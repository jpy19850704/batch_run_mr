package com.zcyh.mr.springboot.measurement.var;

import com.zcyh.mr.springboot.output.db.VarResultPersistService;

import com.zcyh.mr.springboot.output.db.CalcRuleMetaPersistService;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readString;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.measurement.SummaryCleanupMode;
import com.zcyh.mr.springboot.measurement.var.VarSummaryRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * VaR 汇总服务。
 * 从批次场景结果生成 VaR 汇总，并按需要写入结果表。
 */
@Service
public class VarSummaryService {
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

    public JSONObject summarize(VarSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = request.getBatchId();
        String dataDate = request.getDataDate();
        boolean persistResult = request.isPersistResult();

        JSONObject summary = varDbRunnerService.calculateConfigured(request);
        if (persistResult) {
            JSONArray ruleSnapshots = loadRuleSnapshots(request.getRuleIds());
            varResultPersistService.replace(
                    batchId, dataDate, request.getCleanupMode(), request.getCalculations(), summary);
            persistRuleMeta(batchId, dataDate, request, ruleSnapshots);
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
    private JSONArray loadRuleSnapshots(List<String> ruleIds) {
        JSONArray ruleSnapshots = varDbRunnerService.resolveRuleSnapshots(ruleIds);
        if (ruleSnapshots == null || ruleSnapshots.size() != ruleIds.size()) {
            throw new IllegalArgumentException("VaR 规则快照数量与 ruleIds 不一致");
        }
        for (int i = 0; i < ruleSnapshots.size(); i++) {
            JSONObject ruleJson = ruleSnapshots.getJSONObject(i);
            String ruleId = ruleJson == null ? null : readString(ruleJson, "rule_id");
            if (ruleId == null) {
                throw new IllegalArgumentException("VaR 规则快照缺少 rule_id: index=" + i);
            }
        }
        return ruleSnapshots;
    }

    private void persistRuleMeta(String batchId,
                                 String dataDate,
                                 VarSummaryRequest request,
                                 JSONArray ruleSnapshots) {
        if (request.getCleanupMode() == SummaryCleanupMode.FULL) {
            calcRuleMetaPersistService.deleteByBatchAndCalcType(batchId, dataDate, CALC_TYPE_VAR);
        } else if (request.getCleanupMode() == SummaryCleanupMode.RULE) {
            calcRuleMetaPersistService.deleteByBatchCalcTypeAndRuleIds(
                    batchId, dataDate, CALC_TYPE_VAR, request.getRuleIds());
        } else {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        for (int i = 0; i < ruleSnapshots.size(); i++) {
            JSONObject ruleJson = ruleSnapshots.getJSONObject(i);
            String ruleId = readString(ruleJson, "rule_id");
            String ruleJsonStr = ruleJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
            calcRuleMetaPersistService.persist(batchId, dataDate, CALC_TYPE_VAR, ruleId, ruleJsonStr);
        }
    }

}
