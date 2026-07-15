package com.zcyh.mr.calc.result;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalcResultMergeServiceTest {
    private final CalcResultMergeService service = new CalcResultMergeService();

    @Test
    void curveGenerationOnlyResultContainsGeneratedDataAndErrorLog() {
        JSONArray generated = new JSONArray().fluentAdd(
                new JSONObject().fluentPut("CURVE_ID", "CNY_IR"));

        JSONObject result = JSON.parseObject(service.buildCurveGenerationOnlyResult(
                generated, Collections.singletonList("曲线缺少点位")));
        JSONObject data = result.getJSONObject("data");

        assertEquals(1, data.getJSONArray("generated_market_data").size());
        assertEquals("曲线生成失败: 曲线缺少点位",
                data.getJSONArray("log_data").getJSONObject(0).getString("info"));
    }

    @Test
    void mergeDataAddsDefaultProductCode() {
        JSONObject groupData = new JSONObject();
        groupData.put("trade_data", new JSONArray().fluentAdd(
                new JSONObject().fluentPut("INSTRUMENT_ID", "T1")));
        JSONObject groupResult = new JSONObject().fluentPut("data", groupData);
        JSONObject mergedData = new JSONObject();

        service.mergeData(mergedData, groupResult.toJSONString(), "FXFWD");

        assertEquals("FXFWD",
                mergedData.getJSONArray("trade_data").getJSONObject(0).getString("PRODUCT_CODE"));
    }
}
