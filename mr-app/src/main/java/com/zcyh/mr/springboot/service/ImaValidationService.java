package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.validation.backtest.BacktestMultiplierTable;
import com.zcyh.mr.frtbima.validation.backtest.DeskLevelBacktest;
import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;
import com.zcyh.mr.frtbima.validation.common.ValidationConstants;
import com.zcyh.mr.frtbima.validation.model.BacktestResult;
import com.zcyh.mr.frtbima.validation.model.DailyPnl;
import com.zcyh.mr.frtbima.validation.model.ExceptionDetail;
import com.zcyh.mr.frtbima.validation.pla.KolmogorovSmirnovTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * IMA 返回检验与 KS 检验服务。
 */
@Service
public class ImaValidationService {
    private static final String RISK_CLASS_ALL = "ALL";
    private static final String VALIDATION_TYPE_BACKTEST = "BACKTEST";
    private static final String VALIDATION_TYPE_KS = "KS";
    private static final int REQUIRED_OBSERVATION_COUNT = 250;
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbcTemplate;
    private final ImaValidationResultPersistService persistService;
    private final DeskLevelBacktest backtest = new DeskLevelBacktest();
    private final BacktestMultiplierTable multiplierTable = new BacktestMultiplierTable();
    private final KolmogorovSmirnovTest ksTest = new KolmogorovSmirnovTest();

