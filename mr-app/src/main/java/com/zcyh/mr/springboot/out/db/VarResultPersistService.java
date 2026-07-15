package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toBigDecimal;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.trimToNull;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.SummaryCleanupMode;
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
    private static final int DEFAULT_BATCH_SIZE = 5000;
    private static final String TARGET_TABLE = "TB_OUT_VAR_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,QUANTILE,RULE_ID,MODE,SCENARIO_ID,GROUP_TYPE,GROUP_VALUE,RISK_CLASS,RULE_NAME,VAR,ES,COMPONENT_VAR,MARGINAL_VAR,INCREMENTAL_VAR,SELECTED_METHOD,CREATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public VarResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                   DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
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

    public void deleteByBatchDataDateAndRuleIds(String batchId, String dataDate, List<String> ruleIds) {
        int deleted = RuleScopedDeleteSupport.deleteByRuleIds(
                jdbcTemplate, TARGET_TABLE, batchId, dataDate, ruleIds);
        if (deleted > 0) {
            log.info("按规则清理 VaR 汇总历史结果: batchId={}, dataDate={}, ruleIds={}, deleted={}",
                    batchId, dataDate, ruleIds, deleted);
        }
    }

    /**
     * 按 quantile -> rule -> dimension -> risk_class 展平写入 TB_OUT_VAR_RESULT。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void replace(String batchId,
                        String dataDate,
                        SummaryCleanupMode cleanupMode,
                        List<String> ruleIds,
                        JSONObject varResult) {
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
                    if (ruleId == null) {
                        throw new IllegalArgumentException("VaR 汇总结果缺少 rule_id");
                    }
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
                            BigDecimal componentVar = toBigDecimal(riskResult.get("component_var"));
                            BigDecimal marginalVar = toBigDecimal(riskResult.get("marginal_var"));
                            BigDecimal incrementalVar = toBigDecimal(riskResult.get("incremental_var"));

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
                                    componentVar,
                                    marginalVar,
                                    incrementalVar,
                                    selectedMethod
                            ));
                        }
                    }
                }
            }
        }

        if (cleanupMode == SummaryCleanupMode.FULL) {
            deleteByBatchAndDataDate(safeBatchId, safeDataDate);
        } else if (cleanupMode == SummaryCleanupMode.RULE) {
            deleteByBatchDataDateAndRuleIds(safeBatchId, safeDataDate, ruleIds);
        } else {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }

        if (rows.isEmpty()) {
            log.warn("VaR 汇总结果为空，跳过落库: batchId={}, dataDate={}", safeBatchId, safeDataDate);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "var_result_" + safeBatchId + "_" + safeDataDate,
                DEFAULT_BATCH_SIZE);
        for (ResultRow row : rows) {
            buffer.appendRow(
                    row.batchId,
                    row.dataDate,
                    row.quantile,
                    row.ruleId,
                    row.mode,
                    row.scenarioId,
                    row.groupType,
                    row.groupValue,
                    row.riskClass,
                    row.ruleName,
                    DorisCsvStreamLoadBuffer.decimalText(row.varValue),
                    DorisCsvStreamLoadBuffer.decimalText(row.esValue),
                    DorisCsvStreamLoadBuffer.decimalText(row.componentVar),
                    DorisCsvStreamLoadBuffer.decimalText(row.marginalVar),
                    DorisCsvStreamLoadBuffer.decimalText(row.incrementalVar),
                    row.selectedMethod,
                    now
            );
        }
        buffer.flush();
        log.info("VaR 汇总结果落库完成: batchId={}, dataDate={}, rows={}", safeBatchId, safeDataDate, rows.size());
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
        private BigDecimal componentVar;
        private BigDecimal marginalVar;
        private BigDecimal incrementalVar;
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
                            BigDecimal componentVar,
                            BigDecimal marginalVar,
                            BigDecimal incrementalVar,
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
            row.varValue = varValue == null ? BigDecimal.ZERO : varValue;
            row.esValue = esValue == null ? BigDecimal.ZERO : esValue;
            row.componentVar = componentVar == null ? BigDecimal.ZERO : componentVar;
            row.marginalVar = marginalVar == null ? BigDecimal.ZERO : marginalVar;
            row.incrementalVar = incrementalVar == null ? BigDecimal.ZERO : incrementalVar;
            row.selectedMethod = selectedMethod;
            return row;
        }
    }
}
