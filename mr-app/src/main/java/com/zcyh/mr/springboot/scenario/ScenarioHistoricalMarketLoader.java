package com.zcyh.mr.springboot.scenario;

import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情景历史市场数据加载器。
 *
 * <p>
 * 该类负责综合全部情景定义推导查询窗口，并按曲线类型加载历史市场数据。
 */
public class ScenarioHistoricalMarketLoader {

    private final ScenarioMapper scenarioMapper;
    private final ScenarioMarketQueryPlanner queryPlanner;

    public ScenarioHistoricalMarketLoader(
            ScenarioMapper scenarioMapper,
            com.zcyh.mr.core.Calendar holidayCalendar) {
        if (scenarioMapper == null) {
            throw new IllegalArgumentException("scenarioMapper 不能为空");
        }
        this.scenarioMapper = scenarioMapper;
        this.queryPlanner = new ScenarioMarketQueryPlanner(holidayCalendar);
    }

    /**
     * 加载历史市场数据。
     */
    public Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> load(
            String scenarioId,
            LocalDate valuationDate,
            List<ScenarioDefinition> definitions) {
        Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result =
                new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();

        List<ScenarioMarketQueryPlanner.QueryPlan> plans = queryPlanner.planHistorical(definitions, valuationDate);
        if (plans.isEmpty()) {
            return result;
        }

        for (ScenarioMarketQueryPlanner.QueryPlan plan : plans) {
            for (ScenarioMarketQueryPlanner.DateRange range : plan.getRanges()) {
                List<Map<String, Object>> rows = queryMarketData(scenarioId, plan, range);
                for (Map<String, Object> row : rows) {
                    LocalDate dataDate = toLocalDate(row.get("DATA_DATE"));
                    if (dataDate == null) {
                        continue;
                    }
                    result.computeIfAbsent(plan.getKey().getCurveType(), key -> new LinkedHashMap<LocalDate, List<ScenarioMarketSeries>>())
                            .computeIfAbsent(dataDate, key -> new ArrayList<ScenarioMarketSeries>())
                            .add(convertSeriesRow(row));
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> queryMarketData(
            String scenarioId,
            ScenarioMarketQueryPlanner.QueryPlan plan,
            ScenarioMarketQueryPlanner.DateRange range) {
        String curveType = plan.getKey().getCurveType();
        String curveCode = plan.getKey().getCurveCode();
        if (curveType == null || curveType.trim().isEmpty()) {
            return Collections.emptyList();
        }
        switch (curveType.trim()) {
            case "IR_SPOT":
                return emptyIfNull(scenarioMapper.selectIrData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "FX_SPOT":
                return emptyIfNull(scenarioMapper.selectFxData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "COMM_SPOT":
                return emptyIfNull(scenarioMapper.selectCommData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "EQ_SPOT":
                return emptyIfNull(scenarioMapper.selectEqData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "FX_VOL":
                return emptyIfNull(scenarioMapper.selectFxVolData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "IR_VOL":
                return emptyIfNull(scenarioMapper.selectIrVolData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "COMM_VOL":
                return emptyIfNull(scenarioMapper.selectCommVolData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            case "EQ_VOL":
                return emptyIfNull(scenarioMapper.selectEqVolData(scenarioId, curveCode, range.getStartDate(), range.getEndDate()));
            default:
                return Collections.emptyList();
        }
    }

    private ScenarioMarketSeries convertSeriesRow(Map<String, Object> row) {
        ScenarioMarketSeries series = new ScenarioMarketSeries();
        series.setCurveType(toStringValue(row.get("CURVE_TYPE")));
        series.setCurveCode(toStringValue(row.get("CURVE_CODE")));
        series.setDataDate(toLocalDate(row.get("DATA_DATE")));
        series.setTermCode(toStringValue(row.get("TERM_CODE")));
        series.setTermDays(toInteger(row.get("TERM_DAYS")));
        series.setDimension2(firstNonBlank(row.get("VERTEX2"), row.get("UNDERLYING_TERM"), row.get("VOLATILITY_TERM")));
        series.setValue(toBigDecimal(row.get("RISKFACTOR_VALUE")));
        return series;
    }

    private List<Map<String, Object>> emptyIfNull(List<Map<String, Object>> rows) {
        return rows == null ? Collections.emptyList() : rows;
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String text = toStringValue(value);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception ex) {
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
            java.util.Date date = (java.util.Date) value;
            return new Date(date.getTime()).toLocalDate();
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

}
