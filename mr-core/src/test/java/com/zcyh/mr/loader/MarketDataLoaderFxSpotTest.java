package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataLoaderFxSpotTest {

    @Test
    void shouldLoadBaseCurrencyOnEitherSideAcrossContainers() {
        JSONArray errors = new JSONArray();
        MarketData marketData = new MarketDataLoader(LocalDate.of(2025, 12, 31), errors, "USD")
                .loadBaseMarketData(new JSONArray()
                        .fluentAdd(curve(point("USD/CNY", 7.20), point("EUR/USD", 1.08)))
                        .fluentAdd(curve(point("USD/JPY", 150.00))));

        assertEquals(3, marketData.fxSpot.curveData.size());
        assertTrue(errors.isEmpty());
    }

    @Test
    void shouldRemovePairWithoutBaseCurrencyAndReverseDuplicate() {
        JSONArray errors = new JSONArray();
        MarketData marketData = new MarketDataLoader(LocalDate.of(2025, 12, 31), errors, "USD")
                .loadBaseMarketData(new JSONArray()
                        .fluentAdd(curve(
                                point("EUR/USD", 1.08),
                                point("USD/EUR", 0.9259),
                                point("EUR/JPY", 162.00))));

        assertEquals(1, marketData.fxSpot.curveData.size());
        assertTrue(marketData.fxSpot.curveData.containsKey("EUR/USD"));
        assertFalse(marketData.fxSpot.curveData.containsKey("USD/EUR"));
        assertFalse(marketData.fxSpot.curveData.containsKey("EUR/JPY"));
        assertEquals(2, errors.size());
    }

    private static JSONObject curve(JSONObject... points) {
        return new JSONObject()
                .fluentPut("CURVE_TYPE", "FX_SPOT")
                .fluentPut("CURVE_ID", "FX_SPOT_USD_BASE")
                .fluentPut("DATA_DATE", "20251231")
                .fluentPut("CURVE_DATA", new JSONArray(java.util.Arrays.asList(points)));
    }

    private static JSONObject point(String currencyPair, double rate) {
        return new JSONObject().fluentPut("CURRENCY", currencyPair).fluentPut("RATE", rate);
    }
}
