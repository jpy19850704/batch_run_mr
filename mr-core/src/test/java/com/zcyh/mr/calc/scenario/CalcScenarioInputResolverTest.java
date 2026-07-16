package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalcScenarioInputResolverTest {
    private static final String CACHE_KEY = "test:ima:nmrf";

    @AfterEach
    void clearCache() {
        CalcScenarioInputCache.clear();
    }

    @Test
    void resolveScenarioData_whenImaConfigProvided_shouldNotDependOnGenerationState() {
        JSONObject payload = imaNmrfPayload();
        CalcScenarioInputCache.put(CACHE_KEY, List.of(nmrfEntry()));
        LiquidityHorizonTable config = LiquidityHorizonTable.fromCurveConfig(
                Map.of("IR_SPOT|CNY_IR", 10),
                Map.of("IR_SPOT|CNY_IR", "GIRR"));

        List<Loader.ScenarioEntry> result = CalcScenarioInputResolver.resolveScenarioData(
                payload.toJSONString(), new Loader(payload.toJSONString()), config);

        assertEquals(1, result.size());
        assertEquals(ScenarioProcessConstants.IMA_NMRF, result.get(0).processMetadata.processType);
        assertEquals("CNY_IR", result.get(0).processMetadata.nmrfRiskFactorId);
        assertEquals(ImaConstants.NMRF_TYPE_OTHER, result.get(0).processMetadata.nmrfType);
    }

    @Test
    void resolveScenarioData_whenImaConfigMissing_shouldFailExplicitly() {
        JSONObject payload = imaNmrfPayload();
        CalcScenarioInputCache.put(CACHE_KEY, List.of(nmrfEntry()));

        assertThrows(IllegalStateException.class, () -> CalcScenarioInputResolver.resolveScenarioData(
                payload.toJSONString(), new Loader(payload.toJSONString()), null));
    }

    private static JSONObject imaNmrfPayload() {
        JSONObject payload = new JSONObject();
        payload.put("data_date", "20260331");
        payload.put("trade_data", new JSONArray());
        payload.put("market_data", new JSONArray());
        JSONObject ref = new JSONObject();
        ref.put("cache_key", CACHE_KEY);
        payload.put(ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST, new JSONArray(List.of(ref)));
        return payload;
    }

    private static Loader.ScenarioEntry nmrfEntry() {
        return new Loader.ScenarioEntry(
                "S_NMRF",
                "RFET_01_UP",
                "NMRF 上行情景",
                "IMA_NMRF",
                new MarketData(),
                Set.of("IR_SPOT:CNY_IR"));
    }
}
