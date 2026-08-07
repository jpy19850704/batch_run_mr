package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.structure.RangeAccureOptBase;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;
import com.zcyh.mr.product.basic.structure.WeddingCakeBase;
import com.zcyh.mr.product.credit.Cds;
import com.zcyh.mr.product.credit.Trs;
import com.zcyh.mr.product.ir.IrsCcs;
import com.zcyh.mr.product.ir.Swaption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;

public class CreditInputValidationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    public void testCreditAndIrInputDatesUseFieldFormats() {
        Cds.CdsTradeInfo cds = JSON.parseObject(
                "{\"START_DATE\":\"2026-01-01\",\"MATURITY_DATE\":\"2026-12-31\"}", Cds.CdsTradeInfo.class);
        Trs.TrsTradeInfo trs = JSON.parseObject(
                "{\"START_DATE\":\"2026-01-01\",\"MATURITY_DATE\":\"2026-12-31\"}", Trs.TrsTradeInfo.class);
        IrsCcs.IrsCcsTradeInfo irsCcs = JSON.parseObject(
                "{\"START_DATE\":\"2026-01-01\",\"MATURITY_DATE\":\"2026-12-31\"}", IrsCcs.IrsCcsTradeInfo.class);
        Swaption.SwaptionTradeInfo swaption = JSON.parseObject(
                "{\"MATURITY_DATE\":\"2026-03-31\",\"UNDERLYING_START_DATE\":\"2026-04-01\","
                        + "\"UNDERLYING_MATURITY_DATE\":\"2027-04-01\"}", Swaption.SwaptionTradeInfo.class);

