package com.zcyh.mr.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.product.ir.IrsCcs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TradeJsonUtilTest {

    @Test
    void normalizeMissingFieldsTreatsNullAndBlankAsOmitted() {
        JSONObject input = JSON.parseObject("{"
                + "\"NULL_VALUE\":null,"
                + "\"EMPTY_VALUE\":\"\","
                + "\"BLANK_VALUE\":\"  \","
                + "\"ZERO_VALUE\":0,"
                + "\"FALSE_VALUE\":false,"
                + "\"EMPTY_ARRAY\":[],"
                + "\"NESTED\":{\"EMPTY_VALUE\":\"\",\"VALUE\":\"A\"},"
                + "\"VALUES\":[null,\"\",{\"EMPTY_VALUE\":null,\"VALUE\":1}]"
                + "}");

        TradeJsonUtil.normalizeMissingFields(input);

        Assertions.assertFalse(input.containsKey("NULL_VALUE"));
        Assertions.assertFalse(input.containsKey("EMPTY_VALUE"));
        Assertions.assertFalse(input.containsKey("BLANK_VALUE"));
        Assertions.assertFalse(input.containsKey("EMPTY_ARRAY"));
        Assertions.assertEquals(0, input.getIntValue("ZERO_VALUE"));
        Assertions.assertFalse(input.getBooleanValue("FALSE_VALUE"));
        Assertions.assertEquals("A", input.getJSONObject("NESTED").getString("VALUE"));
        Assertions.assertFalse(input.getJSONObject("NESTED").containsKey("EMPTY_VALUE"));
        JSONArray values = input.getJSONArray("VALUES");
        Assertions.assertEquals(3, values.size());
        Assertions.assertFalse(values.getJSONObject(2).containsKey("EMPTY_VALUE"));
    }

    @Test
    void normalizeMissingFieldsPreservesPojoDefaultForOmittedNullAndBlank() {
        String[] inputs = {
                "{}",
                "{\"PAY_INTEREST_AGGREGATION_METHOD\":null}",
                "{\"PAY_INTEREST_AGGREGATION_METHOD\":\"\"}",
                "{\"PAY_INTEREST_AGGREGATION_METHOD\":\"  \"}"
        };

        for (String input : inputs) {
            JSONObject normalized = TradeJsonUtil.normalizeMissingFields(JSON.parseObject(input));
            IrsCcs.IrsCcsTradeInfo info = normalized.to(IrsCcs.IrsCcsTradeInfo.class);
            Assertions.assertEquals("COMPOUNDING", info.payInterestAggregationMethod);
        }
    }
}
