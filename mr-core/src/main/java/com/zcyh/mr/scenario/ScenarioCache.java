package com.zcyh.mr.scenario;

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
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * 场景数据缓存。
 * 将场景文件一次性加载并解析为 ScenarioEntry 列表后放入缓存，
 * 后续各 Calc 切片通过 cache_key 直接获取，避免重复解析。
 * 当前实现为进程内 ConcurrentHashMap，未来可替换为 Redis 等分布式缓存。
 */
public class ScenarioCache {

    private static final ConcurrentHashMap<String, List<Loader.ScenarioEntry>> CACHE =
            new ConcurrentHashMap<>();

    /** 通用对象缓存，存储任意类型（如 PnL 结果列表、RfetModellableIndex 等） */
    private static final ConcurrentHashMap<String, Object> OBJECT_CACHE =
            new ConcurrentHashMap<>();

    /**
     * 从 CSV 场景文件加载并缓存场景数据。
     *
     * @param filePath  场景文件路径
     * @param dataDate  基准日期
     * @return cache_key（基于文件名生成）
     */
    public static String loadFromFile(String filePath, LocalDate dataDate) {
        Path path = Paths.get(filePath);
        String cacheKey = deriveCacheKey(path);

        if (CACHE.containsKey(cacheKey)) {
            return cacheKey;
        }

        JSONArray scenArray = readScenarioArray(path);
        List<Loader.ScenarioEntry> entries = parseScenarioList(scenArray, dataDate);
        CACHE.put(cacheKey, Collections.unmodifiableList(entries));
        return cacheKey;
    }

    /**
     * 从多个情景文件加载并合并缓存。
     *
     * @param cacheKey   缓存键
     * @param filePaths  场景文件路径列表
     * @param dataDate   基准日期
     * @return cache_key
     */
    public static String loadFromFiles(String cacheKey, List<String> filePaths, LocalDate dataDate) {
        String safeCacheKey = firstNonBlank(cacheKey);
        if (safeCacheKey == null) {
            throw new IllegalArgumentException("scenario cache_key 不能为空");
        }
        if (CACHE.containsKey(safeCacheKey)) {
            return safeCacheKey;
        }
        if (filePaths == null || filePaths.isEmpty()) {
            throw new IllegalArgumentException("scenario 文件列表不能为空, cache_key=" + safeCacheKey);
        }

        JSONArray merged = new JSONArray();
        for (String filePath : filePaths) {
            String safeFilePath = firstNonBlank(filePath);
            if (safeFilePath == null) {
                continue;
            }
            merged.addAll(readScenarioArray(Paths.get(safeFilePath)));
        }
        List<Loader.ScenarioEntry> entries = parseScenarioList(merged, dataDate);
        CACHE.put(safeCacheKey, Collections.unmodifiableList(entries));
        return safeCacheKey;
    }

    /**
     * 直接将 scenario_data JSONArray 加载到缓存。
     * 用于批处理层已组装好场景数据的场景。
     *
     * @param cacheKey   缓存键
     * @param scenData   scenario_data JSON 数组
     * @param dataDate   基准日期
     */
    public static void loadFromArray(String cacheKey, JSONArray scenData, LocalDate dataDate) {
        if (CACHE.containsKey(cacheKey)) {
            return;
        }
        List<Loader.ScenarioEntry> entries = parseScenarioList(scenData, dataDate);
        CACHE.put(cacheKey, Collections.unmodifiableList(entries));
    }

