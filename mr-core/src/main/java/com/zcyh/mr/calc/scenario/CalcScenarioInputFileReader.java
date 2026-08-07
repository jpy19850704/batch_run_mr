package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 计量情景文件读取与标准情景条目解析器。
 */
final class CalcScenarioInputFileReader {

    List<Loader.ScenarioEntry> readScenarioEntries(List<Path> paths, LocalDate dataDate) {
        return readScenarioEntries(paths, dataDate, null);
    }

    List<Loader.ScenarioEntry> readScenarioEntries(
            List<Path> paths,
            LocalDate dataDate,
            Set<String> scenarioMarketKeys) {
        return readScenarioLoadResult(paths, dataDate, scenarioMarketKeys, Long.MAX_VALUE).entries;
    }

    ScenarioLoadResult readScenarioLoadResult(
            List<Path> paths,
            LocalDate dataDate,
            Set<String> scenarioMarketKeys,
            long maxRetainedPoints) {
        LinkedHashMap<String, ScenarioGroup> groups = new LinkedHashMap<>();
        Set<String> normalizedMarketKeys = normalizeMarketKeys(scenarioMarketKeys);
        PointCounter pointCounter = new PointCounter(maxRetainedPoints);
        int rowIndex = 0;
        for (Path path : paths) {
            String fileName = path.getFileName().toString().toLowerCase();
            if (!fileName.endsWith(".csv") && !fileName.endsWith(".csv.gz")) {
                throw new IllegalArgumentException("场景文件格式无效，仅支持 .csv 或 .csv.gz: " + path);
            }
            try (BufferedReader reader = openReader(path)) {
                String headerLine = reader.readLine();
                if (headerLine == null || headerLine.trim().isEmpty()) {
                    continue;
                }
                String[] headers = parseCsvLine(headerLine);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    accumulateRow(
                            groups,
                            toRow(headers, parseCsvLine(line)),
                            rowIndex++,
                            dataDate,
                            normalizedMarketKeys,
                            pointCounter);
                }
            } catch (IOException ex) {
                throw new IllegalArgumentException("加载 CSV 场景文件失败: " + path + ", " + ex.getMessage(), ex);
            }
        }
        return new ScenarioLoadResult(toScenarioEntries(groups), pointCounter.rawPoints, pointCounter.retainedPoints);
    }

    private static JSONObject toRow(String[] headers, String[] values) {
        JSONObject row = new JSONObject();
        for (int i = 0; i < headers.length && i < values.length; i++) {
            row.put(headers[i], values[i].isEmpty() ? null : values[i]);
        }
        return row;
    }

    private BufferedReader openReader(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".csv.gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (inQuotes) {
                if (current == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    value.append(current);
                }
            } else if (current == '"') {
                inQuotes = true;
            } else if (current == ',') {
                fields.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        fields.add(value.toString());
        return fields.toArray(new String[0]);
    }
    // ==================== 场景扁平记录解析 ====================

    /**
     * 将场景扁平记录列表解析为 ScenarioEntry 列表。
     *
     * 场景文件中每条记录是扁平记录，包含：
     * SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE,
     * CURVE_TYPE, CURVE_CODE, TERM_DAYS, DIMENSION2, CHANGED_RATE 等标准字段。
     *
     * 处理流程：按 SCENARIO_ID + SUBSCENARIO_ID 分组 → 每组内按 CURVE_TYPE 构建替换型情景 MarketData → 输出 ScenarioEntry。
     *
     * @param scenArray 场景记录 JSON 数组，每个元素是扁平的键值对
     * @param dataDate  基准日期
     * @return 解析后的 ScenarioEntry 列表
     */
    List<Loader.ScenarioEntry> parseScenarioList(JSONArray scenArray, LocalDate dataDate) {
        return parseScenarioList(scenArray, dataDate, null);
    }

    List<Loader.ScenarioEntry> parseScenarioList(
            JSONArray scenArray,
            LocalDate dataDate,
            Set<String> scenarioMarketKeys) {
        return parseScenarioLoadResult(scenArray, dataDate, scenarioMarketKeys, Long.MAX_VALUE).entries;
    }

    ScenarioLoadResult parseScenarioLoadResult(
            JSONArray scenArray,
            LocalDate dataDate,
            Set<String> scenarioMarketKeys,
            long maxRetainedPoints) {
        LinkedHashMap<String, ScenarioGroup> groups = new LinkedHashMap<>();
        Set<String> normalizedMarketKeys = normalizeMarketKeys(scenarioMarketKeys);
        PointCounter pointCounter = new PointCounter(maxRetainedPoints);
        if (scenArray == null || scenArray.isEmpty()) {
            return new ScenarioLoadResult(new ArrayList<>(), 0L, 0L);
        }
        for (int i = 0; i < scenArray.size(); i++) {
            Object rawObj = scenArray.get(i);
            if (!(rawObj instanceof JSONObject)) {
                continue;
            }
            accumulateRow(groups, (JSONObject) rawObj, i, dataDate, normalizedMarketKeys, pointCounter);
        }
        return new ScenarioLoadResult(toScenarioEntries(groups), pointCounter.rawPoints, pointCounter.retainedPoints);
    }

    private static void accumulateRow(
            LinkedHashMap<String, ScenarioGroup> groups,
            JSONObject row,
            int rowIndex,
            LocalDate dataDate,
            Set<String> scenarioMarketKeys,
            PointCounter pointCounter) {
        pointCounter.recordRawPoint();
        String scenarioId = readScenarioId(row);
        if (scenarioId == null) {
            throw new IllegalArgumentException("场景记录缺少 SCENARIO_ID，无法解析: index=" + rowIndex);
        }
        String subScenarioId = readSubScenarioId(row);
        if (subScenarioId == null) {
            throw new IllegalArgumentException("场景记录缺少 SUBSCENARIO_ID，无法解析: index=" + rowIndex);
        }
        String groupKey = scenarioId + "|" + subScenarioId;
        ScenarioGroup group = groups.get(groupKey);
        if (group == null) {
            String scenarioName = readRequiredField(row, "SCENARIO_NAME");
            if (scenarioName == null) {
                throw new IllegalArgumentException("场景分组首行缺少 SCENARIO_NAME，无法解析: subScenarioId=" + subScenarioId);
            }
            String scenarioType = readRequiredField(row, "SCENARIO_TYPE");
            if (scenarioType == null) {
                throw new IllegalArgumentException("场景分组首行缺少 SCENARIO_TYPE，无法解析: subScenarioId=" + subScenarioId);
            }
            group = new ScenarioGroup(scenarioId, subScenarioId, scenarioName, scenarioType);
            groups.put(groupKey, group);
        }

        String curveType = readRequiredField(row, "CURVE_TYPE");
        String curveCode = readRequiredField(row, "CURVE_CODE");
        if (curveType == null || curveCode == null) {
            throw new IllegalArgumentException("场景记录缺少 CURVE_TYPE 或 CURVE_CODE，无法构建情景市场: subScenarioId=" + subScenarioId);
        }
        String normalizedCurveType = curveType.trim().toUpperCase(Locale.ROOT);
        String impactKey = normalizedCurveType + ":" + curveCode.trim().toUpperCase(Locale.ROOT);
        if (!matchesMarketKey(scenarioMarketKeys, normalizedCurveType, impactKey)) {
            return;
        }
        pointCounter.recordRetainedPoint();
        group.impactKeys.add(impactKey);

        Object changedRateObj = row.get("CHANGED_RATE");
        if (changedRateObj == null) {
            throw new IllegalArgumentException("场景记录缺少 CHANGED_RATE，无法构建情景市场: subScenarioId=" + subScenarioId
                    + ", curveType=" + curveType + ", curveCode=" + curveCode);
        }
        double changedRate;
        try {
            changedRate = Double.parseDouble(changedRateObj.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("场景记录的 CHANGED_RATE 不是合法数值，无法构建情景市场: subScenarioId="
                    + subScenarioId + ", curveType=" + curveType + ", curveCode=" + curveCode
                    + ", value=" + changedRateObj, e);
        }
        buildScenarioMarketData(group.marketData, curveType, curveCode, row, changedRate, dataDate);
    }

    private static Set<String> normalizeMarketKeys(Set<String> scenarioMarketKeys) {
        if (scenarioMarketKeys == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String key : scenarioMarketKeys) {
            if (key != null && !key.trim().isEmpty()) {
                normalized.add(key.trim().toUpperCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private static boolean matchesMarketKey(
            Set<String> scenarioMarketKeys,
            String curveType,
            String impactKey) {
        if (scenarioMarketKeys == null) {
            return true;
        }
        return scenarioMarketKeys.contains(curveType) || scenarioMarketKeys.contains(impactKey);
    }

    private static List<Loader.ScenarioEntry> toScenarioEntries(LinkedHashMap<String, ScenarioGroup> groups) {
        List<Loader.ScenarioEntry> result = new ArrayList<>(groups.size());
        for (ScenarioGroup group : groups.values()) {
            result.add(new Loader.ScenarioEntry(
                    group.scenarioId,
                    group.subScenarioId,
                    group.scenarioName,
                    group.scenarioType,
                    group.marketData,
                    group.impactKeys));
        }
        return result;
    }

    private static final class ScenarioGroup {
        private final String scenarioId;
        private final String subScenarioId;
        private final String scenarioName;
        private final String scenarioType;
        private final MarketData marketData = new MarketData();
        private final java.util.Set<String> impactKeys = new LinkedHashSet<>();

        private ScenarioGroup(String scenarioId, String subScenarioId, String scenarioName, String scenarioType) {
            this.scenarioId = scenarioId;
            this.subScenarioId = subScenarioId;
            this.scenarioName = scenarioName;
            this.scenarioType = scenarioType;
        }
    }

    /**
     * 根据曲线类型将冲击后的点位数据填入 MarketData 对应结构。
     */
    private static void buildScenarioMarketData(
            MarketData target, String curveType, String curveCode,
            JSONObject row, double changedRate, LocalDate dataDate) {

        switch (curveType) {
            case "IR_SPOT":
            case "CREDIT_SPOT": {
                IrSpot.IrSpotInfo info = target.irSpot.computeIfAbsent(curveCode, k -> {
                    IrSpot.IrSpotInfo n = new IrSpot.IrSpotInfo();
                    n.curveType = curveType;
                    n.curveCode = curveCode;
                    n.pDataDate = dataDate;
                    return n;
                });
                info.curveType = curveType;
                info.curveCode = curveCode;
                Integer term = parseTermDays(row);
                if (term == null) {
                    throw new IllegalArgumentException(curveType + " 场景记录缺少 TERM_DAYS: curveCode=" + curveCode);
                }
                info.curveData.put(term, changedRate);
                break;
            }
            case "FX_SPOT": {
                if (target.fxSpot == null) {
                    target.fxSpot = new FxSpot.FxSpotInfo();
                    target.fxSpot.pDataDate = dataDate;
                }
                target.fxSpot.curveData.put(curveCode, changedRate);
                break;
            }
            case "COMM_SPOT": {
                CommSpot.CommSpotInfo info = target.commSpot.computeIfAbsent(curveCode, k -> {
                    CommSpot.CommSpotInfo n = new CommSpot.CommSpotInfo();
                    n.pDataDate = dataDate;
                    return n;
                });
                Integer term = parseTermDays(row);
                if (term == null) {
                    throw new IllegalArgumentException("COMM_SPOT 场景记录缺少 TERM_DAYS: curveCode=" + curveCode);
                }
                info.curveData.put(term, changedRate);
                break;
            }
            case "EQ_SPOT": {
                EqSpot.EqSpotInfo info = target.eqSpot.computeIfAbsent(curveCode, k -> {
                    EqSpot.EqSpotInfo n = new EqSpot.EqSpotInfo();
                    n.pDataDate = dataDate;
                    return n;
                });
                Integer term = parseTermDays(row);
                if (term == null) {
                    throw new IllegalArgumentException("EQ_SPOT 场景记录缺少 TERM_DAYS: curveCode=" + curveCode);
                }
                info.curveData.put(term, changedRate);
                break;
            }
            case "FX_VOL": {
                FxVol.FxVolInfo info = target.fxVol.computeIfAbsent(curveCode, k -> {
                    FxVol.FxVolInfo n = new FxVol.FxVolInfo();
                    n.curveType = curveType;
                    n.curveCode = curveCode;
                    n.pDataDate = dataDate;
                    n.termInterpolateType = "LINERVAR";
                    n.axis2Type = "DELTA";
                    n.axis2InterpolateType = "linear";
                    return n;
                });
                addFxLikeVolPoint(info.curveData, row, changedRate);
                break;
            }
            case "IR_VOL": {
                IrVol.IrVolInfo info = target.irVol.computeIfAbsent(curveCode, k -> {
                    IrVol.IrVolInfo n = new IrVol.IrVolInfo();
                    n.curveType = curveType;
                    n.curveCode = curveCode;
                    n.pDataDate = dataDate;
                    n.termInterpolateType = "LINERVAR";
                    n.axis2Type = "UNDERLYING_TERM";
                    n.axis2InterpolateType = "linear";
                    return n;
                });
                addIrVolPoint(info.curveData, row, changedRate);
                break;
            }
            case "COMM_VOL": {
                CommVol.CommVolInfo info = target.commVol.computeIfAbsent(curveCode, k -> {
                    CommVol.CommVolInfo n = new CommVol.CommVolInfo();
                    n.curveType = curveType;
                    n.curveCode = curveCode;
                    n.pDataDate = dataDate;
                    n.termInterpolateType = "LINERVAR";
                    n.axis2Type = "DELTA";
                    n.axis2InterpolateType = "linear";
                    return n;
                });
                addFxLikeVolPoint(info.curveData, row, changedRate);
                break;
            }
            case "EQ_VOL": {
                EqVol.EqVolInfo info = target.eqVol.computeIfAbsent(curveCode, k -> {
                    EqVol.EqVolInfo n = new EqVol.EqVolInfo();
                    n.curveType = curveType;
                    n.curveCode = curveCode;
                    n.pDataDate = dataDate;
                    n.termInterpolateType = "LINERVAR";
                    n.axis2Type = "DELTA";
                    n.axis2InterpolateType = "linear";
                    return n;
                });
                addFxLikeVolPoint(info.curveData, row, changedRate);
                break;
            }
            default:
                break;
        }
    }

    /**
     * 从扁平记录中解析 TERM_DAYS。
     */
    private static Integer parseTermDays(JSONObject row) {
        Object termDays = row.get("TERM_DAYS");
        if (termDays != null) {
            try {
                return Integer.parseInt(termDays.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /**
     * 还原 FX/EQ/COMM 波动率曲面点，第二维使用 delta。
     */
    private static void addFxLikeVolPoint(List<VolSurfacePoint> curveData, JSONObject row, double changedRate) {
        Integer optionTerm = parseTermDays(row);
        String delta = parseVolAxis2(row);
        if (optionTerm == null) {
            throw new IllegalArgumentException("波动率场景记录缺少 TERM_DAYS，无法替换情景市场数据");
        }
        if (delta == null) {
            throw new IllegalArgumentException("波动率场景记录缺少 DIMENSION2，无法替换情景市场数据");
        }
        curveData.add(new VolSurfacePoint(optionTerm, parseNumericAxis(delta), changedRate));
    }

    /**
     * 还原 IR 波动率曲面点，第二维使用 underlying term。
     */
    private static void addIrVolPoint(List<VolSurfacePoint> curveData, JSONObject row, double changedRate) {
        Integer optionTerm = parseTermDays(row);
        String underlyingTerm = parseVolAxis2(row);
        if (optionTerm == null) {
            throw new IllegalArgumentException("IR_VOL 场景记录缺少 TERM_DAYS，无法替换情景市场数据");
        }
        if (underlyingTerm == null) {
            throw new IllegalArgumentException("IR_VOL 场景记录缺少 DIMENSION2，无法替换情景市场数据");
        }
        curveData.add(new VolSurfacePoint(
                optionTerm, parseNumericAxis(underlyingTerm), changedRate));
    }

    /**
     * 场景文件里的第二维只认 DIMENSION2。
     */
    private static String parseVolAxis2(JSONObject row) {
        return readRequiredField(row, "DIMENSION2");
    }

    /**
     * 第二维允许是数字或字符串，优先恢复为数值，便于波动率插值工具直接使用。
     */
    private static double parseNumericAxis(String value) {
        String normalized = firstNonBlank(value);
        if (normalized == null) {
            throw new IllegalArgumentException("DIMENSION2不能为空");
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("DIMENSION2必须为数值: " + value, ex);
        }
    }

    /**
     * 读取标准场景主键（统一字段名：SCENARIO_ID）。
     */
    private static String readScenarioId(JSONObject row) {
        if (row == null) {
            return null;
        }
        return readRequiredField(row, "SCENARIO_ID");
    }

    /**
     * 读取标准子情景主键（统一字段名：SUBSCENARIO_ID）。
     */
    private static String readSubScenarioId(JSONObject row) {
        if (row == null) {
            return null;
        }
        return readRequiredField(row, "SUBSCENARIO_ID");
    }

    /**
     * 读取标准字段，不再兼容旧别名。
     */
    private static String readRequiredField(JSONObject row, String fieldName) {
        if (row == null || fieldName == null) {
            return null;
        }
        Object raw = row.get(fieldName);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 返回第一个非空白字符串。
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null) {
                String trimmed = v.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    static final class ScenarioLoadResult {
        final List<Loader.ScenarioEntry> entries;
        final long rawPoints;
        final long retainedPoints;

        ScenarioLoadResult(List<Loader.ScenarioEntry> entries, long rawPoints, long retainedPoints) {
            this.entries = entries;
            this.rawPoints = rawPoints;
            this.retainedPoints = retainedPoints;
        }
    }

    private static final class PointCounter {
        private final long maxRetainedPoints;
        private long rawPoints;
        private long retainedPoints;

        private PointCounter(long maxRetainedPoints) {
            if (maxRetainedPoints <= 0L) {
                throw new IllegalArgumentException("剪裁后情景点数上限必须大于0");
            }
            this.maxRetainedPoints = maxRetainedPoints;
        }

        private void recordRawPoint() {
            rawPoints++;
        }

        private void recordRetainedPoint() {
            if (retainedPoints >= maxRetainedPoints) {
                throw new IllegalStateException("剪裁后情景点数超过缓存上限: maxRetainedPoints="
                        + maxRetainedPoints + ", rawPoints=" + rawPoints);
            }
            retainedPoints++;
        }
    }
}
