package com.zcyh.mr.springboot.scenario;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 情景市场曲线解析器。
 */
final class ScenarioMarketSeriesParser {
    private static final Logger log = LoggerFactory.getLogger(ScenarioMarketSeriesParser.class);

    private static final String FX_SPOT = "FX_SPOT";
    private static final String FX_VOL = "FX_VOL";
    private static final String IR_VOL = "IR_VOL";
    private static final String COMM_VOL = "COMM_VOL";
    private static final String EQ_VOL = "EQ_VOL";

    List<ScenarioMarketSeries> parseRow(
            Map<String, Object> row,
            ScenarioMarketLoadRequest request,
            String scenarioId) {
        return parseRow(ScenarioMarketCurveRow.parse(row), request, scenarioId);
    }

    List<ScenarioMarketSeries> parseRow(
            ScenarioMarketCurveRow row,
            ScenarioMarketLoadRequest request,
            String scenarioId) {
        String curveType = row.getCurveType();
        String curveId = row.getCurveId();
        LocalDate dataDate = row.getDataDate();
        String contentText = row.getContentText();
        if (curveType == null || contentText == null || dataDate == null) {
            return Collections.emptyList();
        }
        if (row.getParseException() != null) {
            RuntimeException ex = row.getParseException();
            logSkippedRiskFactorPoint(scenarioId, curveType, curveId, dataDate, request, null, null,
                    "市场曲线内容 JSON 解析失败: " + safeText(ex.getMessage()));
            return Collections.emptyList();
        }
        List<JSONObject> curveData = row.getCurveData();
        if (!row.isCurveDataPresent() || curveData.isEmpty()) {
            return Collections.emptyList();
        }
        switch (curveType) {
            case "IR_SPOT":
                return parseSpotSeries(scenarioId, curveType, curveId, dataDate, curveData, request, "RATE");
            case "COMM_SPOT":
                return parseSpotSeries(
                        scenarioId, curveType, curveId, dataDate, curveData, request, "COMM_PRICE");
            case "EQ_SPOT":
                return parseSpotSeries(scenarioId, curveType, curveId, dataDate, curveData, request, "EQ_PRICE");
            case FX_SPOT:
                return parseFxSpotSeries(scenarioId, curveId, dataDate, curveData, request);
            case FX_VOL:
            case IR_VOL:
            case COMM_VOL:
            case EQ_VOL:
                return parseVolSeries(scenarioId, curveType, curveId, dataDate, curveData, request);
            default:
                return Collections.emptyList();
        }
    }

