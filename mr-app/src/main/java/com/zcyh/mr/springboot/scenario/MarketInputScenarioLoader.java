package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import com.zcyh.mr.springboot.service.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 market_input JSON 加载情景市场数据。
 */
public class MarketInputScenarioLoader {
    private static final Logger log = LoggerFactory.getLogger(MarketInputScenarioLoader.class);

    private static final String FX_SPOT = "FX_SPOT";
    private static final String ALERT_CODE = "SCENARIO_RISKGROUP_MARKET_MISMATCH";

    private final ScenarioMapper scenarioMapper;
    private final ScenarioMarketQueryPlanner queryPlanner;
    private final MarketInputScenarioValidator marketInputScenarioValidator;
    private final ScenarioMarketSeriesParser seriesParser;
    private final ScenarioMarketCoverageValidator coverageValidator;
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
        this.seriesParser = new ScenarioMarketSeriesParser();
        this.coverageValidator = new ScenarioMarketCoverageValidator(alertService);
        this.alertService = alertService;
    }

    public CurrentLoadResult loadCurrent(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, ScenarioMarketLoadRequest> requests = buildRequests(definitions);
        Map<String, List<ScenarioMarketSeries>> result =
                new LinkedHashMap<String, List<ScenarioMarketSeries>>();
        List<String> warnings = new ArrayList<String>();
        if (valuationDate == null || requests.isEmpty()) {
            return new CurrentLoadResult(result, warnings);
        }
        Date sqlDate = Date.valueOf(valuationDate);
        for (ScenarioMarketLoadRequest request : requests.values()) {
            List<Map<String, Object>> rows = scenarioMapper.selectMarketInputRows(
                    request.getCurveType(), sqlDate, null, request.resolveQueryCurveIds());
            List<ScenarioMarketCurveRow> parsedRows = ScenarioMarketCurveRow.parseAll(rows);
            validateMarketInputRows(request, parsedRows);
            appendCurrentRows(result, parsedRows, request, scenarioId);
            warnings.addAll(coverageValidator.collectMissingCurrentWarnings(
                    scenarioId, valuationDate, request, result.get(request.getCurveType())));
        }
        return new CurrentLoadResult(result, warnings);
    }

    public Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> loadHistorical(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, ScenarioMarketLoadRequest> requests = buildRequests(definitions);
        Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result =
                new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();
        if (valuationDate == null || requests.isEmpty()) {
            return result;
        }
        List<ScenarioMarketQueryPlanner.QueryPlan> plans = queryPlanner.planHistorical(definitions, valuationDate);
        for (ScenarioMarketQueryPlanner.QueryPlan plan : plans) {
            String curveType = normalize(plan.getKey().getCurveType());
            ScenarioMarketLoadRequest request = requests.get(curveType);
            if (request != null) {
                request.addRanges(plan.getRanges());
            }
        }
        for (ScenarioMarketLoadRequest request : requests.values()) {
            for (ScenarioMarketQueryPlanner.DateRange range : request.getRanges()) {
                List<Map<String, Object>> rows = scenarioMapper.selectMarketInputRows(
                        request.getCurveType(),
                        range.getStartDate(),
                        range.getEndDate(),
                        request.resolveQueryCurveIds());
                List<ScenarioMarketCurveRow> parsedRows = ScenarioMarketCurveRow.parseAll(rows);
                validateMarketInputRows(request, parsedRows);
                appendHistoricalRows(result, parsedRows, request, scenarioId);
            }
        }
        return result;
    }

    private void validateMarketInputRows(
            ScenarioMarketLoadRequest request,
            List<ScenarioMarketCurveRow> rows) {
        if (!FX_SPOT.equals(request.getCurveType())) {
            return;
        }
        try {
            marketInputScenarioValidator.validateParsedFxSpotRows(rows);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            log.error(message, ex);
            if (alertService != null) {
                alertService.warn(ALERT_CODE, message);
            }
            throw ex;
        }
    }

    private Map<String, ScenarioMarketLoadRequest> buildRequests(List<ScenarioDefinition> definitions) {
        Map<String, ScenarioMarketLoadRequest> requests =
                new LinkedHashMap<String, ScenarioMarketLoadRequest>();
        if (definitions == null || definitions.isEmpty()) {
            return requests;
        }
        for (ScenarioDefinition definition : definitions) {
            String curveType = normalize(definition == null ? null : definition.getCurveType());
            if (curveType == null) {
                continue;
            }
            ScenarioMarketLoadRequest request =
                    requests.computeIfAbsent(curveType, ScenarioMarketLoadRequest::new);
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
            } else {
                request.addCurveCode(curveCode);
            }
        }
        return requests;
    }

    private void appendCurrentRows(
            Map<String, List<ScenarioMarketSeries>> result,
            List<ScenarioMarketCurveRow> rows,
            ScenarioMarketLoadRequest request,
            String scenarioId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (ScenarioMarketCurveRow row : rows) {
            List<ScenarioMarketSeries> seriesList = seriesParser.parseRow(row, request, scenarioId);
            if (!seriesList.isEmpty()) {
                result.computeIfAbsent(
                                request.getCurveType(), ignored -> new ArrayList<ScenarioMarketSeries>())
                        .addAll(seriesList);
            }
        }
    }

    private void appendHistoricalRows(
            Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result,
            List<ScenarioMarketCurveRow> rows,
            ScenarioMarketLoadRequest request,
            String scenarioId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (ScenarioMarketCurveRow row : rows) {
            for (ScenarioMarketSeries series : seriesParser.parseRow(row, request, scenarioId)) {
                LocalDate dataDate = series.getDataDate();
                if (dataDate != null) {
                    result.computeIfAbsent(
                                    request.getCurveType(),
                                    ignored -> new LinkedHashMap<LocalDate, List<ScenarioMarketSeries>>())
                            .computeIfAbsent(dataDate, ignored -> new ArrayList<ScenarioMarketSeries>())
                            .add(series);
                }
            }
        }
    }

    private static String resolveDefaultFxSpotBaseCurrency() {
        String value = Configure.getInstance().getValue(Constants.CFG.FX_SPOT_BASE_CODE);
        if (value == null || value.trim().isEmpty()) {
            return "USD";
        }
        return value.trim().toUpperCase();
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

    public static class CurrentLoadResult {
        private final Map<String, List<ScenarioMarketSeries>> marketData;
        private final List<String> warnings;

        public CurrentLoadResult(
                Map<String, List<ScenarioMarketSeries>> marketData,
                List<String> warnings) {
            this.marketData = marketData == null
                    ? new LinkedHashMap<String, List<ScenarioMarketSeries>>() : marketData;
            this.warnings = warnings == null ? new ArrayList<String>() : warnings;
        }

        public Map<String, List<ScenarioMarketSeries>> getMarketData() {
            return marketData;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
