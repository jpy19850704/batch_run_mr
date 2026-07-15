package com.zcyh.mr.springboot.out.db;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.SummaryCleanupMode;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * FRTB RRAO 汇总结果持久化服务。
 */
@Service
public class FrtbRraoResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(FrtbRraoResultPersistService.class);
    private static final String CALC_TYPE_RRAO = "RRAO";
    private static final String TARGET_TABLE = "TB_OUT_TRADE_RRAO_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,RRAO_TYPE,TRADE_COUNT,RRAO_NOTIONAL,RRAO_CAPITAL,CREATED_AT";
    private static final int DEFAULT_BATCH_SIZE = 5000;

    private final JdbcTemplate resultDbJdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;

    public FrtbRraoResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate resultDbJdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService,
            CalcRuleMetaPersistService calcRuleMetaPersistService) {
        this.resultDbJdbcTemplate = resultDbJdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
    }

    public void replaceResults(
            String batchId,
            String dataDate,
            SummaryCleanupMode cleanupMode,
            List<String> ruleIds,
            List<RuleResult> ruleResults) {
        deleteExisting(batchId, dataDate, cleanupMode, ruleIds);
        for (RuleResult ruleResult : ruleResults) {
            persistResult(batchId, dataDate, ruleResult.ruleId, ruleResult.summary);
            persistRuleMeta(batchId, dataDate, ruleResult.ruleId, ruleResult.ruleJson);
        }
    }

    private void deleteExisting(String batchId,
                                String dataDate,
                                SummaryCleanupMode cleanupMode,
                                List<String> ruleIds) {
        int deleted;
        if (cleanupMode == SummaryCleanupMode.FULL) {
            deleted = resultDbJdbcTemplate.update(
                    "DELETE FROM " + TARGET_TABLE + " WHERE BATCH_ID=? AND DATA_DATE=?",
                    batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(
                    batchId, dataDate, CALC_TYPE_RRAO);
        } else if (cleanupMode == SummaryCleanupMode.RULE) {
            deleted = RuleScopedDeleteSupport.deleteByRuleIds(
                    resultDbJdbcTemplate, TARGET_TABLE, batchId, dataDate, ruleIds);
            calcRuleMetaPersistService.deleteByBatchCalcTypeAndRuleIds(
                    batchId, dataDate, CALC_TYPE_RRAO, ruleIds);
        } else {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        if (deleted > 0) {
            log.info("清理 RRAO 汇总历史结果: batchId={}, dataDate={}, cleanupMode={}, ruleIds={}, deleted={}",
                    batchId, dataDate, cleanupMode, ruleIds, deleted);
        }
    }

    private void persistResult(String batchId, String dataDate, String ruleId, JSONArray summary) {
        if (summary == null || summary.isEmpty()) {
            log.info("RRAO 汇总结果为空: batchId={}, dataDate={}, ruleId={}", batchId, dataDate, ruleId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "frtb_rrao_" + batchId + "_" + dataDate + "_" + ruleId,
                DEFAULT_BATCH_SIZE);
        for (int i = 0; i < summary.size(); i++) {
            JSONObject row = summary.getJSONObject(i);
            buffer.appendRow(
                    row.getString("BATCH_ID"),
                    row.getString("DATA_DATE"),
                    row.getString("RULE_ID"),
                    row.getString("GROUP_TYPE"),
                    row.getString("GROUP_VALUE"),
                    row.getString("RRAO_TYPE"),
                    row.getLong("TRADE_COUNT"),
                    DorisCsvStreamLoadBuffer.decimalText(row.getBigDecimal("RRAO_NOTIONAL")),
                    DorisCsvStreamLoadBuffer.decimalText(row.getBigDecimal("RRAO_CAPITAL")),
                    now);
        }
        buffer.flush();
        log.info("RRAO 汇总结果落库完成: batchId={}, dataDate={}, ruleId={}, rows={}",
                batchId, dataDate, ruleId, summary.size());
    }

    private void persistRuleMeta(
            String batchId,
            String dataDate,
            String ruleId,
            JSONObject ruleJson) {
        String ruleJsonText = ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        calcRuleMetaPersistService.persist(
                batchId, dataDate, CALC_TYPE_RRAO, ruleId, ruleJsonText);
    }

    public static class RuleResult {
        private final String ruleId;
        private final JSONArray summary;
        private final JSONObject ruleJson;

        public RuleResult(String ruleId, JSONArray summary, JSONObject ruleJson) {
            this.ruleId = ruleId;
            this.summary = summary;
            this.ruleJson = ruleJson;
        }
    }
}
