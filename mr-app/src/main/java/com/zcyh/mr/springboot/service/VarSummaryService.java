package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * VaR 汇总服务。
 * 从批次场景结果生成 VaR 汇总，并按需要写入结果表。
 */
@Service
public class VarSummaryService {
    private static final Logger log = LoggerFactory.getLogger(VarSummaryService.class);
    private static final String DEFAULT_VAR_QUANTILES = "0.95,0.99";
    private static final String CALC_TYPE_VAR = "VAR";

    private final VarDbRunnerService varDbRunnerService;
    private final VarResultPersistService varResultPersistService;
    private final CalcRuleMetaPersistService calcRuleMetaPersistService;

    public VarSummaryService(VarDbRunnerService varDbRunnerService,
                             VarResultPersistService varResultPersistService,
                             CalcRuleMetaPersistService calcRuleMetaPersistService) {
        this.varDbRunnerService = varDbRunnerService;
        this.varResultPersistService = varResultPersistService;
        this.calcRuleMetaPersistService = calcRuleMetaPersistService;
    }

    public JSONObject summarize(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = readRequiredString(request, "batch_id");
        String dataDate = readRequiredString(request, "data_date");
        boolean persistResult = readBoolean(request, true, "persist_result");

        JSONObject runnerRequest = copyRequest(request);
        if (readString(runnerRequest, "source_type") == null) {
            runnerRequest.put("source_type", "db_inline");
        }
        if (runnerRequest.get("quantiles") == null) {
            runnerRequest.put("quantiles", DEFAULT_VAR_QUANTILES);
        }

        String raw = varDbRunnerService.calculateByInline(
                runnerRequest.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        JSONObject summary = JSON.parseObject(raw);
        if (persistResult) {
            varResultPersistService.persist(batchId, dataDate, summary);
            persistRuleMeta(batchId, dataDate, runnerRequest);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("summary", summary);
        return response;
    }

    /**
     * 将 VaR 计算请求中每条规则的完整 JSON 写入规则元数据表。
     */
    private void persistRuleMeta(String batchId, String dataDate, JSONObject runnerRequest) {
        try {
            JSONArray rules = runnerRequest.getJSONArray("rules");
            if (rules == null || rules.isEmpty()) {
                return;
            }
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_VAR);
            for (int i = 0; i < rules.size(); i++) {
                JSONObject ruleJson = rules.getJSONObject(i);
                if (ruleJson == null) {
                    continue;
                }
                String ruleId = readString(ruleJson, "rule_id");
                if (ruleId == null) {
                    ruleId = "VAR_RULE_" + (i + 1);
                }
                String ruleJsonStr = ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_VAR, ruleId, ruleJsonStr);
            }
        } catch (Exception e) {
            log.warn("VaR 规则元数据落库失败（不影响主流程）: {}", e.getMessage(), e);
        }
    }

    private static JSONObject copyRequest(JSONObject request) {
        JSONObject copy = JSON.parseObject(request.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        copy.remove("persist_result");
        return copy;
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
}