        Assertions.assertEquals(LocalDate.of(2026, 1, 1), cds.startDate);
        Assertions.assertEquals(LocalDate.of(2026, 12, 31), cds.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 1, 1), trs.startDate);
        Assertions.assertEquals(LocalDate.of(2026, 12, 31), trs.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 1, 1), irsCcs.startDate);
        Assertions.assertEquals(LocalDate.of(2026, 12, 31), irsCcs.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 3, 31), swaption.maturityDate);
        Assertions.assertEquals(LocalDate.of(2026, 4, 1), swaption.underlyingStartDate);
        Assertions.assertEquals(LocalDate.of(2027, 4, 1), swaption.underlyingMaturityDate);
    }

    @Test
    public void testTrsRequiresIndependentUnderlyingNotional() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.underlyingNotional = null;

        Trs.TrsMeasure result = new Trs(
                DATA_DATE, info, new MarketData(), new Calendar(), new JSONObject()).calc();

        Assertions.assertEquals("ERROR", result.status);
        Assertions.assertTrue(result.logs.get(0).message.contains("UNDERLYING_NOTIONAL"));
    }

    @Test
    public void testCdsSettlementFieldsParticipateInTradeValidation() {
        JSONObject trade = buildCdsTrade();
        trade.put("SETTLE_RULE", "Invalid_Rule");
        trade.put("SETTLE_DAYOFF", -1);

        String errors = ProductCalculatorRegistry.validateTradeInput(
                EngineConstants.PRODUCT_CODE.CDS, DATA_DATE, trade).toString();

        Assertions.assertTrue(errors.contains("SETTLE_RULE"));
        Assertions.assertTrue(errors.contains("SETTLE_DAYOFF"));
    }

    @Test
    public void testCdsSettlementFieldsRemainOptionalWhenOmittedInTrialInput() {
        String errors = ProductCalculatorRegistry.validateTradeInput(
                EngineConstants.PRODUCT_CODE.CDS, DATA_DATE, buildCdsTrade()).toString();

        Assertions.assertFalse(errors.contains("SETTLE_RULE"));
        Assertions.assertFalse(errors.contains("SETTLE_DAYOFF"));
    }

    @Test
    public void testTrsAllowsZeroNotionals() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.notional = 0.0;
        info.underlyingNotional = 0.0;

        Trs.TrsMeasure result = new Trs(
                DATA_DATE, info, new MarketData(), new Calendar(), new JSONObject()).calc();

        Assertions.assertEquals("ERROR", result.status);
        Assertions.assertTrue(result.logs.get(0).message.contains("标的债券数据不存在"));
    }

    @Test
    public void testTrsUsesHistoricalPriceAndFxFixingWithoutFundingPrincipal() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.startDate = DATA_DATE;
        info.maturityDate = DATA_DATE.plusYears(1);
        info.underlyingCurrencyCode = "EUR";
        info.underlyingCurrencyDiscountCurve = "IR_EUR";
        info.fxFixingId = "FX_EUR_USD";
        info.interestRate = 0.0;

        Trs.TrsMeasure result = new Trs(
                DATA_DATE, info, buildTrsMarketData(), new Calendar(), buildUnderlyingBond()).calc();

        double expected = 100.0 * Math.exp(-0.10) - 90.0;
        Assertions.assertEquals("SUCCESS", result.status,
                result.logs.isEmpty() ? "" : result.logs.get(0).message);
        Assertions.assertEquals(expected, result.valuation, 1e-8);
        Assertions.assertTrue(result.cashFlowList.stream()
                .anyMatch(cf -> "underlying_total_return".equals(cf.cashFlowType)));
        Assertions.assertTrue(result.cashFlowList.stream()
                .noneMatch(cf -> "funding_principal".equals(cf.cashFlowType)));
    }

    @Test
    public void testTrsRequiresFxFixingIdOnlyForCrossCurrencyTrade() {
        Trs.TrsTradeInfo crossCurrency = buildTrsInfo();
        crossCurrency.underlyingCurrencyCode = "EUR";
        String crossCurrencyErrors = crossCurrency.validateInput(
                JSONObject.parseObject(JSON.toJSONString(crossCurrency)), EngineConstants.PRODUCT_CODE.TRS)
                .getErrors().toString();
        Assertions.assertTrue(crossCurrencyErrors.contains("FX_FIXING_ID"));

        Trs.TrsTradeInfo singleCurrency = buildTrsInfo();
        String singleCurrencyErrors = singleCurrency.validateInput(
                JSONObject.parseObject(JSON.toJSONString(singleCurrency)), EngineConstants.PRODUCT_CODE.TRS)
                .getErrors().toString();
        Assertions.assertFalse(singleCurrencyErrors.contains("FX_FIXING_ID"));
    }

    @Test
    public void testTrsFloatingFundingRequiresReferenceCurveAndFixingId() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.interestType = "FLOATING";
        info.referenceCurve = null;
        info.fixingId = null;

        String errors = info.validateInput(
                JSONObject.parseObject(JSON.toJSONString(info)), EngineConstants.PRODUCT_CODE.TRS)
                .getErrors().toString();

        Assertions.assertTrue(errors.contains("REFERENCE_CURVE"));
        Assertions.assertTrue(errors.contains("FIXING_ID"));
    }

    @Test
    public void testTrsRejectsInvalidScheduleFields() {
        Trs.TrsTradeInfo info = buildTrsInfo();
        info.settleRule = "Invalid_Rule";
        info.fixingDayoff = -1;

        String errors = info.validateInput(
                JSONObject.parseObject(JSON.toJSONString(info)), EngineConstants.PRODUCT_CODE.TRS)
                .getErrors().toString();

        Assertions.assertTrue(errors.contains("SETTLE_RULE"));
        Assertions.assertTrue(errors.contains("FIXING_DAYOFF"));
    }

    @Test
    public void testStructuredNotionalMetadataAllowsZero() throws NoSuchFieldException {
        assertZeroAllowed(RangeAccureOptBase.RangeAccureBaseTradeInfo.class);
        assertZeroAllowed(StepUpOptBase.StepUpBaseTradeInfo.class);
        assertZeroAllowed(WeddingCakeBase.WeddingCakeBaseTradeInfo.class);
    }

    private void assertZeroAllowed(Class<?> infoClass) throws NoSuchFieldException {
        ProductInputField metadata = infoClass.getField("notional").getAnnotation(ProductInputField.class);
        Assertions.assertNotNull(metadata);
        Assertions.assertEquals("0", metadata.min());
        Assertions.assertTrue(metadata.minInclusive());
    }

    private Trs.TrsTradeInfo buildTrsInfo() {
        Trs.TrsTradeInfo info = new Trs.TrsTradeInfo();
        info.instrumentId = "UT_TRS_001";
        info.productCode = EngineConstants.PRODUCT_CODE.TRS;
        info.buyOrSell = "B";
        info.startDate = DATA_DATE.plusDays(1);
        info.maturityDate = DATA_DATE.plusYears(1);
        info.notional = 100.0;
        info.underlyingNotional = 100.0;
        info.currencyCode = "USD";
        info.underlyingCurrencyCode = "USD";
        info.discountCurve = "IR_USD";
        info.underlyingCurrencyDiscountCurve = "IR_USD";
        info.underlyingBondId = "UT_BOND_ID_001";
        info.underlyingFixingId = "TRS_BOND_PRICE_001";
        info.recoveryRate = 0.4;
        info.interestRate = 0.03;
        info.interestType = "FIXED";
        info.payFreq = "3M";
        info.interestStub = "ShortStart";
        return info;
    }

    private JSONObject buildCdsTrade() {
        JSONObject trade = new JSONObject();
        trade.put("INSTRUMENT_ID", "UT_CDS_001");
        trade.put("PRODUCT_CODE", EngineConstants.PRODUCT_CODE.CDS);
        trade.put("BUY_OR_SELL", "B");
        trade.put("START_DATE", "2026-01-01");
        trade.put("MATURITY_DATE", "2026-12-31");
        trade.put("NOTIONAL", 100.0);
        trade.put("CURRENCY_CODE", "USD");
        trade.put("DISCOUNT_CURVE", "IR_USD");
        trade.put("UNDERLYING_BOND_ID", "UT_BOND_ID_001");
        trade.put("RECOVERY_RATE", 0.4);
        trade.put("FIXED_RATE", 0.01);
        trade.put("DAY_COUNT_BASIS", "actual/365");
        trade.put("PAY_FREQ", "3M");
        trade.put("INTEREST_STUB", "LongEnd");
        return trade;
    }

    private MarketData buildTrsMarketData() {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildIrCurve("IR_USD", 0.0));
        marketData.irSpot.put("IR_EUR", buildIrCurve("IR_EUR", 0.10));
        marketData.irSpot.put("CR_EUR", buildIrCurve("CR_EUR", 0.0));

        FxSpot.FxSpotInfo fxSpot = new FxSpot.FxSpotInfo();
        fxSpot.dataDate = DATA_DATE;
        fxSpot.pDataDate = DATA_DATE;
        fxSpot.curveData = new HashMap<>();
        fxSpot.curveData.put("USD/CNY", 1.0);
        fxSpot.curveData.put("EUR/CNY", 1.0);
        marketData.fxSpot = fxSpot;
        marketData.fixingRate.put("TRS_BOND_PRICE_001",
                buildFixing("TRS_BOND_PRICE_001", DATA_DATE.minusDays(1), 90.0));
        marketData.fixingRate.put("FX_EUR_USD",
                buildFixing("FX_EUR_USD", DATA_DATE.minusDays(1), 1.0));
        return marketData;
    }

    private Fixing.FixingInfo buildFixing(String fixingId, LocalDate fixingDate, double value) {
        Fixing.FixingInfo info = new Fixing.FixingInfo();
        info.curveType = "FIXING";
        info.fixingId = fixingId;
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.interpolateType = "FORWARD";
        info.curveData = new Series<>(LocalDate.class, Double.class);
        info.curveData.put(fixingDate, value);
        return info;
    }

    private IrSpot.IrSpotInfo buildIrCurve(String curveId, double rate) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = curveId;
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, rate);
        info.curveData.put(365, rate);
        info.curveData.put(1825, rate);
        return info;
    }

    private JSONObject buildUnderlyingBond() {
        JSONObject bond = new JSONObject();
        bond.put("PRODUCT_CODE", EngineConstants.PRODUCT_CODE.BOND);
        bond.put("INSTRUMENT_ID", "UT_BOND_001");
        bond.put("BOND_ID", "UT_BOND_ID_001");
        bond.put("CURRENCY_CODE", "EUR");
        bond.put("ISSUE_DATE", DATA_DATE);
        bond.put("MATURITY_DATE", DATA_DATE.plusYears(1));
        bond.put("INTEREST_STUB", "LongEnd");
        bond.put("INTEREST_TYPE", "FIXED");
        bond.put("INTEREST_RATE", 0.0);
        bond.put("PAY_FREQ", "1Y");
        bond.put("DAY_COUNT_BASIS", "actual/365");
        bond.put("DISCOUNT_CURVE", "IR_EUR");
        bond.put("CREDIT_SPREAD_CURVE", "CR_EUR");
        bond.put("NOTIONAL", 100.0);
        bond.put("POSITION_TRADE", 1.0);

        JSONObject underlyingData = new JSONObject();
        underlyingData.put("UT_BOND_ID_001", bond);
        return underlyingData;
    }
}
