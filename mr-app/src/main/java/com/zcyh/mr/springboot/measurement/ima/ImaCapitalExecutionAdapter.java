package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.springboot.execution.ExecutionAdapter;

import static com.zcyh.mr.springboot.support.RequestParseSupport.readBoolean;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.measurement.ima.ImaCapitalTrialService;

/**
 * IMA 资本试算执行适配器。
 */
public class ImaCapitalExecutionAdapter implements ExecutionAdapter {
    public static final String CODE = "ima_capital";

    private final ImaCapitalTrialService trialService;

    public ImaCapitalExecutionAdapter(ImaCapitalTrialService trialService) {
        this.trialService = trialService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "IMA Phase2：从 Doris 读取 PnL → 计算 IMCC/SES/总资本 → 落库";
    }

    @Override
    public String execute(String inputJson) {
        JSONObject request = JSON.parseObject(inputJson);
        if (request == null) {
            throw new IllegalArgumentException("IMA Phase2 input 不能为空");
        }
        if (readBoolean(request, false, "trial")) {
            return JSON.toJSONString(
                    trialService.calculate(request),
                    JSONWriter.Feature.WriteBigDecimalAsPlain);
        }
        throw new IllegalArgumentException("IMA 资本汇总请使用 /api/summary/ima/capital");
    }
}
