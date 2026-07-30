package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.EngineConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalcTradeErrorResultTest {

    @Test
    void inputErrorBecomesTradeDetailWithoutTopLevelLogData() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "T_BAD_JSON_001");
        trade.put("PRODUCT_CODE", "FXFWD");
        trade.put(EngineConstants.CONTROL_FIELD.INPUT_ERROR, "交易JSON格式异常");

        JSONObject result = run(trade);
        JSONObject data = result.getJSONObject("data");
        JSONObject errorTrade = data.getJSONArray("trade_data").getJSONObject(0);

        assertFalse(data.containsKey("log_data"));
        assertEquals("ERROR", errorTrade.getString("STATUS"));
        assertFalse(errorTrade.containsKey("LOGS"));
        assertTrue(errorTrade.getJSONArray("LOGS_JSON")
                .getJSONObject(0).getString("message").contains("交易JSON格式异常"));
    }

    @Test
    void missingInstrumentIdDoesNotCreateTradeResult() {
        JSONObject trade = new JSONObject();
        trade.put("PRODUCT_CODE", "FXFWD");

        JSONObject result = run(trade);

        assertTrue(result.getJSONObject("data").getJSONArray("trade_data").isEmpty());
    }

    private static JSONObject run(JSONObject trade) {
        JSONObject payload = new JSONObject();
        payload.put("calc_mode", "PRICING");
        payload.put("data_date", "2025-12-31");
        payload.put("trade_data", new JSONArray().fluentAdd(trade));
        payload.put("market_data", new JSONArray());
        return JSONObject.parseObject(new Calc(payload.toJSONString(), null).run());
    }
}
