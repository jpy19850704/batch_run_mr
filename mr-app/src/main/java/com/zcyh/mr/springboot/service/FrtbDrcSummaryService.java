package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Service;

/**
 * FRTB DRC 汇总服务。
 * 从批次结果明细生成 DRC 汇总，并按需要写入结果表。
 */
@Service
public class FrtbDrcSummaryService {
    private static final String CALC_TYPE_DRC = "FRTB_DRC";

    private final FrtbDrcDbRunnerService frtbDrcDbRunnerService;
    private final FrtbDrcResultPersistService frtbDrcResultPersistService;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;

    public FrtbDrcSummaryService(FrtbDrcDbRunnerService frtbDrcDbRunnerService,
                                 FrtbDrcResultPersistService frtbDrcResultPersistService,
                                 CalcRuleMetaPersistService calcRuleMetaPersistService) {
        this.frtbDrcDbRunnerService = frtbDrcDbRunnerService;
        this.frtbDrcResultPersistService = frtbDrcResultPersistService;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
    }

    public JSONObject summarize(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = readRequiredString(request, "batch_id");
        String dataDate = readRequiredString(request, "data_date");
        String requestId = readString(request, "request_id");
        String jobId = readString(request, "job_id");
        boolean persistResult = readBoolean(request, true, "persist_result");
        JSONArray ruleList = resolveRuleList(request);
        if (persistResult) {
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_DRC);
        }

        JSONArray results = new JSONArray();
        for (int i = 0; i < ruleList.size(); i++) {
            JSONObject ruleItem = ruleList.getJSONObject(i);
            if (ruleItem == null) {
                throw new IllegalArgumentException("rule_list[" + i + "] 不能为空对象");
            }
            RuleExecution execution = resolveRuleExecution(ruleItem);
            JSONObject summary = executeOne(batchId, dataDate, execution);
            if (persistResult) {
                frtbDrcResultPersistService.persist(requestId, jobId, batchId, dataDate, execution.ruleId, summary);
                persistRuleMeta(batchId, dataDate, execution);
            }
            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", execution.ruleId);
            resultItem.put("source_type", execution.sourceType);
            resultItem.put("summary", summary);
            results.add(resultItem);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    private void persistRuleMeta(String batchId, String dataDate, RuleExecution execution) {
        String ruleJsonStr;
        if (execution.ruleJson != null) {
            ruleJsonStr = execution.ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        } else {
            JSONObject ruleSnapshot = frtbDrcDbRunnerService.loadRuleSnapshot(execution.ruleId);
            ruleJsonStr = ruleSnapshot.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        }
        calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_DRC, execution.ruleId, ruleJsonStr);
    }

    private JSONObject executeOne(String batchId, String dataDate, RuleExecution execution) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);
        String raw;
        if ("db".equals(execution.sourceType)) {
            payload.put("rule_id", execution.ruleId);
            raw = frtbDrcDbRunnerService.calculateByRule(
                    payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        } else {
            payload.put("rule", execution.ruleJson);
            raw = frtbDrcDbRunnerService.calculateByInlineRule(
                    payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        }
        return JSON.parseObject(raw);
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
        throw new IllegalArgumentException("DRC 汇总必须显式提供 rule_id、rule 或 rule_list");
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
            throw new IllegalArgumentException("DRC inline rule 必须显式提供 rule_id");
        }
        rule.put("rule_id", ruleId);
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
