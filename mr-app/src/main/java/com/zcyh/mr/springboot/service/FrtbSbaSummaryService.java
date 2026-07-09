package com.zcyh.mr.springboot.service;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;
import static com.zcyh.mr.springboot.support.RequestParseSupport.readInteger;
import static com.zcyh.mr.springboot.support.RequestParseSupport.readRequiredString;
import static com.zcyh.mr.springboot.support.RequestParseSupport.readString;
import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult;
import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;
import com.zcyh.mr.springboot.out.db.FrtbSbaResultPersistService;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FRTB SBA 汇总服务。
 * 从批次敏感性明细生成 SBA 汇总结果，并按需要执行结果落库。
 */
@Service
public class FrtbSbaSummaryService {
    private static final Logger log = LoggerFactory.getLogger(FrtbSbaSummaryService.class);
    private static final String CALC_TYPE_FRTB_SBA = "FRTB_SBA";
    private static final int DECOMP_DETAIL_BATCH_SIZE = 10000;
    private static final String DECOMP_DETAIL_TABLE = "TB_OUT_FRTB_SBA_DECOMP_DETAIL";
    private static final String DECOMP_DETAIL_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,"
                    + "RISK_FACTOR_CLASS,RISK_FACTOR_BUCKET,RISK_FACTOR_ID,"
                    + "RISK_FACTOR_VERTEX_1,RISK_FACTOR_VERTEX_2,RISK_FACTOR_TYPE,"
                    + "SENSITIVITY_TYPE,UNIT_CONTRIBUTION,CREATED_AT,UPDATED_AT";

    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final FrtbSbaResultPersistService frtbSbaResultPersistService;
    private final FrtbAggregator frtbAggregator;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;
    private final JdbcTemplate engineResultDbJdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public FrtbSbaSummaryService(FrtbSbaDbRunnerService frtbSbaDbRunnerService,
                                 FrtbSbaResultPersistService frtbSbaResultPersistService,
                                 FrtbAggregator frtbAggregator,
                                 CalcRuleMetaPersistService calcRuleMetaPersistService,
                                 @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultDbJdbcTemplate,
                                 DorisStreamLoadService dorisStreamLoadService) {
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.frtbSbaResultPersistService = frtbSbaResultPersistService;
        this.frtbAggregator = frtbAggregator;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
        this.engineResultDbJdbcTemplate = engineResultDbJdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @SuppressWarnings("unchecked")
    public JSONObject summarize(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = readRequiredString(request, "batch_id");
        String dataDate = readRequiredString(request, "data_date");
        boolean needDecompose = readBoolean(request, true, "need_decompose");
        int threadCount = readInteger(request, 0, "thread_count");
        boolean persistResult = readBoolean(request, true, "persist_result");
        JSONArray ruleList = resolveRuleList(request);

        if (persistResult) {
            frtbSbaResultPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            deleteDecompDetailByBatchAndDataDate(batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_FRTB_SBA);
        }

        JSONArray results = new JSONArray();
        for (int i = 0; i < ruleList.size(); i++) {
            JSONObject ruleItem = ruleList.getJSONObject(i);
            if (ruleItem == null) {
                throw new IllegalArgumentException("rule_list[" + i + "] 不能为空对象");
            }
            RuleExecution execution = resolveRuleExecution(ruleItem);
            String raw = executeOne(batchId, dataDate, needDecompose, threadCount, execution);
            Object parsed = JSON.parse(raw);

            if (persistResult) {
                Map<String, Object> batchResult = JSON.parseObject(raw, Map.class);
                persistRuleResult(batchId, dataDate, execution.ruleId, batchResult);
                persistRuleMeta(batchId, dataDate, execution);
            }

            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", execution.ruleId);
            resultItem.put("source_type", execution.sourceType);
            resultItem.put("summary", parsed);
            results.add(resultItem);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    private String executeOne(String batchId,
                              String dataDate,
                              boolean needDecompose,
                              int threadCount,
                              RuleExecution execution) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);
        payload.put("need_decompose", needDecompose);
        if (threadCount > 0) {
            payload.put("thread_count", threadCount);
        }
        if ("db".equals(execution.sourceType)) {
            execution.ruleJson = frtbSbaDbRunnerService.loadRuleSnapshot(execution.ruleId);
            payload.put("rule", execution.ruleJson);
            return frtbSbaDbRunnerService.calculateByInlineRule(
                    payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        }
        payload.put("rule", execution.ruleJson);
        return frtbSbaDbRunnerService.calculateByInlineRule(
                payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    @SuppressWarnings("unchecked")
    private void persistRuleResult(String batchId,
                                   String dataDate,
                                   String ruleId,
                                   Map<String, Object> batchResult) {
        if (batchResult == null || batchResult.isEmpty()) {
            return;
        }
        Map<String, Object> rawDetails = requireRawDetails(batchResult.get("__raw_details"));
        List<FRTBPosResult> decompDetails = new ArrayList<FRTBPosResult>();
        for (Map.Entry<String, Object> entry : batchResult.entrySet()) {
            String taskKey = entry.getKey();
            if ("__raw_details".equals(taskKey) || "__raw_detail_schema".equals(taskKey)) {
                continue;
            }
            Map<String, Object> rawDetail = requireRawDetail(rawDetails, taskKey);
            String detailRuleId = requireRawDetailText(rawDetail, "ruleId", taskKey);
            String groupType = requireRawDetailText(rawDetail, "groupType", taskKey);
            String groupValue = requireRawDetailText(rawDetail, "groupValue", taskKey);
            Object calcResult = entry.getValue();
            if (!(calcResult instanceof Map)) {
                throw new IllegalArgumentException("FRTB SBA 任务结果格式异常: taskKey=" + taskKey);
            }
            Map<String, List<?>> pojoResult = frtbAggregator.buildResults(
                    (Map<String, Object>) calcResult, detailRuleId, groupType, groupValue);
            List<?> classResults = pojoResult.get("classResults");
            if (classResults != null && !classResults.isEmpty()) {
                frtbSbaResultPersistService.persist(
                        (List<FRTBClassResult>) classResults, batchId, dataDate, ruleId);
            }
            List<?> posResults = pojoResult.get("posResults");
            if (posResults != null && !posResults.isEmpty()) {
                decompDetails.addAll((List<FRTBPosResult>) posResults);
            }
        }
        if (!decompDetails.isEmpty()) {
            persistDecompDetails(decompDetails, batchId, dataDate, ruleId);
        }
    }

    private void persistDecompDetails(List<FRTBPosResult> posResults, String batchId, String dataDate, String ruleId) {
        if (posResults == null || posResults.isEmpty()) {
            log.warn("FRTB SBA Decomp 明细为空，跳过落库: batchId={}, ruleId={}", batchId, ruleId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                DECOMP_DETAIL_TABLE,
                DECOMP_DETAIL_COLUMNS,
                "frtb_sba_decomp_" + batchId,
                DECOMP_DETAIL_BATCH_SIZE);

        int rows = 0;
        for (FRTBPosResult pr : posResults) {
            if (pr == null) {
                continue;
            }
            if ("ALL".equalsIgnoreCase(pr.getRiskFactorClass())) {
                continue;
            }
            buffer.appendRow(
                    batchId, dataDate, ruleId,
                    pr.getGroupType(), pr.getGroupValue(),
                    pr.getRiskFactorClass(), pr.getRiskFactorBucket(), pr.getRiskFactorId(),
                    pr.getRiskFactorVertex1(), pr.getRiskFactorVertex2(), pr.getRiskFactorType(),
                    pr.getSensitivityType(),
                    DorisCsvStreamLoadBuffer.decimalText(decVal(pr.getUnitContribution())),
                    now, now);
            rows++;
        }
        buffer.flush();
        log.info("FRTB SBA Decomp 明细落库完成: batchId={}, ruleId={}, rows={}", batchId, ruleId, rows);
    }

    private void deleteDecompDetailByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = engineResultDbJdbcTemplate.update(
                "DELETE FROM TB_OUT_FRTB_SBA_DECOMP_DETAIL WHERE BATCH_ID = ? AND DATA_DATE = ?",
                batchId, dataDate);
        if (deleted > 0) {
            log.info("清理 FRTB SBA Decomp 历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    private static BigDecimal decVal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireRawDetails(Object rawDetails) {
        if (!(rawDetails instanceof Map)) {
            throw new IllegalArgumentException("FRTB SBA 结果缺少 __raw_details，无法确定真实维度类型");
        }
        return (Map<String, Object>) rawDetails;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireRawDetail(Map<String, Object> rawDetails, String taskKey) {
        if (rawDetails == null || rawDetails.isEmpty()) {
            throw new IllegalArgumentException("FRTB SBA 结果缺少 __raw_details，无法确定真实维度类型");
        }
        Object detail = rawDetails.get(taskKey);
        if (!(detail instanceof Map)) {
            throw new IllegalArgumentException("FRTB SBA 结果缺少任务维度信息: taskKey=" + taskKey);
        }
        return (Map<String, Object>) detail;
    }

    private static String requireRawDetailText(Map<String, Object> rawDetail, String field, String taskKey) {
        Object value = rawDetail.get(field);
        String text = value == null ? null : trimToNull(String.valueOf(value));
        if (text == null) {
            throw new IllegalArgumentException("FRTB SBA 任务维度字段缺失: taskKey=" + taskKey + ", field=" + field);
        }
        return text;
    }

    /**
     * 将 FRTB SBA 规则的完整 JSON 写入规则元数据表。
     */
    private void persistRuleMeta(String batchId, String dataDate, RuleExecution execution) {
        try {
            String ruleJsonStr;
            if (execution.ruleJson != null) {
                ruleJsonStr = execution.ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
            } else {
                JSONObject ruleSnapshot = frtbSbaDbRunnerService.loadRuleSnapshot(execution.ruleId);
                ruleJsonStr = ruleSnapshot.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
            }
            calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_FRTB_SBA, execution.ruleId, ruleJsonStr);
        } catch (Exception e) {
            log.warn("FRTB SBA 规则元数据落库失败（不影响主流程）: {}", e.getMessage(), e);
        }
    }

    private static JSONArray resolveRuleList(JSONObject request) {
        JSONArray ruleList = request.getJSONArray("rule_list");
        if (ruleList != null && !ruleList.isEmpty()) {
            return ruleList;
        }
        JSONArray single = new JSONArray();
        JSONObject item = new JSONObject();
        String ruleId = readString(request, "rule_id");
        JSONObject rule = request.getJSONObject("rule");
        if (ruleId != null) {
            item.put("rule_id", ruleId);
            single.add(item);
            return single;
        }
        if (rule != null) {
            item.put("rule", rule);
            single.add(item);
            return single;
        }
        throw new IllegalArgumentException("FRTB SBA 汇总必须显式提供 rule_id、rule 或 rule_list");
    }

    private static RuleExecution resolveRuleExecution(JSONObject ruleItem) {
        JSONObject rule = ruleItem.getJSONObject("rule");
        String ruleId = readString(ruleItem, "rule_id");
        if (rule == null) {
            if (ruleId == null) {
                throw new IllegalArgumentException("rule_list 项必须提供 rule_id 或 rule");
            }
            return RuleExecution.db(ruleId);
        }
        if (ruleId == null) {
            ruleId = readString(rule, "rule_id");
        }
        if (ruleId == null) {
            throw new IllegalArgumentException("FRTB SBA inline rule 必须显式提供 rule_id");
        }
        rule.put("rule_id", ruleId);
        return RuleExecution.inline(ruleId, rule);
    }

    /**
     * 单条 SBA 汇总规则执行定义。
     */
    private static class RuleExecution {
        private String ruleId;
        private String sourceType;
        private JSONObject ruleJson;

        static RuleExecution db(String ruleId) {
            RuleExecution execution = new RuleExecution();
            execution.ruleId = ruleId;
            execution.sourceType = "db";
            return execution;
        }

        static RuleExecution inline(String ruleId, JSONObject ruleJson) {
            RuleExecution execution = new RuleExecution();
            execution.ruleId = ruleId;
            execution.sourceType = "db_inline";
            execution.ruleJson = ruleJson;
            return execution;
        }
    }
}
