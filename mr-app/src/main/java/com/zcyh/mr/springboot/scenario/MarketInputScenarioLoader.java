package com.zcyh.mr.springboot.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import com.zcyh.mr.springboot.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 market_input JSON 加载情景市场数据。
 */
public class MarketInputScenarioLoader {
    private static final Logger log = LoggerFactory.getLogger(MarketInputScenarioLoader.class);

    private static final String FX_SPOT = "FX_SPOT";
    private static final String FX_VOL = "FX_VOL";
    private static final String IR_VOL = "IR_VOL";
    private static final String COMM_VOL = "COMM_VOL";
    private static final String EQ_VOL = "EQ_VOL";
    private static final String ALERT_CODE = "SCENARIO_RISKGROUP_MARKET_MISMATCH";

    private final ScenarioMapper scenarioMapper;
    private final ScenarioMarketQueryPlanner queryPlanner;
    private final MarketInputScenarioValidator marketInputScenarioValidator;
    private final AlertService alertService;

    public MarketInputScenarioLoader(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar) {
        this(scenarioMapper, holidayCalendar, null, resolveDefaultFxSpotBaseCurrency());
    }

    public MarketInputScenarioLoader(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar,
            AlertService alertService) {
        this(scenarioMapper, holidayCalendar, alertService, resolveDefaultFxSpotBaseCurrency());
    }

