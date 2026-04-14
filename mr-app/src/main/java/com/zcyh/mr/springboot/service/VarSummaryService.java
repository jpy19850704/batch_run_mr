package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.stereotype.Service;

/**
 * VaR 汇总服务。
 * 从批次场景结果生成 VaR 汇总，并按需要写入结果表。
 */
@Service
public class VarSummaryService {
    private static final String DEFAULT_VAR_RULE_ID = "BATCH_VAR_DEFAULT";
    private static final String DEFAULT_VAR_QUANTILES = "0.95,0.99";

    private final VarDbRunnerService varDbRunnerService;
    private final VarResultPersistService varResultPersistService;

    public VarSummaryService(VarDbRunnerService varDbRunnerService,
                             VarResultPersistService varResultPersistService) {
        this.varDbRunnerService = varDbRunnerService;
        this.varResultPersistService = varResultPersistService;
    }

    public JSONObject summarize(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = readRequiredString(request, "batch_id", "batchId");
        String dataDate = readRequiredString(request, "data_date", "dataDate");
        boolean persistResult = readBoolean(request, true, "persist_result", "persistResult");

        JSONObject runnerRequest = copyRequest(request);
        if (readString(runnerRequest, "source_type", "sourceType") == null) {
            runnerRequest.put("source_type", "db_inline");
        }
        if (runnerRequest.get("quantiles") == null) {
            runnerRequest.put("quantiles", DEFAULT_VAR_QUANTILES);
        }
        if (runnerRequest.getJSONArray("rules") == null || runnerRequest.getJSONArray("rules").isEmpty()) {
            runnerRequest.put("rules", buildDefaultRules());
        }

        String raw = varDbRunnerService.calculateByInline(
                runnerRequest.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        JSONObject summary = JSON.parseObject(raw);
        if (persistResult) {
            varResultPersistService.persist(batchId, dataDate, summary);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("summary", summary);
        return response;
    }

    private static JSONObject copyRequest(JSONObject request) {
        JSONObject copy = JSON.parseObject(request.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        copy.remove("persist_result");
        copy.remove("persistResult");
        return copy;
    }

    private static JSONArray buildDefaultRules() {
        JSONObject rule = new JSONObject();
        rule.put("rule_id", DEFAULT_VAR_RULE_ID);
        rule.put("rule_name", "默认 VaR 批次汇总规则");
        rule.put("rule_type", "VAR");
        rule.put("build_order", JSON.parseArray("[\"TRADER\",\"DESK\",\"PORTFOLIO\",\"TOTAL\"]"));

        JSONObject dimensions = new JSONObject();
        dimensions.put("TRADER", "TRADER");
        dimensions.put("DESK", "DESK");
        dimensions.put("PORTFOLIO", "PORTFOLIO");
        rule.put("dimensions", dimensions);
        rule.put("group_by_fields", JSON.parseArray("[\"TRADER\",\"DESK\",\"PORTFOLIO\"]"));
        rule.put("sum_fields", JSON.parseArray("[\"ALL_PNL\"]"));
        rule.put("filters", new JSONArray());

        JSONObject calc = new JSONObject();
        calc.put("risk_class", "ALL");
        calc.put("var_pick", "average");
        rule.put("calc", calc);

        JSONArray rules = new JSONArray();
        rules.add(rule);
        return rules;
    }

    private static boolean readBoolean(JSONObject request, boolean defaultValue, String... keys) {
        for (String key : keys) {
            if (key == null || !request.containsKey(key)) {
                continue;
            }
            Boolean value = request.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static String readRequiredString(JSONObject request, String... keys) {
        String value = readString(request, keys);
        if (value == null) {
            throw new IllegalArgumentException("参数缺失: " + keys[0]);
        }
        return value;
    }

    private static String readString(JSONObject request, String... keys) {
        if (request == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = trimToNull(request.getString(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
