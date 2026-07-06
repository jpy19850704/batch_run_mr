package com.zcyh.mr.refactoring;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.basic.util.EnginePreconditions;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.scenariopnl.SubsetScenarioRunner;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.loader.FileUtils;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.loader.TradeValidator;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.frtb.builder.CmtySensitivityBuilder;
import com.zcyh.mr.product.ir.IrDigOpt;
import com.zcyh.mr.saccr.addon.EquityAddOnCalc;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void tradeValidatorRequiresConfiguredCommodityFields() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "T_COMM_WEDDING_001");
        trade.put("PRODUCT_CODE", "COMM_WEDDING_CAKE");

        List<String> errors = TradeValidator.validate(trade, "COMM_WEDDING_CAKE", "TRADE");

        assertTrue(errors.contains("缺少必填字段: NOTIONAL"));
        assertTrue(errors.contains("缺少必填字段: FIXING_ID"));
    }

    @Test
    void historicalCurveIsNotRuntimeInputContract() {
        JSONObject rules = JSON.parseObject(FileUtils.loadData("data/model/validationRules.json"));

        assertFalse(rules.toJSONString().contains("HISTORICAL_CURVE"));
    }

    @Test
    void productModelDoesNotDeclareUnsupportedFxSharkFin() {
        JSONObject productModel = JSON.parseObject(FileUtils.loadData("data/model/productModel.json"));

        assertFalse(productModel.containsKey("FX_SHARKFIN"));
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
    void cmtyDependenciesRequireExplicitRiskFactorId() {
        assertTrue(CmtySensitivityBuilder.buildDeltaDependencies("COMM_OIL", "", "3").isEmpty());
        assertTrue(CmtySensitivityBuilder.buildVegaDependencies("COMMVOL_OIL", "", "3").isEmpty());
    }

    @Test
    void irTermInputsAreExplicit() {
        LocalDate dataDate = LocalDate.of(2026, 3, 31);
        MarketData marketData = minimalIrOptionMarketData();

        IrDigOpt.IrDigOptInfo missingTermCode = baseIrDigInfo(dataDate);
        missingTermCode.rateType = "PAR";
        missingTermCode.termFreq = "1Y";
        IllegalArgumentException termCodeEx = assertThrows(IllegalArgumentException.class,
                () -> new IrDigOpt(dataDate, missingTermCode, marketData).calc());
        assertTrue(termCodeEx.getMessage().contains("TERM_CODE"));

        IrDigOpt.IrDigOptInfo missingRateType = baseIrDigInfo(dataDate);
        missingRateType.termCode = "10Y";
        IllegalArgumentException rateTypeEx = assertThrows(IllegalArgumentException.class,
                () -> new IrDigOpt(dataDate, missingRateType, marketData).calc());
        assertTrue(rateTypeEx.getMessage().contains("RATE_TYPE"));

        IrDigOpt.IrDigOptInfo missingTermFreq = baseIrDigInfo(dataDate);
        missingTermFreq.termCode = "10Y";
        missingTermFreq.rateType = "PAR";
        IllegalArgumentException termFreqEx = assertThrows(IllegalArgumentException.class,
                () -> new IrDigOpt(dataDate, missingTermFreq, marketData).calc());
        assertTrue(termFreqEx.getMessage().contains("TERM_FREQ"));
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

    private static IrDigOpt.IrDigOptInfo baseIrDigInfo(LocalDate dataDate) {
        IrDigOpt.IrDigOptInfo info = new IrDigOpt.IrDigOptInfo();
        info.instrumentId = "T_IR_DIG_001";
        info.productCode = "IR_DIG_OPT";
        info.buyOrSell = "B";
        info.callOrPut = "Call";
        info.contractSize = 1.0;
        info.strikePrice = 0.02;
        info.maturityDate = dataDate.plusMonths(6);
        info.settleDate = dataDate.plusMonths(6);
        info.discountCurve = "IR_CNY";
        info.referenceCurve = "IR_CNY";
        info.fixingId = "FIX_IR";
        info.volatilitySurface = "IR_VOL";
        info.currencyCode = "CNY";
        return info;
    }

    private static MarketData minimalIrOptionMarketData() {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_CNY", null);
        marketData.irVol.put("IR_VOL", null);
        marketData.fixingRate.put("FIX_IR", null);
        return marketData;
    }
}
