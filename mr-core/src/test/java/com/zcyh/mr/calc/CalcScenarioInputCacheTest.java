package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CalcScenarioInputCacheTest {
    private static final String CACHE_KEY = "test:regular:scenario";

    @AfterEach
    void clearCache() {
        CalcScenarioInputCache.clear();
        CalcScenarioInputCache.configure(512, 3_000_000L);
    }

    @Test
    void run_whenRegularScenarioComesFromCache_shouldBuildScenarioResult() {
        CalcScenarioInputCache.put(CACHE_KEY, List.of(new Loader.ScenarioEntry(
                "S_REGULAR",
                "BASE_UP",
                "普通情景",
                "CUSTOM",
                new MarketData(),
                Set.of("IR_SPOT:CNY_IR"))));

        JSONObject payload = new JSONObject();
        payload.put("data_date", "2026-03-31");
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

    @Test
    void loadFromFiles_whenConcurrentRequestsUseSameKey_shouldShareOneCacheEntry() throws Exception {
        String filePath = java.nio.file.Paths.get(
                getClass().getResource("/scenario/generated-scenario.csv").toURI()).toString();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return CalcScenarioInputCache.loadFromFiles(
                            CACHE_KEY, List.of(filePath), java.time.LocalDate.of(2026, 3, 31));
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals(CACHE_KEY, future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, CalcScenarioInputCache.size());
        assertNotNull(CalcScenarioInputCache.get(CACHE_KEY));
        assertEquals(1, CalcScenarioInputCache.get(CACHE_KEY).size());
        assertEquals(1L, CalcScenarioInputCache.retainedPointCount(CACHE_KEY));
    }
}
