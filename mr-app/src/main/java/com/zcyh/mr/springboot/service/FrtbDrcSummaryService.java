package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.out.db.FrtbDrcResultPersistService;

import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.FrtbDrcSummaryRequest;
import com.zcyh.mr.springboot.model.SummaryCleanupMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public JSONObject summarize(FrtbDrcSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        boolean persistResult = request.isPersistResult();

        JSONArray results = new JSONArray();
        List<RuleOutput> ruleOutputs = new ArrayList<RuleOutput>();
        for (String ruleId : request.getRuleIds()) {
            com.zcyh.mr.springboot.model.AggregationRule ruleDefinition =
                    frtbDrcDbRunnerService.loadRuleDefinition(ruleId);
            JSONObject summary = frtbDrcDbRunnerService.calculate(
                    batchId,
                    dataDate,
                    Collections.singletonList(ruleDefinition));
            JSONObject ruleSnapshot = frtbDrcDbRunnerService.loadRuleSnapshot(ruleId);
            ruleOutputs.add(new RuleOutput(ruleId, summary, ruleSnapshot));
            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", ruleId);
            resultItem.put("source_type", "db");
            resultItem.put("summary", summary);
            results.add(resultItem);
        }
        if (persistResult) {
            cleanup(batchId, dataDate, request);
            for (RuleOutput output : ruleOutputs) {
                frtbDrcResultPersistService.persist(request.getRequestId(), request.getJobId(),
                        batchId, dataDate, output.ruleId, output.summary);
                persistRuleMeta(batchId, dataDate, output.ruleId, output.ruleSnapshot);
            }
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    private void cleanup(String batchId, String dataDate, FrtbDrcSummaryRequest request) {
        if (request.getCleanupMode() == SummaryCleanupMode.FULL) {
            frtbDrcResultPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_DRC);
            return;
        }
        if (request.getCleanupMode() != SummaryCleanupMode.RULE) {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        frtbDrcResultPersistService.deleteByBatchDataDateAndRuleIds(
                batchId, dataDate, request.getRuleIds());
        calcRuleMetaPersistService.deleteByBatchCalcTypeAndRuleIds(
                batchId, dataDate, CALC_TYPE_DRC, request.getRuleIds());
    }

    private void persistRuleMeta(String batchId,
                                 String dataDate,
                                 String ruleId,
                                 JSONObject ruleSnapshot) {
        String ruleJsonStr = ruleSnapshot.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_DRC, ruleId, ruleJsonStr);
    }

    private static class RuleOutput {
        private final String ruleId;
        private final JSONObject summary;
        private final JSONObject ruleSnapshot;

        private RuleOutput(String ruleId, JSONObject summary, JSONObject ruleSnapshot) {
            this.ruleId = ruleId;
            this.summary = summary;
            this.ruleSnapshot = ruleSnapshot;
        }
    }

}
