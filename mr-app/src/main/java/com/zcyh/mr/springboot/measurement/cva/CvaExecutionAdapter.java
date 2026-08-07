package com.zcyh.mr.springboot.measurement.cva;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.execution.ExecutionAdapter;
import com.zcyh.mr.springboot.output.db.CvaResultPersistService;
import com.zcyh.mr.springboot.support.ResultDbDateSupport;

import java.time.LocalDate;

public class CvaExecutionAdapter implements ExecutionAdapter {
    public static final String CODE = "ba_cva";

    private final CvaInputQueryService inputQueryService;
    private final CvaResultPersistService resultPersistService;

    public CvaExecutionAdapter(CvaInputQueryService inputQueryService,
                               CvaResultPersistService resultPersistService) {
        this.inputQueryService = inputQueryService;
        this.resultPersistService = resultPersistService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "完整版BA-CVA计算器，无合格对冲时按简化版计量";
    }

    @Override
    public String execute(String inputJson) {
        JSONObject request = JSON.parseObject(inputJson);
        if (request == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }
        String batchId = requireText(request.getString("batchId"), "batchId");
        LocalDate dataDate = ResultDbDateSupport.localDate(requireText(request.getString("dataDate"), "dataDate"));
        boolean persistResult = requireBoolean(request, "persistResult");

        CvaRunInput input = inputQueryService.build(batchId, dataDate);
        if (persistResult) {
            resultPersistService.persist(batchId, dataDate, input.result);
        }
        JSONObject output = new JSONObject();
        output.put("batchId", batchId);
        output.put("dataDate", ResultDbDateSupport.protocolDate(dataDate));
        output.put("persistResult", persistResult);
        output.put("calculationMode", input.result.calculationMode);
        output.put("reductionReason", input.result.reductionReason);
        output.put("derivativeNotionalCny", input.result.derivativeNotionalCny);
        output.put("kReduced", input.result.kReduced);
        output.put("kHedged", input.result.kHedged);
        output.put("kFull", input.result.kFull);
        output.put("cvaCapitalRequirement", input.result.cvaCapitalRequirement);
        output.put("cvaRiskWeightedAssets", input.result.cvaRiskWeightedAssets);
        output.put("counterpartyCount", input.result.counterparties.size());
        output.put("nettingSetCount", input.result.nettingSets.size());
        output.put("hedgeCount", input.result.hedges.size());
        return output.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static boolean requireBoolean(JSONObject request, String field) {
        Object value = request.get(field);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(field + " 必须显式传入 true/false");
        }
        return (Boolean) value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 必填");
        }
        return value.trim();
    }
}