    public ImaValidationService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            ImaValidationResultPersistService persistService) {
        this.jdbcTemplate = jdbcTemplate;
        this.persistService = persistService;
    }

    public String calculate(String inputJson) {
        JSONObject request = JSON.parseObject(inputJson);
        if (request == null) {
            throw new IllegalArgumentException("IMA 校验输入不能为空");
        }
        String validationType = normalizeValidationType(required(request, "validation_type"));
        String batchId = required(request, "batch_id");
        String dataDate = normalizeDate(required(request, "data_date"), "data_date");
        String ruleId = required(request, "rule_id");
        String quantile = VALIDATION_TYPE_BACKTEST.equals(validationType) ? required(request, "quantile") : null;
        String varScenarioId = VALIDATION_TYPE_BACKTEST.equals(validationType) ? required(request, "var_scenario_id") : null;
        boolean persistResult = readBoolean(request, "persist_result", true);

        List<String> observationDates = queryObservationDates(dataDate, ruleId);
        if (VALIDATION_TYPE_KS.equals(validationType) && observationDates.size() != REQUIRED_OBSERVATION_COUNT) {
            throw new IllegalArgumentException("IMA 返回检验需要最近250个有效观测日: data_date=" + dataDate
                    + ", rule_id=" + ruleId + ", actual_count=" + observationDates.size());
        }
        String startDate = observationDates.isEmpty() ? dataDate : observationDates.get(0);
        String endDate = observationDates.isEmpty() ? dataDate : observationDates.get(observationDates.size() - 1);

        Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup =
                VALIDATION_TYPE_BACKTEST.equals(validationType)
                        ? queryVarRows(batchId, dataDate, ruleId, quantile, varScenarioId) : null;
        if (VALIDATION_TYPE_BACKTEST.equals(validationType)) {
            if (varByGroup.isEmpty()) {
                throw new IllegalArgumentException("未查询到匹配的 VaR 结果: rule_id=" + ruleId
                        + ", batch_id=" + batchId + ", data_date=" + dataDate);
            }
            startDate = minVarDate(varByGroup);
            endDate = maxVarDate(varByGroup);
        }
        Map<GroupKey, List<ExternalPnlRow>> pnlRows = queryExternalPnl(startDate, endDate, ruleId);
        if (VALIDATION_TYPE_KS.equals(validationType) && pnlRows.isEmpty()) {
            throw new IllegalArgumentException("未查询到 IMA 外部接入分组 PnL: rule_id=" + ruleId);
        }

        List<ImaValidationResultPersistService.BacktestRow> backtestRows =
                VALIDATION_TYPE_BACKTEST.equals(validationType)
                        ? new ArrayList<ImaValidationResultPersistService.BacktestRow>() : null;
        List<ImaValidationResultPersistService.ExceptionRow> exceptionRows =
                VALIDATION_TYPE_BACKTEST.equals(validationType)
                        ? new ArrayList<ImaValidationResultPersistService.ExceptionRow>() : null;
        List<ImaValidationResultPersistService.KsRow> ksRows =
                VALIDATION_TYPE_KS.equals(validationType)
                        ? new ArrayList<ImaValidationResultPersistService.KsRow>() : null;
        JSONArray responseBacktestRows = new JSONArray();
        JSONArray responseExceptionRows = new JSONArray();
        JSONArray responseKsRows = new JSONArray();

        if (VALIDATION_TYPE_BACKTEST.equals(validationType)) {
            Map<GroupKey, TreeMap<LocalDate, ExternalPnlRow>> pnlByGroupDate = indexPnlRows(pnlRows);
            for (Map.Entry<GroupKey, TreeMap<LocalDate, BigDecimal>> entry : varByGroup.entrySet()) {
                GroupKey groupKey = entry.getKey();
                TreeMap<LocalDate, BigDecimal> groupVarRows = entry.getValue();
                List<DailyPnl> series = buildDailySeries(groupVarRows, pnlByGroupDate.get(groupKey));
                BacktestResult backtestResult = backtest.run(series);
                ImaValidationResultPersistService.BacktestRow backtestRow =
                        toBacktestRow(batchId, dataDate, startDate, endDate, ruleId, groupKey, quantile,
                                varScenarioId, series.size(), backtestResult, multiplierTable);
                backtestRows.add(backtestRow);
                responseBacktestRows.add(toBacktestJson(backtestRow));

                for (ExceptionDetail detail : backtestResult.getExceptions()) {
                    ImaValidationResultPersistService.ExceptionRow exceptionRow =
                            toExceptionRow(batchId, dataDate, startDate, endDate, ruleId, groupKey,
                                    quantile, varScenarioId, detail);
                    exceptionRows.add(exceptionRow);
                    responseExceptionRows.add(toExceptionJson(exceptionRow));
                }
            }
        }

        for (Map.Entry<GroupKey, List<ExternalPnlRow>> entry : pnlRows.entrySet()) {
            GroupKey groupKey = entry.getKey();
            List<ExternalPnlRow> rows = entry.getValue();
            rows.sort(Comparator.comparing(row -> row.dataDate));

            if (VALIDATION_TYPE_KS.equals(validationType)) {
                validateGroupObservationCount(rows, groupKey);
                BigDecimal ksStatistic = ksTest.compute(readHypotheticalSeries(rows), readRiskTheoreticalSeries(rows));
                String ksZone = evaluateKsZone(ksStatistic);
                ImaValidationResultPersistService.KsRow ksRow =
                        toKsRow(batchId, dataDate, startDate, endDate, ruleId, groupKey,
                                rows.size(), ksStatistic, ksZone);
                ksRows.add(ksRow);
                responseKsRows.add(toKsJson(ksRow));
            }
        }

        if (persistResult) {
            persistService.replace(batchId, dataDate, startDate, endDate, ruleId, quantile, varScenarioId,
                    backtestRows, exceptionRows, ksRows);
        }

        JSONObject response = new JSONObject();
        response.put("validation_type", validationType);
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("start_date", startDate);
        response.put("end_date", endDate);
        response.put("rule_id", ruleId);
        if (VALIDATION_TYPE_BACKTEST.equals(validationType)) {
            response.put("quantile", quantile);
            response.put("var_scenario_id", varScenarioId);
        }
        response.put("sample_size", REQUIRED_OBSERVATION_COUNT);
        response.put("persist_result", persistResult);
        response.put("backtest_results", responseBacktestRows);
        response.put("backtest_exception_details", responseExceptionRows);
        response.put("ks_results", responseKsRows);
        return JSON.toJSONString(response, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private List<String> queryObservationDates(String dataDate, String ruleId) {
        String sql = "SELECT DATA_DATE FROM ("
                + "SELECT DISTINCT DATA_DATE FROM TB_EXTERNAL_IMA_GROUP_PNL "
                + "WHERE RULE_ID=? AND DATA_DATE<=? "
                + "ORDER BY DATA_DATE DESC LIMIT " + REQUIRED_OBSERVATION_COUNT
                + ") t ORDER BY DATA_DATE";
        List<String> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, ruleId);
                    ps.setString(2, dataDate);
                },
                (rs, rowNum) -> normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE"));
        List<String> result = new ArrayList<String>();
        for (String row : rows) {
            result.add(row);
        }
        return result;
    }

    private Map<GroupKey, List<ExternalPnlRow>> queryExternalPnl(String startDate, String endDate, String ruleId) {
        String sql = "SELECT DATA_DATE, RULE_ID, GROUP_TYPE, GROUP_VALUE, ACTUAL_PNL, "
                + "HYPOTHETICAL_PNL, RISK_THEORETICAL_PNL, VALUATION_CCY "
                + "FROM TB_EXTERNAL_IMA_GROUP_PNL "
                + "WHERE RULE_ID=? AND DATA_DATE BETWEEN ? AND ? "
                + "ORDER BY GROUP_TYPE, GROUP_VALUE, DATA_DATE";
        List<ExternalPnlRow> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, ruleId);
                    ps.setString(2, startDate);
                    ps.setString(3, endDate);
                },
                (rs, rowNum) -> {
                    ExternalPnlRow row = new ExternalPnlRow();
                    row.dataDateText = normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE");
                    row.dataDate = parseDate(row.dataDateText, "DATA_DATE");
                    row.ruleId = requireText(rs.getString("RULE_ID"), "RULE_ID");
                    row.groupType = requireText(rs.getString("GROUP_TYPE"), "GROUP_TYPE");
                    row.groupValue = requireText(rs.getString("GROUP_VALUE"), "GROUP_VALUE");
                    row.actualPnl = requireDecimal(rs.getBigDecimal("ACTUAL_PNL"), "ACTUAL_PNL", row);
                    row.hypotheticalPnl = requireDecimal(rs.getBigDecimal("HYPOTHETICAL_PNL"), "HYPOTHETICAL_PNL", row);
                    row.riskTheoreticalPnl = requireDecimal(rs.getBigDecimal("RISK_THEORETICAL_PNL"), "RISK_THEORETICAL_PNL", row);
                    row.valuationCcy = trimToNull(rs.getString("VALUATION_CCY"));
                    return row;
                });

        Map<GroupKey, List<ExternalPnlRow>> result = new LinkedHashMap<GroupKey, List<ExternalPnlRow>>();
        for (ExternalPnlRow row : rows) {
            GroupKey key = new GroupKey(row.groupType, row.groupValue);
            List<ExternalPnlRow> groupRows = result.get(key);
            if (groupRows == null) {
                groupRows = new ArrayList<ExternalPnlRow>();
                result.put(key, groupRows);
            }
            groupRows.add(row);
        }
        return result;
    }

    private Map<GroupKey, TreeMap<LocalDate, BigDecimal>> queryVarRows(String batchId,
                                                                       String endDate,
                                                                       String ruleId,
                                                                       String quantile,
                                                                       String varScenarioId) {
        List<String> varDates = queryVarObservationDates(batchId, endDate, ruleId, quantile, varScenarioId);
        Map<GroupKey, TreeMap<LocalDate, BigDecimal>> result = new LinkedHashMap<GroupKey, TreeMap<LocalDate, BigDecimal>>();
        if (varDates.isEmpty()) {
            return result;
        }
        String sql = "SELECT DATA_DATE, GROUP_TYPE, GROUP_VALUE, VAR "
                + "FROM TB_OUT_VAR_RESULT "
                + "WHERE BATCH_ID=? AND RULE_ID=? AND QUANTILE=? AND SCENARIO_ID=? "
                + "AND RISK_CLASS=? AND DATA_DATE IN (" + placeholders(varDates.size()) + ") "
                + "ORDER BY GROUP_TYPE, GROUP_VALUE, DATA_DATE";
        List<Object> params = new ArrayList<Object>();
        params.add(batchId);
        params.add(ruleId);
        params.add(quantile);
        params.add(varScenarioId);
        params.add(RISK_CLASS_ALL);
        params.addAll(varDates);
        List<VarRow> rows = jdbcTemplate.query(
                sql,
                ps -> {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setObject(i + 1, params.get(i));
                    }
                },
                (rs, rowNum) -> {
                    VarRow row = new VarRow();
                    row.dataDateText = normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE");
                    row.dataDate = parseDate(row.dataDateText, "DATA_DATE");
                    row.groupType = requireText(rs.getString("GROUP_TYPE"), "GROUP_TYPE");
                    row.groupValue = requireText(rs.getString("GROUP_VALUE"), "GROUP_VALUE");
                    row.varValue = requireDecimal(rs.getBigDecimal("VAR"), "VAR", row);
                    return row;
                });

        for (VarRow row : rows) {
            GroupKey key = new GroupKey(row.groupType, row.groupValue);
            TreeMap<LocalDate, BigDecimal> dateValues = result.get(key);
            if (dateValues == null) {
                dateValues = new TreeMap<LocalDate, BigDecimal>();
                result.put(key, dateValues);
            }
            dateValues.put(row.dataDate, row.varValue);
        }
        return result;
    }

    private List<String> queryVarObservationDates(String batchId,
                                                  String endDate,
                                                  String ruleId,
                                                  String quantile,
                                                  String varScenarioId) {
        String sql = "SELECT DATA_DATE FROM ("
                + "SELECT DISTINCT DATA_DATE FROM TB_OUT_VAR_RESULT "
                + "WHERE BATCH_ID=? AND RULE_ID=? AND QUANTILE=? AND SCENARIO_ID=? "
                + "AND RISK_CLASS=? AND DATA_DATE < ? "
                + "ORDER BY DATA_DATE DESC LIMIT " + REQUIRED_OBSERVATION_COUNT
                + ") t ORDER BY DATA_DATE";
        return jdbcTemplate.query(
                sql,
                ps -> {
                    ps.setString(1, batchId);
                    ps.setString(2, ruleId);
                    ps.setString(3, quantile);
                    ps.setString(4, varScenarioId);
                    ps.setString(5, RISK_CLASS_ALL);
                    ps.setString(6, endDate);
                },
                (rs, rowNum) -> normalizeDate(rs.getString("DATA_DATE"), "DATA_DATE"));
    }

    private Map<GroupKey, TreeMap<LocalDate, ExternalPnlRow>> indexPnlRows(Map<GroupKey, List<ExternalPnlRow>> pnlRows) {
        Map<GroupKey, TreeMap<LocalDate, ExternalPnlRow>> result =
                new LinkedHashMap<GroupKey, TreeMap<LocalDate, ExternalPnlRow>>();
        for (Map.Entry<GroupKey, List<ExternalPnlRow>> entry : pnlRows.entrySet()) {
            TreeMap<LocalDate, ExternalPnlRow> dateRows = new TreeMap<LocalDate, ExternalPnlRow>();
            for (ExternalPnlRow row : entry.getValue()) {
                dateRows.put(row.dataDate, row);
            }
            result.put(entry.getKey(), dateRows);
        }
        return result;
    }

    private List<DailyPnl> buildDailySeries(TreeMap<LocalDate, BigDecimal> groupVarRows,
                                            TreeMap<LocalDate, ExternalPnlRow> pnlRows) {
        List<DailyPnl> series = new ArrayList<DailyPnl>();
        for (Map.Entry<LocalDate, BigDecimal> varEntry : groupVarRows.entrySet()) {
            LocalDate date = varEntry.getKey();
            BigDecimal varValue = varEntry.getValue();
            ExternalPnlRow pnlRow = pnlRows == null ? null : pnlRows.get(date);
            if (pnlRow == null) {
                BigDecimal threshold = varValue.abs().negate();
                BigDecimal missingPnl = threshold.subtract(new BigDecimal("0.01"));
                series.add(new DailyPnl(date, missingPnl, null, null, varValue));
                continue;
            }
            series.add(new DailyPnl(
                    date,
                    pnlRow.actualPnl,
                    pnlRow.hypotheticalPnl,
                    pnlRow.riskTheoreticalPnl,
                    varValue));
        }
        return series;
    }

    private static String minVarDate(Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup) {
        LocalDate min = null;
        for (TreeMap<LocalDate, BigDecimal> rows : varByGroup.values()) {
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            LocalDate first = rows.firstKey();
            if (min == null || first.isBefore(min)) {
                min = first;
            }
        }
        return min == null ? null : min.format(BASIC_DATE);
    }

    private static String maxVarDate(Map<GroupKey, TreeMap<LocalDate, BigDecimal>> varByGroup) {
        LocalDate max = null;
        for (TreeMap<LocalDate, BigDecimal> rows : varByGroup.values()) {
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            LocalDate last = rows.lastKey();
            if (max == null || last.isAfter(max)) {
                max = last;
            }
        }
        return max == null ? null : max.format(BASIC_DATE);
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private static void validateGroupObservationCount(List<ExternalPnlRow> rows, GroupKey groupKey) {
        int count = rows == null ? 0 : rows.size();
        if (count != REQUIRED_OBSERVATION_COUNT) {
            throw new IllegalArgumentException("IMA 返回检验分组样本数必须为250: group_type="
                    + groupKey.groupType + ", group_value=" + groupKey.groupValue + ", actual_count=" + count);
        }
    }

    private static List<BigDecimal> readHypotheticalSeries(List<ExternalPnlRow> series) {
        List<BigDecimal> values = new ArrayList<BigDecimal>();
        for (ExternalPnlRow row : series) {
            values.add(row.hypotheticalPnl);
        }
        return values;
    }

    private static List<BigDecimal> readRiskTheoreticalSeries(List<ExternalPnlRow> series) {
        List<BigDecimal> values = new ArrayList<BigDecimal>();
        for (ExternalPnlRow row : series) {
            values.add(row.riskTheoreticalPnl);
        }
        return values;
    }

    private static ImaValidationResultPersistService.BacktestRow toBacktestRow(
            String batchId,
            String dataDate,
            String startDate,
            String endDate,
            String ruleId,
            GroupKey groupKey,
            String quantile,
            String varScenarioId,
            int sampleSize,
            BacktestResult result,
            BacktestMultiplierTable multiplierTable) {
        int actualCount = countExceptionType(result, ExceptionDetail.PNL_TYPE_ACTUAL);
        int hypotheticalCount = countExceptionType(result, ExceptionDetail.PNL_TYPE_HYPOTHETICAL);
        int overallCount = Math.max(actualCount, hypotheticalCount);

        ImaValidationResultPersistService.BacktestRow row = new ImaValidationResultPersistService.BacktestRow();
        row.batchId = batchId;
        row.dataDate = dataDate;
        row.startDate = startDate;
        row.endDate = endDate;
        row.ruleId = ruleId;
        row.groupType = groupKey.groupType;
        row.groupValue = groupKey.groupValue;
        row.quantile = quantile;
        row.varScenarioId = varScenarioId;
        row.sampleSize = sampleSize;
        row.actualExceptionCount = actualCount;
        row.hypotheticalExceptionCount = hypotheticalCount;
        row.overallExceptionCount = overallCount;
        row.zone = TrafficLightZone.fromExceptions(overallCount);
        row.multiplierAddOn = multiplierTable.lookup(overallCount);
        return row;
    }

    private static ImaValidationResultPersistService.ExceptionRow toExceptionRow(
            String batchId,
            String dataDate,
            String startDate,
            String endDate,
            String ruleId,
            GroupKey groupKey,
            String quantile,
            String varScenarioId,
            ExceptionDetail detail) {
        ImaValidationResultPersistService.ExceptionRow row = new ImaValidationResultPersistService.ExceptionRow();
        row.batchId = batchId;
        row.dataDate = dataDate;
        row.startDate = startDate;
        row.endDate = endDate;
        row.exceptionDate = detail.getDate().format(BASIC_DATE);
        row.ruleId = ruleId;
        row.groupType = groupKey.groupType;
        row.groupValue = groupKey.groupValue;
        row.quantile = quantile;
        row.varScenarioId = varScenarioId;
        row.pnlType = detail.getPnlType();
        row.pnl = detail.getPnl();
        row.varValue = detail.getVarValue();
        row.threshold = detail.getThreshold();
        return row;
    }

    private static ImaValidationResultPersistService.KsRow toKsRow(
            String batchId,
            String dataDate,
            String startDate,
            String endDate,
            String ruleId,
            GroupKey groupKey,
            int sampleSize,
            BigDecimal ksStatistic,
            String ksZone) {
        ImaValidationResultPersistService.KsRow row = new ImaValidationResultPersistService.KsRow();
        row.batchId = batchId;
        row.dataDate = dataDate;
        row.startDate = startDate;
        row.endDate = endDate;
        row.ruleId = ruleId;
        row.groupType = groupKey.groupType;
        row.groupValue = groupKey.groupValue;
        row.sampleSize = sampleSize;
        row.ksStatistic = ksStatistic;
        row.ksZone = ksZone;
        row.passed = "GREEN".equals(ksZone);
        return row;
    }

    private static int countExceptionType(BacktestResult result, String pnlType) {
        int count = 0;
        for (ExceptionDetail detail : result.getExceptions()) {
            if (pnlType.equals(detail.getPnlType())) {
                count++;
            }
        }
        return count;
    }

    private static String evaluateKsZone(BigDecimal ksStatistic) {
        if (ksStatistic.compareTo(ValidationConstants.PLA_KS_GREEN_THRESHOLD) < 0) {
            return "GREEN";
        }
        if (ksStatistic.compareTo(ValidationConstants.PLA_KS_RED_THRESHOLD) <= 0) {
            return "AMBER";
        }
        return "RED";
    }

    private static JSONObject toBacktestJson(ImaValidationResultPersistService.BacktestRow row) {
        JSONObject json = new JSONObject();
        json.put("data_date", row.dataDate);
        json.put("start_date", row.startDate);
        json.put("end_date", row.endDate);
        json.put("rule_id", row.ruleId);
        json.put("group_type", row.groupType);
        json.put("group_value", row.groupValue);
        json.put("sample_size", row.sampleSize);
        json.put("actual_exception_count", row.actualExceptionCount);
        json.put("hypothetical_exception_count", row.hypotheticalExceptionCount);
        json.put("overall_exception_count", row.overallExceptionCount);
        json.put("traffic_light_zone", row.zone == null ? null : row.zone.name());
        json.put("multiplier_add_on", row.multiplierAddOn);
        return json;
    }

    private static JSONObject toExceptionJson(ImaValidationResultPersistService.ExceptionRow row) {
        JSONObject json = new JSONObject();
        json.put("data_date", row.dataDate);
        json.put("start_date", row.startDate);
        json.put("end_date", row.endDate);
        json.put("exception_date", row.exceptionDate);
        json.put("rule_id", row.ruleId);
        json.put("group_type", row.groupType);
        json.put("group_value", row.groupValue);
        json.put("pnl_type", row.pnlType);
        json.put("pnl", row.pnl);
        json.put("var_value", row.varValue);
        json.put("threshold", row.threshold);
        return json;
    }

    private static JSONObject toKsJson(ImaValidationResultPersistService.KsRow row) {
        JSONObject json = new JSONObject();
        json.put("data_date", row.dataDate);
        json.put("start_date", row.startDate);
        json.put("end_date", row.endDate);
        json.put("rule_id", row.ruleId);
        json.put("group_type", row.groupType);
        json.put("group_value", row.groupValue);
        json.put("sample_size", row.sampleSize);
        json.put("ks_statistic", row.ksStatistic);
        json.put("ks_zone", row.ksZone);
        json.put("passed", row.passed);
        return json;
    }

    private static boolean readBoolean(JSONObject json, String key, boolean defaultValue) {
        if (!json.containsKey(key)) {
            return defaultValue;
        }
        Boolean value = json.getBoolean(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " 必须是布尔值");
        }
        return value;
    }

    private static String required(JSONObject json, String key) {
        return requireText(json.getString(key), key);
    }

    private static String normalizeValidationType(String validationType) {
        String value = validationType.trim().toUpperCase(Locale.ROOT);
        if (!VALIDATION_TYPE_BACKTEST.equals(value) && !VALIDATION_TYPE_KS.equals(value)) {
            throw new IllegalArgumentException("validation_type 仅支持 BACKTEST 或 KS");
        }
        return value;
    }

    private static String requireText(String text, String fieldName) {
        String value = trimToNull(text);
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }

    private static BigDecimal requireDecimal(BigDecimal value, String fieldName, Object row) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为空: " + row);
        }
        return value;
    }

    private static String normalizeDate(String text, String fieldName) {
        String value = requireText(text, fieldName).replace("-", "");
        parseDate(value, fieldName);
        return value;
    }

    private static LocalDate parseDate(String text, String fieldName) {
        try {
            return LocalDate.parse(text, BASIC_DATE);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " 必须为 yyyyMMdd 或 yyyy-MM-dd: " + text);
        }
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    private static class GroupKey {
        private final String groupType;
        private final String groupValue;

        private GroupKey(String groupType, String groupValue) {
            this.groupType = groupType;
            this.groupValue = groupValue;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof GroupKey)) {
                return false;
            }
            GroupKey other = (GroupKey) obj;
            return groupType.equals(other.groupType) && groupValue.equals(other.groupValue);
        }

        @Override
        public int hashCode() {
            return 31 * groupType.hashCode() + groupValue.hashCode();
        }
    }

    private static class ExternalPnlRow {
        private String dataDateText;
        private LocalDate dataDate;
        private String ruleId;
        private String groupType;
        private String groupValue;
        private BigDecimal actualPnl;
        private BigDecimal hypotheticalPnl;
        private BigDecimal riskTheoreticalPnl;
        private String valuationCcy;

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "DATA_DATE=%s,RULE_ID=%s,GROUP_TYPE=%s,GROUP_VALUE=%s",
                    dataDateText, ruleId, groupType, groupValue);
        }
    }

    private static class VarRow {
        private String dataDateText;
        private LocalDate dataDate;
        private String groupType;
        private String groupValue;
        private BigDecimal varValue;

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "DATA_DATE=%s,GROUP_TYPE=%s,GROUP_VALUE=%s",
                    dataDateText, groupType, groupValue);
        }
    }
}
