package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FRTB SBA 汇总服务。
 * 从批次敏感性明细生成 SBA 汇总结果，并按需要执行结果落库。
 */
@Service
public class FrtbSbaSummaryService {
    private static final Logger log = LoggerFactory.getLogger(FrtbSbaSummaryService.class);
    private static final String DEFAULT_RULE_ID = "BATCH_FRTB_DEFAULT";
    private static final String CALC_TYPE_FRTB_SBA = "FRTB_SBA";

    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final FrtbSbaResultPersistService frtbSbaResultPersistService;
    private final FrtbAggregator frtbAggregator;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;

    public FrtbSbaSummaryService(FrtbSbaDbRunnerService frtbSbaDbRunnerService,
                                 FrtbSbaResultPersistService frtbSbaResultPersistService,
                                 FrtbAggregator frtbAggregator,
                                 CalcRuleMetaPersistService calcRuleMetaPersistService) {
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.frtbSbaResultPersistService = frtbSbaResultPersistService;
        this.frtbAggregator = frtbAggregator;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
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
            frtbSbaResultPersistService.deleteByBatch(batchId);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_FRTB_SBA);
        }

        JSONArray results = new JSONArray();
        AtomicInteger inlineCounter = new AtomicInteger(1);
        for (int i = 0; i < ruleList.size(); i++) {
            JSONObject ruleItem = ruleList.getJSONObject(i);
            if (ruleItem == null) {
                throw new IllegalArgumentException("rule_list[" + i + "] 不能为空对象");
            }
            RuleExecution execution = resolveRuleExecution(ruleItem, inlineCounter);
            String raw = executeOne(batchId, dataDate, needDecompose, threadCount, execution);
            Object parsed = JSON.parse(raw);

            if (persistResult) {
                Map<String, Map<String, Object>> batchResult = JSON.parseObject(raw, Map.class);
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
                                   Map<String, Map<String, Object>> batchResult) {
        if (batchResult == null || batchResult.isEmpty()) {
            return;
        }
        Map<String, Object> rawDetails = batchResult.get("__raw_details");
        for (Map.Entry<String, Map<String, Object>> entry : batchResult.entrySet()) {
            String taskKey = entry.getKey();
            if ("__raw_details".equals(taskKey)) {
                continue;
            }
            Map<String, Object> rawDetail = requireRawDetail(rawDetails, taskKey);
            String treeId = requireRawDetailText(rawDetail, "treeId", taskKey);
            String groupType = requireRawDetailText(rawDetail, "groupType", taskKey);
            String groupValue = requireRawDetailText(rawDetail, "groupValue", taskKey);
            Map<String, List<?>> pojoResult = frtbAggregator.buildResults(
                    entry.getValue(), treeId, groupType, groupValue);
            List<?> classResults = pojoResult.get("classResults");
            if (classResults == null || classResults.isEmpty()) {
                continue;
            }
            frtbSbaResultPersistService.persist(
                    (List<FRTBClassResult>) classResults, batchId, dataDate, ruleId);
        }
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
        item.put("rule_id", DEFAULT_RULE_ID);
        single.add(item);
        return single;
    }

    private static RuleExecution resolveRuleExecution(JSONObject ruleItem, AtomicInteger inlineCounter) {
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
            ruleId = "INLINE_FRTB_SBA_" + inlineCounter.getAndIncrement();
        }
        rule.put("ruleId", ruleId);
        String ruleType = readString(rule, "rule_type");
        rule.put("ruleType", ruleType == null ? "FRTB" : ruleType);
        return RuleExecution.inline(ruleId, rule);
    }

    private static boolean readBoolean(JSONObject request, boolean defaultValue, String key) {
        if (key != null && request.containsKey(key)) {
            Boolean value = request.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static int readInteger(JSONObject request, int defaultValue, String key) {
        if (key != null && request.containsKey(key)) {
            Integer value = request.getInteger(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static String readRequiredString(JSONObject request, String key) {
        String value = readString(request, key);
        if (value == null) {
            throw new IllegalArgumentException("参数缺失: " + key);
        }
        return value;
    }

    private static String readString(JSONObject request, String key) {
        if (request == null || key == null) {
            return null;
        }
        return trimToNull(request.getString(key));
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
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
