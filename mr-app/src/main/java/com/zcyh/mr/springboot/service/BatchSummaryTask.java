package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批次汇总任务。
 */
@Component
public class BatchSummaryTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchSummaryTask.class);

    private final FrtbSbaSummaryService frtbSbaSummaryService;
    private final FrtbDrcSummaryService frtbDrcSummaryService;
    private final VarSummaryService varSummaryService;

    public BatchSummaryTask(FrtbSbaSummaryService frtbSbaSummaryService,
                            FrtbDrcSummaryService frtbDrcSummaryService,
                            VarSummaryService varSummaryService) {
        this.frtbSbaSummaryService = frtbSbaSummaryService;
        this.frtbDrcSummaryService = frtbDrcSummaryService;
        this.varSummaryService = varSummaryService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isPersistResult()) {
            log.info("批次结果未落库，跳过汇总任务，batchId={}", context.getBatchId());
            return;
        }
        if (!context.isFrtbDisabled()) {
            runFrtbSbaSummary(context);
            runFrtbDrcSummary(context);
        } else {
            log.info("批次已关闭 FRTB 明细计量，跳过 FRTB SBA/DRC 汇总，batchId={}", context.getBatchId());
        }
        if (hasVarScenario(context)) {
            runVarSummary(context);
        } else {
            log.info("批次未生成 VaR 情景结果，跳过 VaR/ES 汇总，batchId={}", context.getBatchId());
        }
        RequestContextHolder.setEngineCode("MR_CALC");
    }

    private void runFrtbSbaSummary(BatchRunWorkflowContext context) {
        List<String> ruleIds = parseRuleIdList(context.getFrtbSbaRuleIdList(), "frtb_sba_rule_id_list");
        if (ruleIds.isEmpty()) {
            log.info("未指定 FRTB SBA 汇总规则，跳过 FRTB SBA 汇总，batchId={}", context.getBatchId());
            return;
        }
        RequestContextHolder.setEngineCode("frtb_sba");
        JSONObject request = buildBaseRequest(context);
        request.put("rule_list", buildRuleIdItems(ruleIds));
        frtbSbaSummaryService.summarize(request);
        log.info("FRTB SBA 汇总完成，batchId={}", context.getBatchId());
    }

    private void runFrtbDrcSummary(BatchRunWorkflowContext context) {
        List<String> ruleIds = parseRuleIdList(context.getDrcRuleIdList(), "drc_rule_id_list");
        if (ruleIds.isEmpty()) {
            log.info("未指定 FRTB DRC 汇总规则，跳过 FRTB DRC 汇总，batchId={}", context.getBatchId());
            return;
        }
        RequestContextHolder.setEngineCode("frtb_drc");
        JSONObject request = buildBaseRequest(context);
        request.put("rule_list", buildRuleIdItems(ruleIds));
        frtbDrcSummaryService.summarize(request);
        log.info("FRTB DRC 汇总完成，batchId={}", context.getBatchId());
    }

    private void runVarSummary(BatchRunWorkflowContext context) {
        List<String> ruleIds = parseRuleIdList(context.getVarRuleIdList(), "var_rule_id_list");
        if (ruleIds.isEmpty()) {
            log.info("未指定 VaR/ES 汇总规则，跳过 VaR/ES 汇总，batchId={}", context.getBatchId());
            return;
        }
        RequestContextHolder.setEngineCode("var");
        JSONObject request = buildBaseRequest(context);
        request.put("rules", buildRuleIdItems(ruleIds));
        varSummaryService.summarize(request);
        log.info("VaR/ES 汇总完成，batchId={}", context.getBatchId());
    }

    private static JSONObject buildBaseRequest(BatchRunWorkflowContext context) {
        JSONObject request = new JSONObject();
        request.put("batch_id", context.getBatchId());
        request.put("data_date", context.getDataDate());
        request.put("persist_result", true);
        return request;
    }

    private static boolean hasVarScenario(BatchRunWorkflowContext context) {
        String scenarioIdList = context.getVarScenarioIdList();
        return scenarioIdList != null && !scenarioIdList.trim().isEmpty();
    }

    private List<String> parseRuleIdList(String raw, String fieldName) {
        List<String> output = new ArrayList<String>();
        String safe = trimToNull(raw);
        if (safe == null) {
            return output;
        }
        String[] parts = safe.split(",");
        for (int i = 0; i < parts.length; i++) {
            String ruleId = trimToNull(parts[i]);
            if (ruleId == null) {
                log.info("汇总规则列表存在空项，已忽略: field={}, index={}", fieldName, i);
                continue;
            }
            output.add(ruleId);
        }
        return output;
    }

    private static JSONArray buildRuleIdItems(List<String> ruleIds) {
        JSONArray rules = new JSONArray();
        for (String ruleId : ruleIds) {
            JSONObject item = new JSONObject();
            item.put("rule_id", ruleId);
            rules.add(item);
        }
        return rules;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