    /**
     * 通过 cache_key 获取场景列表。
     *
     * @param cacheKey 缓存键
     * @return 场景条目列表，不存在时返回 null
     */
    public static List<Loader.ScenarioEntry> get(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return null;
        }
        return CACHE.get(cacheKey);
    }

    /**
     * 检查缓存中是否存在指定 key。
     */
    public static boolean contains(String cacheKey) {
        return cacheKey != null && CACHE.containsKey(cacheKey);
    }

    /**
     * 移除指定缓存。
     */
    public static void evict(String cacheKey) {
        if (cacheKey != null) {
            CACHE.remove(cacheKey);
        }
    }

    /**
     * 清空所有缓存。
     */
    public static void clear() {
        CACHE.clear();
        OBJECT_CACHE.clear();
    }

    /**
     * 直接存入已解析的场景条目列表。
     */
    public static void put(String cacheKey, List<Loader.ScenarioEntry> entries) {
        CACHE.put(cacheKey, Collections.unmodifiableList(new ArrayList<>(entries)));
    }

    // ==================== 通用对象缓存 ====================

    /**
     * 存入任意类型对象（用于跨批次结果复用等非 ScenarioEntry 场景）。
     */
    public static void putObject(String cacheKey, Object value) {
        if (cacheKey != null && value != null) {
            OBJECT_CACHE.put(cacheKey, value);
        }
    }

    /**
     * 获取通用对象缓存。
     */
    public static Object getObject(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return null;
        }
        return OBJECT_CACHE.get(cacheKey);
    }

    /**
     * 移除通用对象缓存。
     */
    public static void evictObject(String cacheKey) {
        if (cacheKey != null) {
            OBJECT_CACHE.remove(cacheKey);
        }
    }

    /**
     * 返回当前缓存的 key 数量。
     */
    public static int size() {
        return CACHE.size();
    }

    /**
     * 从文件路径推导缓存键（使用文件名去掉扩展名）。
     */
    private static String deriveCacheKey(Path path) {
        String fileName = path.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    /**
     * 读取场景文件并解析为 JSONArray。
     * 根据文件后缀自动选择解析方式。
     */
    private static JSONArray readScenarioArray(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".csv") || fileName.endsWith(".csv.gz")) {
            return readCsvAsJsonArray(path);
        }
        throw new IllegalArgumentException("场景文件格式无效，仅支持 .csv 或 .csv.gz: " + path);
    }

    /**
     * 读取 CSV 格式的场景文件，转换为 JSONArray。
     * 首行为列头，后续行为数据。
     */
    private static JSONArray readCsvAsJsonArray(Path path) {
        JSONArray result = new JSONArray();
        try (BufferedReader reader = openCsvReader(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                return result;
            }
            String[] headers = parseCsvLine(headerLine);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] values = parseCsvLine(line);
                JSONObject row = new JSONObject();
                for (int i = 0; i < headers.length && i < values.length; i++) {
                    String val = values[i];
                    row.put(headers[i], val.isEmpty() ? null : val);
                }
                result.add(row);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "加载 CSV 场景文件失败: " + path + ", " + ex.getMessage(), ex);
        }
        return result;
    }

    private static BufferedReader openCsvReader(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".csv.gz")) {
            return new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(path, StandardCharsets.UTF_8);
    }

    /**
     * 解析 CSV 行：支持双引号包裹、转义双引号
     */
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }



    /**
     * 按风险因子分组切分 MarketData（供 Calc 动态过滤使用）。
     */
    public static MarketData sliceByGroup(MarketData src, String group) {
        MarketData md = new MarketData();
        if (src == null || "ALL".equals(group)) {
            return src == null ? md : src;
        }
        switch (group) {
            case "IR":
                md.irSpot = src.irSpot;
                md.irVol = src.irVol;
                break;
            case "FX":
                md.fxSpot = src.fxSpot;
                md.fxVol = src.fxVol;
                break;
            case "EQ":
                md.eqSpot = src.eqSpot;
                md.eqVol = src.eqVol;
                break;
            case "COMM":
                md.commSpot = src.commSpot;
                md.commVol = src.commVol;
                break;
            default:
                break;
        }
        return md;
    }

    /**
     * 从切片后的 MarketData 推导 impact keys（供 Calc 动态过滤使用）。
     */
    public static java.util.Set<String> deriveKeysFromSlice(MarketData md, String group) {
        java.util.Set<String> keys = new LinkedHashSet<>();
        if (md == null) {
            return keys;
        }
        // ALL 组使用原始 impactKeys 或推导全部
        if ("ALL".equals(group)) {
            addMapKeys(keys, "IR_SPOT", md.irSpot);
            addMapKeys(keys, "IR_VOL", md.irVol);
            addMapKeys(keys, "EQ_SPOT", md.eqSpot);
            addMapKeys(keys, "EQ_VOL", md.eqVol);
            addMapKeys(keys, "COMM_SPOT", md.commSpot);
            addMapKeys(keys, "COMM_VOL", md.commVol);
            addMapKeys(keys, "FX_VOL", md.fxVol);
            if (md.fxSpot != null && md.fxSpot.curveData != null) {
                for (String ccyPair : md.fxSpot.curveData.keySet()) {
                    keys.add("FX_SPOT:" + ccyPair.trim().toUpperCase());
                }
            }
            return keys;
        }
        // 单因子组只推导对应类型
        switch (group) {
            case "IR":
                addMapKeys(keys, "IR_SPOT", md.irSpot);
                addMapKeys(keys, "IR_VOL", md.irVol);
                break;
            case "FX":
                addMapKeys(keys, "FX_VOL", md.fxVol);
                if (md.fxSpot != null && md.fxSpot.curveData != null) {
                    for (String ccyPair : md.fxSpot.curveData.keySet()) {
                        keys.add("FX_SPOT:" + ccyPair.trim().toUpperCase());
                    }
                }
                break;
            case "EQ":
                addMapKeys(keys, "EQ_SPOT", md.eqSpot);
                addMapKeys(keys, "EQ_VOL", md.eqVol);
                break;
            case "COMM":
                addMapKeys(keys, "COMM_SPOT", md.commSpot);
                addMapKeys(keys, "COMM_VOL", md.commVol);
                break;
            default:
                break;
        }
        return keys;
    }

    @SuppressWarnings("rawtypes")
    private static void addMapKeys(java.util.Set<String> target, String type, java.util.HashMap map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Object key : map.keySet()) {
            if (key != null) {
                target.add(type + ":" + key.toString().trim().toUpperCase());
            }
        }
    }

    // ==================== 场景扁平记录解析 ====================

    /**
     * 将场景扁平记录列表解析为 ScenarioEntry 列表。
     *
     * 场景文件中每条记录是扁平记录，包含：
     * SCENARIO_ID, SUBSCENARIO_ID, SCENARIO_NAME, SCENARIO_TYPE,
     * CURVE_TYPE, CURVE_CODE, TERM_DAYS, DIMENSION2, CHANGED_RATE 等标准字段。
     *
     * 处理流程：按 SUBSCENARIO_ID 分组 → 每组内按 CURVE_TYPE 构建替换型情景 MarketData → 输出 ScenarioEntry。
     *
     * @param scenArray 场景记录 JSON 数组，每个元素是扁平的键值对
     * @param dataDate  基准日期
     * @return 解析后的 ScenarioEntry 列表
     */
    static List<Loader.ScenarioEntry> parseScenarioList(JSONArray scenArray, LocalDate dataDate) {
        List<Loader.ScenarioEntry> result = new ArrayList<>();
        if (scenArray == null || scenArray.isEmpty()) {
            return result;
        }

        // 按 SUBSCENARIO_ID 分组（同一子情景下有多条曲线记录）
        java.util.LinkedHashMap<String, List<JSONObject>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < scenArray.size(); i++) {
            Object rawObj = scenArray.get(i);
            if (!(rawObj instanceof JSONObject)) {
                continue;
            }
            JSONObject row = (JSONObject) rawObj;
            String scenarioId = readScenarioId(row);
            if (scenarioId == null) {
                throw new IllegalArgumentException("场景记录缺少 SCENARIO_ID，无法解析: index=" + i);
            }
            String subScenarioId = readSubScenarioId(row);
            if (subScenarioId == null) {
                throw new IllegalArgumentException("场景记录缺少 SUBSCENARIO_ID，无法解析: index=" + i);
            }
            String groupKey = subScenarioId;
            groups.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(row);
        }

        // 每个分组构建一个 ScenarioEntry
        for (java.util.Map.Entry<String, List<JSONObject>> entry : groups.entrySet()) {
            List<JSONObject> rows = entry.getValue();
            JSONObject first = rows.get(0);

            String scenarioId = readScenarioId(first);
            if (scenarioId == null) {
                throw new IllegalArgumentException("场景分组首行缺少 SCENARIO_ID，无法解析");
            }
            String subScenarioId = readSubScenarioId(first);
            if (subScenarioId == null) {
                throw new IllegalArgumentException("场景分组首行缺少 SUBSCENARIO_ID，无法解析: scenarioId=" + scenarioId);
            }
            String scenName = readRequiredField(first, "SCENARIO_NAME");
            if (scenName == null) {
                throw new IllegalArgumentException("场景分组首行缺少 SCENARIO_NAME，无法解析: subScenarioId=" + subScenarioId);
            }
            String scenarioType = readRequiredField(first, "SCENARIO_TYPE");
            if (scenarioType == null) {
                throw new IllegalArgumentException("场景分组首行缺少 SCENARIO_TYPE，无法解析: subScenarioId=" + subScenarioId);
            }

            // 收集 impact keys 和构建 MarketData
            java.util.Set<String> impactKeys = new LinkedHashSet<>();
            MarketData scenMarket = new MarketData();

            for (JSONObject row : rows) {
                String curveType = readRequiredField(row, "CURVE_TYPE");
                String curveCode = readRequiredField(row, "CURVE_CODE");
                if (curveType == null || curveCode == null) {
                    throw new IllegalArgumentException("场景记录缺少 CURVE_TYPE 或 CURVE_CODE，无法构建情景市场: subScenarioId=" + subScenarioId);
                }
                impactKeys.add(curveType.trim().toUpperCase() + ":" + curveCode.trim().toUpperCase());

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
                buildScenarioMarketData(scenMarket, curveType, curveCode, row, changedRate, dataDate);
            }

            result.add(new Loader.ScenarioEntry(scenarioId, subScenarioId, scenName, scenarioType, scenMarket, impactKeys));
        }
        return result;
    }

    /**
     * 根据曲线类型将冲击后的点位数据填入 MarketData 对应结构。
     */
    private static void buildScenarioMarketData(
            MarketData target, String curveType, String curveCode,
            JSONObject row, double changedRate, LocalDate dataDate) {

        switch (curveType) {
            case "IR_SPOT": {
                IrSpot.IrSpotInfo info = target.irSpot.computeIfAbsent(curveCode, k -> {
                    IrSpot.IrSpotInfo n = new IrSpot.IrSpotInfo();
                    n.pDataDate = dataDate;
                    return n;
                });
                Integer term = parseTermDays(row);
                if (term == null) {
                    throw new IllegalArgumentException("IR_SPOT 场景记录缺少 TERM_DAYS: curveCode=" + curveCode);
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
    private static void addFxLikeVolPoint(List<Map<String, Object>> curveData, JSONObject row, double changedRate) {
        Integer optionTerm = parseTermDays(row);
        String delta = parseVolAxis2(row);
        if (optionTerm == null) {
            throw new IllegalArgumentException("波动率场景记录缺少 TERM_DAYS，无法替换情景市场数据");
        }
        if (delta == null) {
            throw new IllegalArgumentException("波动率场景记录缺少 DIMENSION2，无法替换情景市场数据");
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("DELTA", parseNumericAxis(delta));
        point.put("VOLATILITY_RATE", changedRate);
        curveData.add(point);
    }

    /**
     * 还原 IR 波动率曲面点，第二维使用 underlying term。
     */
    private static void addIrVolPoint(List<Map<String, Object>> curveData, JSONObject row, double changedRate) {
        Integer optionTerm = parseTermDays(row);
        String underlyingTerm = parseVolAxis2(row);
        if (optionTerm == null) {
            throw new IllegalArgumentException("IR_VOL 场景记录缺少 TERM_DAYS，无法替换情景市场数据");
        }
        if (underlyingTerm == null) {
            throw new IllegalArgumentException("IR_VOL 场景记录缺少 DIMENSION2，无法替换情景市场数据");
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("UNDERLYING_TERM", parseNumericAxis(underlyingTerm));
        point.put("VOLATILITY_RATE", changedRate);
        curveData.add(point);
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
    private static Object parseNumericAxis(String value) {
        String normalized = firstNonBlank(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return normalized;
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
}
