package com.zcyh.mr.frtbsa.sba.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrtbAllRiskClassAssemblerTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldExcludeInvalidClassCapitalAndKeepValidRiskClass() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("GIRR", riskClassResult(10.0, 12.0, 8.0));
        result.put("EQ", riskClassResult("invalid", 7.0, 6.0));

        new FrtbAllRiskClassAssembler().append(result);

        Map<String, Object> all = (Map<String, Object>) result.get("ALL");
        Map<String, Object> delta = (Map<String, Object>) all.get("Delta");
        Map<String, Object> capital = (Map<String, Object>) delta.get("class");
        assertEquals(10.0, capital.get("capital_normal"));
        assertEquals(12.0, capital.get("capital_high"));
        assertEquals(8.0, capital.get("capital_low"));
        assertEquals(12.0, capital.get("capital"));
        assertTrue(result.containsKey("EQ"));
    }

    private static Map<String, Object> riskClassResult(Object normal, Object high, Object low) {
        Map<String, Object> capital = new LinkedHashMap<String, Object>();
        capital.put("capital_normal", normal);
        capital.put("capital_high", high);
        capital.put("capital_low", low);
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("class", capital);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("Delta", delta);
        return result;
    }
}
