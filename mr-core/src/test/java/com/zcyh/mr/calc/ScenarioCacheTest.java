package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.ScenarioCache;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScenarioCacheTest {
    private static final String CACHE_KEY = "test:regular:scenario";

    @AfterEach
    void clearCache() {
        ScenarioCache.clear();
    }

    @Test
    void run_whenRegularScenarioComesFromCache_shouldBuildScenarioResult() {
        ScenarioCache.put(CACHE_KEY, List.of(new Loader.ScenarioEntry(
                "S_REGULAR",
                "BASE_UP",
                "普通情景",
                "CUSTOM",
                new MarketData(),
                Set.of("IR_SPOT:CNY_IR"))));

        JSONObject payload = new JSONObject();
        payload.put("data_date", "20260331");
        payload.put("calc_mode", "PRICING");
        payload.put("trade_data", new JSONArray());
        payload.put("market_data", new JSONArray());
        JSONObject ref = new JSONObject();
        ref.put("cache_key", CACHE_KEY);
        payload.put(ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST, new JSONArray(List.of(ref)));

        JSONObject result = JSONObject.parseObject(new Calc(payload.toJSONString(), null).run());
        JSONArray scenarioResult = result.getJSONObject("data").getJSONArray("scenario_result");

        assertNotNull(scenarioResult);
        assertEquals(1, scenarioResult.size());
        assertEquals("S_REGULAR", scenarioResult.getJSONObject(0).getString("SCENARIO_ID"));
        assertEquals("BASE_UP", scenarioResult.getJSONObject(0).getString("SUBSCENARIO_ID"));
    }
}