    private List<ScenarioMarketSeries> parseSpotSeries(
            String scenarioId,
            String curveType,
            String curveId,
            LocalDate dataDate,
            List<JSONObject> curveData,
            ScenarioMarketLoadRequest request,
            String valueField) {
        if (curveId == null || !request.matchesCurveCode(curveId)) {
            return Collections.emptyList();
        }
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        ParseWarningStats parseStats = new ParseWarningStats();
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.get(i);
            if (point == null) {
                continue;
            }
            Integer termDays = readInteger(point, parseStats, "TERM", "TERM_DAYS");
            BigDecimal value = readBigDecimal(point, valueField, parseStats);
            if (termDays == null || value == null) {
                parseStats.skippedPointCount++;
                logSkippedRiskFactorPoint(scenarioId, curveType, curveId, dataDate, request, i, point,
                        "风险因子点字段缺失或格式无效", "TERM", "TERM_DAYS", valueField);
                continue;
            }
            result.add(buildSeries(
                    curveType, curveId, dataDate, termDays, resolveTermCode(point, termDays), value));
        }
        warnSkippedMarketCurvePoints(curveType, curveId, dataDate, parseStats, curveData.size(),
                "TERM", "TERM_DAYS", valueField);
        return result;
    }

    private List<ScenarioMarketSeries> parseFxSpotSeries(
            String scenarioId,
            String curveId,
            LocalDate dataDate,
            List<JSONObject> curveData,
            ScenarioMarketLoadRequest request) {
        if (!request.matchesFxContainer(curveId)) {
            return Collections.emptyList();
        }
        request.markMatchedFxContainer(curveId);
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        boolean includeAllPairs = request.shouldIncludeAllFxPairs();
        ParseWarningStats parseStats = new ParseWarningStats();
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.get(i);
            if (point == null) {
                continue;
            }
            String currencyPair = readRequiredText(point, "CURRENCY", parseStats);
            BigDecimal value = readBigDecimal(point, "RATE", parseStats);
            if (currencyPair == null || value == null) {
                parseStats.skippedPointCount++;
                logSkippedRiskFactorPoint(scenarioId, FX_SPOT, curveId, dataDate, request, i, point,
                        "风险因子点字段缺失或格式无效", "CURRENCY", "RATE");
                continue;
            }
            String normalizedPair = currencyPair.toUpperCase();
            if (!includeAllPairs && !request.matchesCurveCode(normalizedPair)) {
                continue;
            }
            result.add(buildSeries(FX_SPOT, normalizedPair, dataDate, 0, "0", value));
        }
        warnSkippedMarketCurvePoints(FX_SPOT, curveId, dataDate, parseStats, curveData.size(),
                "CURRENCY", "RATE");
        return result;
    }

    private List<ScenarioMarketSeries> parseVolSeries(
            String scenarioId,
            String curveType,
            String curveId,
            LocalDate dataDate,
            List<JSONObject> curveData,
            ScenarioMarketLoadRequest request) {
        if (curveId == null || !request.matchesCurveCode(curveId)) {
            return Collections.emptyList();
        }
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        ParseWarningStats parseStats = new ParseWarningStats();
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.get(i);
            if (point == null) {
                continue;
            }
            Integer termDays = readInteger(point, parseStats, "OPTION_TERM", "TERM", "TERM_DAYS");
            BigDecimal value = readBigDecimal(point, "VOLATILITY_RATE", parseStats);
            if (termDays == null || value == null) {
                parseStats.skippedPointCount++;
                logSkippedRiskFactorPoint(scenarioId, curveType, curveId, dataDate, request, i, point,
                        "风险因子点字段缺失或格式无效",
                        "OPTION_TERM", "TERM", "TERM_DAYS", "VOLATILITY_RATE");
                continue;
            }
            String dimension2 = resolveVolDimension2(curveType, point);
            if (dimension2 == null) {
                parseStats.skippedPointCount++;
                parseStats.missingFieldCount++;
                logSkippedRiskFactorPoint(scenarioId, curveType, curveId, dataDate, request, i, point,
                        "风险因子点缺少第二维", resolveVolDimensionField(curveType));
                continue;
            }
            ScenarioMarketSeries series = buildSeries(
                    curveType, curveId, dataDate, termDays, resolveTermCode(point, termDays), value);
            series.setDimension2(dimension2);
            result.add(series);
        }
        warnSkippedMarketCurvePoints(curveType, curveId, dataDate, parseStats, curveData.size(),
                "OPTION_TERM", "TERM", "TERM_DAYS", "VOLATILITY_RATE");
        return result;
    }

    private void warnSkippedMarketCurvePoints(
            String curveType,
            String curveId,
            LocalDate dataDate,
            ParseWarningStats parseStats,
            int totalCount,
            String... fieldNames) {
        if (parseStats.skippedPointCount <= 0) {
            return;
        }
        log.info("市场曲线无效数据点处理汇总: curveType={}, curveId={}, dataDate={}, fields={}, skippedPointCount={}, missingFieldCount={}, parseErrorFieldCount={}, totalPointCount={}",
                safeText(curveType), safeText(curveId), safeText(dataDate),
                java.util.Arrays.toString(fieldNames), parseStats.skippedPointCount,
                parseStats.missingFieldCount, parseStats.parseErrorFieldCount, totalCount);
    }

    private void logSkippedRiskFactorPoint(
            String scenarioId,
            String curveType,
            String curveId,
            LocalDate dataDate,
            ScenarioMarketLoadRequest request,
            Integer pointIndex,
            JSONObject point,
            String reason,
            String... fields) {
        log.warn("情景风险因子已排除: scenarioId={}, riskFactorType={}, riskFactorId={}, riskGroupIds={}, dataDate={}, pointIndex={}, point={}, fields={}, reason={}",
                safeText(scenarioId), safeText(curveType), safeText(curveId),
                request == null ? Collections.emptySet() : request.getRiskGroupIds(), safeText(dataDate),
                pointIndex, point == null ? null : point.toJSONString(),
                java.util.Arrays.toString(fields), safeText(reason));
    }

    private ScenarioMarketSeries buildSeries(
            String curveType,
            String curveCode,
            LocalDate dataDate,
            Integer termDays,
            String termCode,
            BigDecimal value) {
        ScenarioMarketSeries series = new ScenarioMarketSeries();
        series.setCurveType(curveType);
        series.setCurveCode(curveCode);
        series.setDataDate(dataDate);
        series.setTermDays(termDays);
        series.setTermCode(termCode);
        series.setValue(value);
        return series;
    }

    private Integer readInteger(JSONObject point, ParseWarningStats parseStats, String... fieldNames) {
        boolean hasValue = false;
        for (String fieldName : fieldNames) {
            Object value = point.get(fieldName);
            if (value == null) {
                continue;
            }
            hasValue = true;
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (Exception ignored) {
            }
        }
        recordInvalidField(parseStats, hasValue);
        return null;
    }

    private BigDecimal readBigDecimal(JSONObject point, String fieldName, ParseWarningStats parseStats) {
        Object value = point.get(fieldName);
        if (value == null) {
            recordInvalidField(parseStats, false);
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception ex) {
            recordInvalidField(parseStats, true);
            return null;
        }
    }

    private String readRequiredText(JSONObject point, String fieldName, ParseWarningStats parseStats) {
        String value = normalize(toStringValue(point.get(fieldName)));
        if (value == null) {
            recordInvalidField(parseStats, false);
        }
        return value;
    }

    private void recordInvalidField(ParseWarningStats parseStats, boolean parseError) {
        if (parseError) {
            parseStats.parseErrorFieldCount++;
        } else {
            parseStats.missingFieldCount++;
        }
    }

    private String resolveTermCode(JSONObject point, Integer termDays) {
        String termCode = normalize(toStringValue(point.get("TERM_CODE")));
        return termCode != null ? termCode : termDays + "D";
    }

    private String resolveVolDimension2(String curveType, JSONObject point) {
        switch (curveType) {
            case IR_VOL:
                return normalize(toStringValue(point.get("UNDERLYING_TERM")));
            case FX_VOL:
            case COMM_VOL:
            case EQ_VOL:
                return normalize(toStringValue(point.get("DELTA")));
            default:
                return null;
        }
    }

    private String resolveVolDimensionField(String curveType) {
        return IR_VOL.equals(curveType) ? "UNDERLYING_TERM" : "DELTA";
    }

    private String toStringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class ParseWarningStats {
        private int skippedPointCount;
        private int missingFieldCount;
        private int parseErrorFieldCount;
    }
}
