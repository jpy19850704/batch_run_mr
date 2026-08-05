package com.zcyh.mr.springboot.input.common;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputJsonSupportTest {
    @Test
    void recognizesStandardFieldsInsideArrayObjects() {
        JSONObject content = JSONObject.of("UNDERLYING_DATA", JSONArray.of(JSONObject.of(
                "DRC_LGD", 0.75,
                "TRADER_CODE", "DUMMY")));

        List<String> invalidPaths = InputJsonSupport.invalidFieldPaths(content,
                List.of("UNDERLYING_DATA[0].DRC_LGD"));

        assertFalse(invalidPaths.contains("UNDERLYING_DATA[0].DRC_LGD"));
        assertEquals(List.of("UNDERLYING_DATA[0].TRADER_CODE"), invalidPaths);
    }

    @Test
    void treatsJsonContainerAsOneStandardField() {
        JSONObject content = JSONObject.of("CALLPUT_DATES", JSONArray.of(JSONObject.of(
                "DATE", "2026-06-01",
                "TYPE", "Call")));

        List<String> invalidPaths = InputJsonSupport.invalidFieldPaths(content, List.of("CALLPUT_DATES"));

        assertTrue(invalidPaths.isEmpty());
    }

    @Test
    void comparesNumericRepresentationsByValue() {
        assertTrue(InputJsonSupport.deepEquals(
                JSONObject.of("DRC_LGD", 1),
                JSONObject.of("DRC_LGD", 1.0)));
    }
}
