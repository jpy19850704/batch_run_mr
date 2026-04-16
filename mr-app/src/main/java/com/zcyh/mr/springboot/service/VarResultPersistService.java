package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * VaR 汇总结果落库服务。
 */
@Service
public class VarResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(VarResultPersistService.class);
    private static final int DEFAULT_BATCH_SIZE = 500;

    private static final String INSERT_SQL =
            "INSERT INTO TB_OUT_VAR_RESULT ("
                    + "BATCH_ID, DATA_DATE, QUANTILE, "
                    + "RULE_ID, RULE_NAME, MODE, SCENARIO_ID, "
                    + "GROUP_TYPE, GROUP_VALUE, RISK_CLASS, "
                    + "VAR, ES, SELECTED_METHOD, CREATED_AT"
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbcTemplate;

    public VarResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 删除指定批次与估值日的 VaR 汇总历史结果。
     */
    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM TB_OUT_VAR_RESULT WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, dataDate);
        if (deleted > 0) {
            log.info("清理 VaR 汇总历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    /**
     * 按 quantile -> rule -> dimension -> risk_class 展平写入 TB_OUT_VAR_RESULT。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(String batchId, String dataDate, JSONObject varResult) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        if (varResult == null) {
            throw new IllegalArgumentException("varResult 不能为空");
        }

        deleteByBatchAndDataDate(safeBatchId, safeDataDate);

        List<ResultRow> rows = new ArrayList<ResultRow>();
        JSONObject summaryFile = varResult.getJSONObject("summary_file");
        if (summaryFile == null) {
            throw new IllegalArgumentException("varResult.summary_file 不能为空");
        }
        JSONArray quantileGroups = summaryFile.getJSONArray("quantile_groups");
        if (quantileGroups != null) {
            for (int i = 0; i < quantileGroups.size(); i++) {
                JSONObject quantileGroup = quantileGroups.getJSONObject(i);
                if (quantileGroup == null) {
                    continue;
                }
                String quantile = trimToNull(quantileGroup.getString("quantile"));
                JSONArray ruleResults = quantileGroup.getJSONArray("rule_results");
                if (ruleResults == null || ruleResults.isEmpty()) {
                    continue;
                }

                for (int j = 0; j < ruleResults.size(); j++) {
                    JSONObject ruleResult = ruleResults.getJSONObject(j);
                    if (ruleResult == null) {
                        continue;
                    }
                    String ruleId = trimToNull(ruleResult.getString("rule_id"));
                    String ruleName = trimToNull(ruleResult.getString("rule_name"));
                    String mode = trimToNull(ruleResult.getString("mode"));
                    String scenarioId = trimToNull(ruleResult.getString("scenario_id"));
                    String selectedMethod = trimToNull(ruleResult.getString("selected_method"));

                    JSONArray dimensionResults = ruleResult.getJSONArray("dimension_results");
                    if (dimensionResults == null || dimensionResults.isEmpty()) {
                        continue;
                    }
                    for (int k = 0; k < dimensionResults.size(); k++) {
                        JSONObject dimensionResult = dimensionResults.getJSONObject(k);
                        if (dimensionResult == null) {
                            continue;
                        }
                        String groupType = trimToNull(dimensionResult.getString("group_type"));
                        String groupValue = trimToNull(dimensionResult.getString("group_value"));

                        JSONArray riskClassResults = dimensionResult.getJSONArray("risk_class_results");
                        if (riskClassResults == null || riskClassResults.isEmpty()) {
                            continue;
                        }
                        for (int m = 0; m < riskClassResults.size(); m++) {
                            JSONObject riskResult = riskClassResults.getJSONObject(m);
                            if (riskResult == null) {
                                continue;
                            }
                            String riskClass = trimToNull(riskResult.getString("risk_class"));
                            BigDecimal varValue = toBigDecimal(riskResult.get("var"));
                            BigDecimal esValue = toBigDecimal(riskResult.get("es"));

                            rows.add(ResultRow.of(
                                    safeBatchId,
                                    safeDataDate,
                                    quantile,
                                    ruleId,
                                    ruleName,
                                    mode,
                                    scenarioId,
                                    groupType,
                                    groupValue,
                                    riskClass,
                                    varValue,
                                    esValue,
                                    selectedMethod
                            ));
                        }
                    }
                }
            }
        }

        if (rows.isEmpty()) {
            log.warn("VaR 汇总结果为空，跳过落库: batchId={}, dataDate={}", safeBatchId, safeDataDate);
            return;
        }

        String now = ResultPersistTime.nowText();
        List<Object[]> batchArgs = new ArrayList<Object[]>();
        for (ResultRow row : rows) {
            batchArgs.add(new Object[]{
                    row.batchId,
                    row.dataDate,
                    row.quantile,
                    row.ruleId,
                    row.ruleName,
                    row.mode,
                    row.scenarioId,
                    row.groupType,
                    row.groupValue,
                    row.riskClass,
                    row.varValue,
                    row.esValue,
                    row.selectedMethod,
                    now
            });
            if (batchArgs.size() >= DEFAULT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
                batchArgs.clear();
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
        }
        log.info("VaR 汇总结果落库完成: batchId={}, dataDate={}, rows={}", safeBatchId, safeDataDate, rows.size());
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * VaR 汇总结果单行结构。
     */
    private static class ResultRow {
        private String batchId;
        private String dataDate;
        private String quantile;
        private String ruleId;
        private String ruleName;
        private String mode;
        private String scenarioId;
        private String groupType;
        private String groupValue;
        private String riskClass;
        private BigDecimal varValue;
        private BigDecimal esValue;
        private String selectedMethod;

        static ResultRow of(String batchId,
                            String dataDate,
                            String quantile,
                            String ruleId,
                            String ruleName,
                            String mode,
                            String scenarioId,
                            String groupType,
                            String groupValue,
                            String riskClass,
                            BigDecimal varValue,
                            BigDecimal esValue,
                            String selectedMethod) {
            ResultRow row = new ResultRow();
            row.batchId = batchId;
            row.dataDate = dataDate;
            row.quantile = quantile;
            row.ruleId = ruleId;
            row.ruleName = ruleName;
            row.mode = mode;
            row.scenarioId = scenarioId;
            row.groupType = groupType;
            row.groupValue = groupValue;
            row.riskClass = riskClass;
            row.varValue = varValue;
            row.esValue = esValue;
            row.selectedMethod = selectedMethod;
            return row;
        }
    }
}
