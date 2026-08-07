package com.zcyh.mr.marketdata.input;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketInputDefinitionRegistryTest {

    @Test
    void definitions_shouldUseDedicatedPointTypeForEveryMarketDataType() {
        assertEquals(MarketDataType.values().length, MarketInputDefinitionRegistry.all().size());
        Set<Class<?>> pointTypes = new HashSet<Class<?>>();
        for (MarketInputDefinition definition : MarketInputDefinitionRegistry.all().values()) {
            pointTypes.add(definition.getPointType());
            assertTrue(definition.getFields().stream()
                    .anyMatch(field -> "CURVE_DATA".equals(field.getName())));
            assertTrue(definition.getPointFields().stream()
                    .noneMatch(field -> "dataDate".equals(field.getName()) || "curveCode".equals(field.getName())));
        }
        assertEquals(MarketDataType.values().length, pointTypes.size());
        assertEquals(List.of("OPTION_TERM", "UNDERLYING_TERM", "VOLATILITY_RATE"),
                fieldNames(MarketDataType.IR_VOL));
        assertEquals(List.of("OPTION_TERM", "DELTA", "VOLATILITY_RATE"),
                fieldNames(MarketDataType.FX_VOL));
        assertEquals(List.of("OPTION_TERM", "DELTA", "VOLATILITY_RATE"),
                fieldNames(MarketDataType.EQ_VOL));
        assertEquals(List.of("OPTION_TERM", "DELTA", "VOLATILITY_RATE"),
                fieldNames(MarketDataType.COMM_VOL));
    }

    @Test
    void validator_shouldRejectOuterFieldsInsideVolPoint() {
        JSONObject input = parse("""
                {
                  "CURVE_TYPE":"FX_VOL",
                  "DATA_DATE":"2026-01-02",
                  "CURVE_ID":"FX_VOL_USD_CNY",
                  "AXIS2_TYPE":"DELTA",
                  "CURVE_DATA":[{
                    "OPTION_TERM":30,
                    "DELTA":0.5,
                    "VOLATILITY_RATE":0.12,
                    "dataDate":"2026-01-02"
                  }]
                }
                """);

        List<String> errors = MarketInputValidator.validate(input);

        assertTrue(errors.stream().anyMatch(error -> error.contains("CURVE_DATA[0].dataDate")));
        assertTrue(MarketInputValidator.validateFieldValues(input).isEmpty());
    }

    @Test
    void validator_shouldRejectAxisFieldThatDoesNotMatchAxisType() {
        JSONObject input = parse("""
                {
                  "CURVE_TYPE":"IR_VOL",
                  "DATA_DATE":"2026-01-02",
                  "CURVE_ID":"IR_VOL_CNY",
                  "AXIS2_TYPE":"UNDERLYING_TERM",
                  "CURVE_DATA":[{
                    "OPTION_TERM":30,
                    "UNDERLYING_TERM":365,
                    "DELTA":0.5,
                    "VOLATILITY_RATE":0.18
                  }]
                }
                """);

        List<String> errors = MarketInputValidator.validate(input);

        assertTrue(errors.stream().anyMatch(error -> error.contains("CURVE_DATA[0].DELTA")));
    }

    @Test
    void validator_shouldAcceptDedicatedIrVolPoint() {
        JSONObject input = parse("""
                {
                  "CURVE_TYPE":"IR_VOL",
                  "DATA_DATE":"2026-01-02",
                  "CURVE_ID":"IR_VOL_CNY",
                  "TERM_INTERPOLATE_TYPE":"LINERVAR",
                  "AXIS2_TYPE":"UNDERLYING_TERM",
                  "AXIS2_INTERPOLATE_TYPE":"linear",
                  "CURVE_DATA":[{
                    "OPTION_TERM":30,
                    "UNDERLYING_TERM":365,
                    "VOLATILITY_RATE":0.18
                  }]
                }
                """);

        assertTrue(MarketInputValidator.validate(input).isEmpty());
    }

    @Test
    void validator_shouldRejectNumericString() {
        JSONObject input = parse("""
                {
                  "CURVE_TYPE":"EQ_SPOT",
                  "DATA_DATE":"2026-01-02",
                  "CURVE_ID":"EQ_SPOT_CN_A",
                  "CURVE_DATA":[{"TERM":30,"EQ_PRICE":"3515"}]
                }
                """);

        List<String> errors = MarketInputValidator.validate(input);

        assertTrue(errors.stream().anyMatch(error -> error.contains("EQ_PRICE必须为数值类型")));
    }

    private JSONObject parse(String text) {
        return JSON.parseObject(text);
    }

    private List<String> fieldNames(MarketDataType marketDataType) {
        return MarketInputDefinitionRegistry.get(marketDataType).getPointFields().stream()
                .map(MarketFieldDefinition::getName)
                .toList();
    }
}
