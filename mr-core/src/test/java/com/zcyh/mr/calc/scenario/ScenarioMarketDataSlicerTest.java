package com.zcyh.mr.calc.scenario;

import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioMarketDataSlicerTest {

    private final ScenarioMarketDataSlicer slicer = new ScenarioMarketDataSlicer();

    @Test
    void slice_whenGroupIsIr_shouldOnlyKeepIrSpotCurves() {
        MarketData source = new MarketData();
        source.irSpot.put("CNY_IR", irSpot("IR_SPOT"));
        source.irSpot.put("CNY_CREDIT", irSpot("CREDIT_SPOT"));

        MarketData result = slicer.slice(source, "IR");

        assertEquals(1, result.irSpot.size());
        assertTrue(result.irSpot.containsKey("CNY_IR"));
    }

    @Test
    void deriveImpactKeys_whenGroupIsAll_shouldKeepCurveTypes() {
        MarketData source = new MarketData();
        source.irSpot.put("CNY_IR", irSpot("IR_SPOT"));
        source.irSpot.put("CNY_CREDIT", irSpot("CREDIT_SPOT"));

        Set<String> result = slicer.deriveImpactKeys(source, "ALL");

        assertTrue(result.contains("IR_SPOT:CNY_IR"));
        assertTrue(result.contains("CREDIT_SPOT:CNY_CREDIT"));
    }

    private static IrSpot.IrSpotInfo irSpot(String curveType) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = curveType;
        return info;
    }
}