    public MarketInputScenarioLoader(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar,
            AlertService alertService,
            String fxSpotBaseCurrency) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
        this.scenarioMapper = scenarioMapper;
        this.queryPlanner = new ScenarioMarketQueryPlanner(holidayCalendar);
        this.marketInputScenarioValidator = new MarketInputScenarioValidator(fxSpotBaseCurrency);
        this.alertService = alertService;
    }

    private static String resolveDefaultFxSpotBaseCurrency() {
        String value = Configure.getInstance().getValue(Constants.CFG.FX_SPOT_BASE_CODE);
        if (value == null || value.trim().isEmpty()) {
            return "USD";
        }
        return value.trim().toUpperCase();
    }

    public CurrentLoadResult loadCurrent(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, TypeLoadRequest> requests = buildRequests(definitions);
        Map<String, List<ScenarioMarketSeries>> result = new LinkedHashMap<String, List<ScenarioMarketSeries>>();
        List<String> warnings = new ArrayList<String>();
        if (valuationDate == null || requests.isEmpty()) {
            return new CurrentLoadResult(result, warnings);
        }
        Date sqlDate = Date.valueOf(valuationDate);
        for (TypeLoadRequest request : requests.values()) {
            List<Map<String, Object>> rows = scenarioMapper.selectMarketInputRows(
                    request.getCurveType(),
                    sqlDate,
                    null,
                    request.resolveQueryCurveIds());
            validateMarketInputRows(request, rows);
            appendCurrentRows(result, rows, request, scenarioId);
            warnings.addAll(collectMissingCurrentWarnings(scenarioId, valuationDate, request, result.get(request.getCurveType())));
        }
        return new CurrentLoadResult(result, warnings);
    }

    public Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> loadHistorical(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, TypeLoadRequest> requests = buildRequests(definitions);
        Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result =
                new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();
        if (valuationDate == null || requests.isEmpty()) {
            return result;
        }
        List<ScenarioMarketQueryPlanner.QueryPlan> plans = queryPlanner.planHistorical(definitions, valuationDate);
        for (ScenarioMarketQueryPlanner.QueryPlan plan : plans) {
            String curveType = normalize(plan.getKey().getCurveType());
            TypeLoadRequest request = requests.get(curveType);
            if (request == null) {
                continue;
            }
            request.addRanges(plan.getRanges());
        }
        for (TypeLoadRequest request : requests.values()) {
            for (ScenarioMarketQueryPlanner.DateRange range : request.getRanges()) {
                List<Map<String, Object>> rows = scenarioMapper.selectMarketInputRows(
                        request.getCurveType(),
                        range.getStartDate(),
                        range.getEndDate(),
                        request.resolveQueryCurveIds());
                validateMarketInputRows(request, rows);
                appendHistoricalRows(result, rows, request, scenarioId);
            }
        }
        return result;
    }

    private void validateMarketInputRows(TypeLoadRequest request, List<Map<String, Object>> rows) {
        if (!FX_SPOT.equals(request.getCurveType())) {
            return;
        }
        try {
            marketInputScenarioValidator.validateFxSpotRows(rows);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            log.error(message, ex);
            if (alertService != null) {
                alertService.warn(ALERT_CODE, message);
            }
            throw ex;
        }
    }

    private Map<String, TypeLoadRequest> buildRequests(List<ScenarioDefinition> definitions) {
        Map<String, TypeLoadRequest> requests = new LinkedHashMap<String, TypeLoadRequest>();
        if (definitions == null || definitions.isEmpty()) {
            return requests;
        }
        for (ScenarioDefinition definition : definitions) {
            String curveType = normalize(definition == null ? null : definition.getCurveType());
            if (curveType == null) {
                continue;
            }
            TypeLoadRequest request = requests.computeIfAbsent(curveType, TypeLoadRequest::new);
            request.addRiskGroupId(normalize(definition.getRiskGroupId()));
            String curveCode = normalize(definition.getCurveCode());
            if (curveCode == null) {
                continue;
            }
            if (FX_SPOT.equals(curveType)) {
                if (isFxPair(curveCode)) {
                    request.addCurveCode(curveCode.toUpperCase());
                } else {
                    request.addFxContainerId(curveCode);
                }
                continue;
            }
            request.addCurveCode(curveCode);
        }
        return requests;
    }

    private void appendCurrentRows(
            Map<String, List<ScenarioMarketSeries>> result,
            List<Map<String, Object>> rows,
            TypeLoadRequest request,
            String scenarioId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            List<ScenarioMarketSeries> seriesList = parseRow(row, request, scenarioId);
            if (seriesList.isEmpty()) {
                continue;
            }
            result.computeIfAbsent(request.getCurveType(), key -> new ArrayList<ScenarioMarketSeries>())
                    .addAll(seriesList);
        }
    }

    private void appendHistoricalRows(
            Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result,
            List<Map<String, Object>> rows,
            TypeLoadRequest request,
            String scenarioId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            List<ScenarioMarketSeries> seriesList = parseRow(row, request, scenarioId);
            for (ScenarioMarketSeries series : seriesList) {
                LocalDate dataDate = series.getDataDate();
                if (dataDate == null) {
                    continue;
                }
                result.computeIfAbsent(request.getCurveType(), key -> new LinkedHashMap<LocalDate, List<ScenarioMarketSeries>>())
                        .computeIfAbsent(dataDate, key -> new ArrayList<ScenarioMarketSeries>())
                        .add(series);
            }
        }
    }

    private List<ScenarioMarketSeries> parseRow(
            Map<String, Object> row,
            TypeLoadRequest request,
            String scenarioId) {
        String curveType = normalize(toStringValue(row.get("MARKET_DATA_TYPE")));
        String curveId = normalize(toStringValue(row.get("CURVE_ID")));
        LocalDate dataDate = toLocalDate(row.get("DATA_DATE"));
        String contentText = toStringValue(row.get("CURVE_CONTENT_TEXT"));
        if (curveType == null || contentText == null || dataDate == null) {
            return Collections.emptyList();
        }
        JSONObject root;
        try {
            root = JSONObject.parseObject(contentText);
        } catch (RuntimeException ex) {
            logSkippedRiskFactorPoint(scenarioId, curveType, curveId, dataDate, request, null, null,
                    "市场曲线内容 JSON 解析失败: " + safeText(ex.getMessage()));
            return Collections.emptyList();
        }
        if (root == null) {
            return Collections.emptyList();
        }
        JSONArray curveData = root.getJSONArray("CURVE_DATA");
        if (curveData == null || curveData.isEmpty()) {
            return Collections.emptyList();
        }
        switch (curveType) {
            case "IR_SPOT":
                return parseSpotSeries(scenarioId, curveType, curveId, dataDate, curveData, request, "RATE");
            case "COMM_SPOT":
                return parseSpotSeries(scenarioId, curveType, curveId, dataDate, curveData, request, "COMM_PRICE");
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
            JSONArray curveData,
            TypeLoadRequest request,
            String valueField) {
        if (curveId == null || !request.matchesCurveCode(curveId)) {
            return Collections.emptyList();
        }
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        ParseWarningStats parseStats = new ParseWarningStats();
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.getJSONObject(i);
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
            ScenarioMarketSeries series = buildSeries(curveType, curveId, dataDate, termDays, resolveTermCode(point, termDays), value);
            result.add(series);
        }
        warnSkippedMarketCurvePoints(curveType, curveId, dataDate, parseStats, curveData.size(),
                "TERM", "TERM_DAYS", valueField);
        return result;
    }

    private List<String> collectMissingCurrentWarnings(
            String scenarioId,
            LocalDate valuationDate,
            TypeLoadRequest request,
            List<ScenarioMarketSeries> loadedSeries) {
        if (FX_SPOT.equals(request.getCurveType())) {
            return collectFxSpotCurrentWarnings(scenarioId, valuationDate, request, loadedSeries);
        }
        if (!request.hasRiskGroup() || request.getCurveCodes().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> actualCurveCodes = new LinkedHashSet<String>();
        if (loadedSeries != null) {
            for (ScenarioMarketSeries series : loadedSeries) {
                String curveCode = normalize(series.getCurveCode());
                if (curveCode != null) {
                    actualCurveCodes.add(FX_SPOT.equals(request.getCurveType()) ? curveCode.toUpperCase() : curveCode);
                }
            }
        }
        List<String> missingCurveCodes = new ArrayList<String>();
        for (String curveCode : request.getCurveCodes()) {
            if (!actualCurveCodes.contains(FX_SPOT.equals(request.getCurveType()) ? curveCode.toUpperCase() : curveCode)) {
                missingCurveCodes.add(curveCode);
            }
        }
        if (missingCurveCodes.isEmpty()) {
            return Collections.emptyList();
        }
        String warning = buildMissingCurrentWarning(scenarioId, valuationDate, request, missingCurveCodes, actualCurveCodes);
        log.warn(warning);
        if (alertService != null) {
            alertService.warn(ALERT_CODE, warning);
        }
        return Collections.singletonList(warning);
    }

    private List<String> collectFxSpotCurrentWarnings(
            String scenarioId,
            LocalDate valuationDate,
            TypeLoadRequest request,
            List<ScenarioMarketSeries> loadedSeries) {
        if (!request.hasRiskGroup() && request.getFxContainerIds().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> warnings = new ArrayList<String>();
        Set<String> actualCurveCodes = new LinkedHashSet<String>();
        if (loadedSeries != null) {
            for (ScenarioMarketSeries series : loadedSeries) {
                String curveCode = normalize(series.getCurveCode());
                if (curveCode != null) {
                    actualCurveCodes.add(curveCode.toUpperCase());
                }
            }
        }

        List<String> missingFxContainerIds = new ArrayList<String>();
        for (String fxContainerId : request.getFxContainerIds()) {
            if (!request.getMatchedFxContainerIds().contains(fxContainerId)) {
                missingFxContainerIds.add(fxContainerId);
            }
        }
        if (!missingFxContainerIds.isEmpty()) {
            warnings.add(reportCurrentMismatch(buildFxSpotCurrentWarning(
                    scenarioId,
                    valuationDate,
                    request,
                    missingFxContainerIds,
                    Collections.<String>emptyList(),
                    actualCurveCodes)));
        }

        List<String> missingCurveCodes = new ArrayList<String>();
        for (String curveCode : request.getCurveCodes()) {
            if (!actualCurveCodes.contains(curveCode.toUpperCase())) {
                missingCurveCodes.add(curveCode);
            }
        }
        if (!missingCurveCodes.isEmpty()) {
            warnings.add(reportCurrentMismatch(buildFxSpotCurrentWarning(
                    scenarioId,
                    valuationDate,
                    request,
                    Collections.<String>emptyList(),
                    missingCurveCodes,
                    actualCurveCodes)));
        }

        if (warnings.isEmpty()
                && !request.getFxContainerIds().isEmpty()
                && request.getCurveCodes().isEmpty()
                && request.getMatchedFxContainerIds().size() == request.getFxContainerIds().size()
                && actualCurveCodes.isEmpty()) {
            warnings.add(reportCurrentMismatch(buildFxSpotCurrentWarning(
                    scenarioId,
                    valuationDate,
                    request,
                    Collections.<String>emptyList(),
                    Collections.<String>emptyList(),
                    actualCurveCodes)));
        }
        return warnings;
    }

    private String buildMissingCurrentWarning(
            String scenarioId,
            LocalDate valuationDate,
            TypeLoadRequest request,
            List<String> missingCurveCodes,
            Set<String> actualCurveCodes) {
        return "情景风险组与市场数据不一致"
                + ": scenarioId=" + safeText(scenarioId)
                + ", valuationDate=" + safeText(valuationDate)
                + ", curveType=" + safeText(request.getCurveType())
                + ", riskGroupIds=" + request.getRiskGroupIds()
                + ", missingCurveCodes=" + missingCurveCodes
                + ", actualCurveCodes=" + actualCurveCodes;
    }

    private String buildFxSpotCurrentWarning(
            String scenarioId,
            LocalDate valuationDate,
            TypeLoadRequest request,
            List<String> missingFxContainerIds,
            List<String> missingCurveCodes,
            Set<String> actualCurveCodes) {
        return "情景FX市场数据与配置不一致"
                + ": scenarioId=" + safeText(scenarioId)
                + ", valuationDate=" + safeText(valuationDate)
                + ", curveType=" + safeText(request.getCurveType())
                + ", riskGroupIds=" + request.getRiskGroupIds()
                + ", fxContainerIds=" + request.getFxContainerIds()
                + ", matchedFxContainerIds=" + request.getMatchedFxContainerIds()
                + ", missingFxContainerIds=" + missingFxContainerIds
                + ", requestedCurveCodes=" + request.getCurveCodes()
                + ", missingCurveCodes=" + missingCurveCodes
                + ", actualCurveCodes=" + actualCurveCodes;
    }

    private String reportCurrentMismatch(String warning) {
        log.warn(warning);
        if (alertService != null) {
            alertService.warn(ALERT_CODE, warning);
        }
        return warning;
    }

    private List<ScenarioMarketSeries> parseFxSpotSeries(
            String scenarioId,
            String curveId,
            LocalDate dataDate,
            JSONArray curveData,
            TypeLoadRequest request) {
        if (!request.matchesFxContainer(curveId)) {
            return Collections.emptyList();
        }
        request.markMatchedFxContainer(curveId);
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        boolean includeAllPairs = request.shouldIncludeAllFxPairs(curveId);
        ParseWarningStats parseStats = new ParseWarningStats();
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.getJSONObject(i);
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
            ScenarioMarketSeries series = buildSeries(FX_SPOT, normalizedPair, dataDate, 0, "0", value);
            result.add(series);
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
            JSONArray curveData,
            TypeLoadRequest request) {
        if (curveId == null || !request.matchesCurveCode(curveId)) {
            return Collections.emptyList();
        }
        List<ScenarioMarketSeries> result = new ArrayList<ScenarioMarketSeries>();
        ParseWarningStats parseStats = new ParseWarningStats();
        for (int i = 0; i < curveData.size(); i++) {
            JSONObject point = curveData.getJSONObject(i);
            if (point == null) {
                continue;
            }
            Integer termDays = readInteger(point, parseStats, "OPTION_TERM", "TERM", "TERM_DAYS");
            BigDecimal value = readBigDecimal(point, "VOLATILITY_RATE", parseStats);
            if (termDays == null || value == null) {
                parseStats.skippedPointCount++;
                logSkippedRiskFactorPoint(scenarioId, curveType, curveId, dataDate, request, i, point,
                        "风险因子点字段缺失或格式无效", "OPTION_TERM", "TERM", "TERM_DAYS", "VOLATILITY_RATE");
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
            String termCode = resolveTermCode(point, termDays);
            ScenarioMarketSeries series = buildSeries(curveType, curveId, dataDate, termDays, termCode, value);
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
        if (parseStats == null || parseStats.skippedPointCount <= 0) {
            return;
        }
        log.info("市场曲线无效数据点处理汇总: curveType={}, curveId={}, dataDate={}, fields={}, skippedPointCount={}, missingFieldCount={}, parseErrorFieldCount={}, totalPointCount={}",
                safeText(curveType),
                safeText(curveId),
                safeText(dataDate),
                java.util.Arrays.toString(fieldNames),
                parseStats.skippedPointCount,
                parseStats.missingFieldCount,
                parseStats.parseErrorFieldCount,
                totalCount);
    }

    private void logSkippedRiskFactorPoint(
            String scenarioId,
            String curveType,
            String curveId,
            LocalDate dataDate,
            TypeLoadRequest request,
            Integer pointIndex,
            JSONObject point,
            String reason,
            String... fields) {
        log.warn("情景风险因子已排除: scenarioId={}, riskFactorType={}, riskFactorId={}, riskGroupIds={}, dataDate={}, pointIndex={}, point={}, fields={}, reason={}",
                safeText(scenarioId),
                safeText(curveType),
                safeText(curveId),
                request == null ? Collections.emptySet() : request.getRiskGroupIds(),
                safeText(dataDate),
                pointIndex,
                point == null ? null : point.toJSONString(),
                java.util.Arrays.toString(fields),
                safeText(reason));
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
        if (parseStats == null) {
            return;
        }
        if (parseError) {
            parseStats.parseErrorFieldCount++;
        } else {
            parseStats.missingFieldCount++;
        }
    }

    private String resolveTermCode(JSONObject point, Integer termDays) {
        String termCode = normalize(toStringValue(point.get("TERM_CODE")));
        if (termCode != null) {
            return termCode;
        }
        return termDays == null ? null : termDays + "D";
    }

    /**
     * 波动率曲面第二维统一沿用原始业务字段，不能退化成期限。
     */
    private String resolveVolDimension2(String curveType, JSONObject point) {
        if (point == null) {
            return null;
        }
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

    private LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate();
        }
        if (value instanceof java.util.Date) {
            return new Date(((java.util.Date) value).getTime()).toLocalDate();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() == 8 && text.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(text, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(text);
        } catch (Exception ex) {
            return null;
        }
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

    private boolean isFxPair(String curveCode) {
        return curveCode != null && curveCode.contains("/");
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static class CurrentLoadResult {
        private final Map<String, List<ScenarioMarketSeries>> marketData;
        private final List<String> warnings;

        public CurrentLoadResult(
                Map<String, List<ScenarioMarketSeries>> marketData,
                List<String> warnings) {
            this.marketData = marketData == null
                    ? new LinkedHashMap<String, List<ScenarioMarketSeries>>()
                    : marketData;
            this.warnings = warnings == null ? new ArrayList<String>() : warnings;
        }

        public Map<String, List<ScenarioMarketSeries>> getMarketData() {
            return marketData;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    private String resolveVolDimensionField(String curveType) {
        if (IR_VOL.equals(curveType)) {
            return "UNDERLYING_TERM";
        }
        return "DELTA";
    }

    private static class ParseWarningStats {
        private int skippedPointCount;
        private int missingFieldCount;
        private int parseErrorFieldCount;
    }

    private static class TypeLoadRequest {
        private final String curveType;
        private final Set<String> curveCodes = new LinkedHashSet<String>();
        private final Set<String> riskGroupIds = new LinkedHashSet<String>();
        private final Set<String> fxContainerIds = new LinkedHashSet<String>();
        private final Set<String> matchedFxContainerIds = new LinkedHashSet<String>();
        private final List<ScenarioMarketQueryPlanner.DateRange> ranges = new ArrayList<ScenarioMarketQueryPlanner.DateRange>();
        private final Set<String> rangeKeys = new LinkedHashSet<String>();

        private TypeLoadRequest(String curveType) {
            this.curveType = curveType;
        }

        public String getCurveType() {
            return curveType;
        }

        public Set<String> getCurveCodes() {
            return curveCodes;
        }

        public Set<String> getRiskGroupIds() {
            return riskGroupIds;
        }

        public Set<String> getFxContainerIds() {
            return fxContainerIds;
        }

        public Set<String> getMatchedFxContainerIds() {
            return matchedFxContainerIds;
        }

        public void addRiskGroupId(String riskGroupId) {
            if (riskGroupId != null) {
                riskGroupIds.add(riskGroupId);
            }
        }

        public boolean hasRiskGroup() {
            return !riskGroupIds.isEmpty();
        }

        public void addCurveCode(String curveCode) {
            if (curveCode != null) {
                curveCodes.add(FX_SPOT.equals(curveType) ? curveCode.toUpperCase() : curveCode);
            }
        }

        public void addFxContainerId(String curveId) {
            if (curveId != null) {
                fxContainerIds.add(curveId);
            }
        }

        public void markMatchedFxContainer(String curveId) {
            if (curveId != null) {
                matchedFxContainerIds.add(curveId);
            }
        }

        public boolean matchesCurveCode(String curveCode) {
            if (curveCodes.isEmpty()) {
                return true;
            }
            return curveCode != null && curveCodes.contains(FX_SPOT.equals(curveType) ? curveCode.toUpperCase() : curveCode);
        }

        public boolean matchesFxContainer(String curveId) {
            if (!FX_SPOT.equals(curveType)) {
                return true;
            }
            if (fxContainerIds.isEmpty()) {
                return true;
            }
            return curveId != null && fxContainerIds.contains(curveId);
        }

        public boolean shouldIncludeAllFxPairs(String curveId) {
            if (!FX_SPOT.equals(curveType)) {
                return false;
            }
            return curveCodes.isEmpty();
        }

        public List<String> resolveQueryCurveIds() {
            if (FX_SPOT.equals(curveType)) {
                return Collections.emptyList();
            }
            return curveCodes.isEmpty() ? Collections.emptyList() : new ArrayList<String>(curveCodes);
        }

        public void addRanges(List<ScenarioMarketQueryPlanner.DateRange> dateRanges) {
            if (dateRanges == null || dateRanges.isEmpty()) {
                return;
            }
            for (ScenarioMarketQueryPlanner.DateRange dateRange : dateRanges) {
                if (dateRange == null) {
                    continue;
                }
                String key = String.valueOf(dateRange.getStartDate()) + "|" + String.valueOf(dateRange.getEndDate());
                if (rangeKeys.add(key)) {
                    ranges.add(dateRange);
                }
            }
        }

        public List<ScenarioMarketQueryPlanner.DateRange> getRanges() {
            return ranges;
        }
    }
}
