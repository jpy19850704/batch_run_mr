package com.zcyh.mr.refactoring;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.basic.util.EnginePreconditions;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.scenariopnl.SubsetScenarioRunner;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.loader.TradeValidator;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.saccr.addon.EquityAddOnCalc;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictContractTest {

    @Test
    void enginePreconditionsThrowsStandardException() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EnginePreconditions.require(false, "字段不能为空: %s", "DATA_DATE"));

        assertEquals("字段不能为空: DATA_DATE", ex.getMessage());
    }

    @Test
    void tradeValidatorLoadsRuntimeValidationRules() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "T001");
        trade.put("PRODUCT_CODE", "BOND");

        List<String> errors = TradeValidator.validate(trade, "BOND", "TRADE");

        assertTrue(errors.contains("缺少必填字段: CURRENCY_CODE"));
        assertTrue(errors.contains("缺少必填字段: DISCOUNT_CURVE"));
    }

    @Test
    void equityAddonRejectsMissingEquityInfo() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EquityAddOnCalc.calc(Map.of("EQ_A", 100.0), Map.of()));

        assertTrue(ex.getMessage().contains("缺少 EquityInfo"));
    }

    @Test
    void liquidityHorizonRejectsMissingCurveLh() {
        LiquidityHorizonTable table = LiquidityHorizonTable.fromCurveLiquidityHorizonDays(Map.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> table.getLhDays("CNY_IR"));

        assertTrue(ex.getMessage().contains("流动性期限未配置"));
    }

    @Test
    void fxLiquidityHorizonUsesImaCurrencySet() {
        assertEquals(10, LiquidityHorizonTable.resolveFxLiquidityHorizonDays("USD/ZAR"));
        assertEquals(10, LiquidityHorizonTable.resolveFxLiquidityHorizonDays("CNY/ZAR"));
        assertEquals(20, LiquidityHorizonTable.resolveFxLiquidityHorizonDays("USD/XXX"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> LiquidityHorizonTable.resolveFxLiquidityHorizonDays("USDCNY"));

        assertTrue(ex.getMessage().contains("格式必须为AAA/BBB"));
    }

    @Test
    void frtbSaFxScaledCurrenciesUseRegulatoryScope() {
        assertTrue(FrtbParamsCache.isFxScaledCurrency("ZAR"));
        assertTrue(FrtbParamsCache.isFxScaledCurrency("MXN"));
        assertTrue(FrtbParamsCache.isFxScaledCurrency("HKD"));
        assertTrue(FrtbParamsCache.isFxScaledCurrency("BRL"));
    }

    @Test
    void fxSpotScenarioSubsetUsesResolvedLiquidityHorizon() {
        MarketData marketData = new MarketData();
        marketData.fxSpot.curveData.clear();
        marketData.fxSpot.curveData.put("USD/ZAR", 18.5);
        Loader.ScenarioEntry source = new Loader.ScenarioEntry(
                "S001",
                "SS001",
                "FX_ZAR",
                "NORMAL",
                marketData,
                Set.of("FX_SPOT:USD/ZAR"));

        SubsetScenarioRunner runner = new SubsetScenarioRunner(
                LiquidityHorizonTable.fromCurveConfig(Map.of(), Map.of()));
        List<Loader.ScenarioEntry> entries = runner.buildModellableScenarioEntries(
                List.of(source),
                "IMA_MODELLABLE",
                "lh",
                "imaRiskClass",
                "T");

        assertTrue(hasFxPair(entries, 10, "FX", "USD/ZAR"));
        assertTrue(hasFxPair(entries, 10, "ALL", "USD/ZAR"));
        assertTrue(!hasFxPair(entries, 20, "FX", "USD/ZAR"));
    }

    private static boolean hasFxPair(List<Loader.ScenarioEntry> entries,
                                     int lh,
                                     String riskClass,
                                     String currencyPair) {
        for (Loader.ScenarioEntry entry : entries) {
            if (entry == null || entry.processMetadata == null || entry.processMetadata.tag == null) {
                continue;
            }
            Integer entryLh = entry.processMetadata.tag.getInteger("lh");
            String entryRiskClass = entry.processMetadata.tag.getString("imaRiskClass");
            if (entryLh != null && entryLh == lh && riskClass.equals(entryRiskClass)
                    && entry.marketData != null
                    && entry.marketData.fxSpot != null
                    && entry.marketData.fxSpot.curveData != null
                    && entry.marketData.fxSpot.curveData.containsKey(currencyPair)) {
                return true;
            }
        }
        return false;
    }
}
