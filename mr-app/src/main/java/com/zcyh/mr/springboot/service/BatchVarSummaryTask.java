package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * VaR 汇总任务。
 */
@Component
public class BatchVarSummaryTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchVarSummaryTask.class);
    private static final String DEFAULT_VAR_RULE_ID = "BATCH_VAR_DEFAULT";
    private static final String DEFAULT_VAR_QUANTILES = "0.95,0.99";

    private final VarDbRunnerService varDbRunnerService;
    private final VarResultPersistService varResultPersistService;

    public BatchVarSummaryTask(
            VarDbRunnerService varDbRunnerService,
            VarResultPersistService varResultPersistService) {
        this.varDbRunnerService = varDbRunnerService;
        this.varResultPersistService = varResultPersistService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (context.isWhatifMode()) {
            return;
        }
        context.setVarSummary(runVarSummary(context.getBatchId(), context.getDataDate()));
    }

    private Object runVarSummary(String batchId, String dataDate) {
        JSONObject payload = new JSONObject();
        payload.put("source_type", "db_inline");
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);
        payload.put("quantiles", DEFAULT_VAR_QUANTILES);

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
        payload.put("rules", rules);

        String raw = varDbRunnerService.calculateByInline(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        Object parsed = JSON.parse(raw);

        try {
            JSONObject resultJson = JSON.parseObject(raw);
            varResultPersistService.persist(batchId, dataDate, resultJson);
        } catch (Exception ex) {
            log.warn("VaR 结果落库异常，不影响批量返回: batchId={}, error={}", batchId, ex.getMessage());
        }

        return parsed;
    }
}
