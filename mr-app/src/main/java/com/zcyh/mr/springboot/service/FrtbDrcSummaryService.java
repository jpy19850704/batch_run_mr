package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.out.db.FrtbDrcResultPersistService;

import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.FrtbDrcSummaryRequest;
import org.springframework.stereotype.Service;

import java.util.Collections;

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
        if (persistResult) {
            frtbDrcResultPersistService.deleteByBatchAndDataDate(batchId, dataDate);
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_DRC);
        }

        JSONArray results = new JSONArray();
        for (String ruleId : request.getRuleIds()) {
            com.zcyh.mr.springboot.model.AggregationRule ruleDefinition =
                    frtbDrcDbRunnerService.loadRuleDefinition(ruleId);
            JSONObject summary = frtbDrcDbRunnerService.calculate(
                    batchId,
                    dataDate,
                    Collections.singletonList(ruleDefinition));
            if (persistResult) {
                frtbDrcResultPersistService.persist(request.getRequestId(), request.getJobId(),
                        batchId, dataDate, ruleId, summary);
                persistRuleMeta(batchId, dataDate, ruleId);
            }
            JSONObject resultItem = new JSONObject();
            resultItem.put("rule_id", ruleId);
            resultItem.put("source_type", "db");
            resultItem.put("summary", summary);
            results.add(resultItem);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("results", results);
        return response;
    }

    private void persistRuleMeta(String batchId, String dataDate, String ruleId) {
        JSONObject ruleSnapshot = frtbDrcDbRunnerService.loadRuleSnapshot(ruleId);
        String ruleJsonStr = ruleSnapshot.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_DRC, ruleId, ruleJsonStr);
    }

}
