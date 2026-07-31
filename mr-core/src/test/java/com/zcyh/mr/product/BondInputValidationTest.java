package com.zcyh.mr.product;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.ir.Bond;
import com.zcyh.mr.product.ir.BondFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

public class BondInputValidationTest {

    private static final LocalDate DATA_DATE = LocalDate.of(2025, 12, 31);

    @Test
    public void testBondFieldDefaults() {
        Bond.BondTradeInfo info = new Bond.BondTradeInfo();

        Assertions.assertEquals(100.0, info.notional);
        Assertions.assertEquals(1.0, info.positionTrade);
        Assertions.assertEquals(0.0, info.spread);
        Assertions.assertEquals(0.75, info.lgd);
        Assertions.assertEquals("Y", info.drcFlag);
        Assertions.assertEquals("actual/365", info.dayCountBasis);
    }

    @Test
    public void testLastResetRateMetadataRequiresFiniteValue() throws NoSuchFieldException {
        ProductInputField metadata = Bond.BondTradeInfo.class.getField("lastResetRate")
                .getAnnotation(ProductInputField.class);

        Assertions.assertNotNull(metadata);
        Assertions.assertTrue(metadata.finite());
    }

    @Test
    public void testBondAllowsZeroNotional() {
        Bond.BondTradeInfo info = buildBondInfo();
        info.notional = 0.0;

        Assertions.assertDoesNotThrow(() -> new Bond(DATA_DATE, info, buildMarketData(), new Calendar()));
    }

    @Test
    public void testBondRejectsNegativeNotional() {
        Bond.BondTradeInfo info = buildBondInfo();
        info.notional = -1.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Bond(DATA_DATE, info, buildMarketData(), new Calendar()));

        Assertions.assertTrue(exception.getMessage().contains("NOTIONAL"));
    }

    @Test
    public void testBondRejectsInvalidInterestType() {
        Bond.BondTradeInfo info = buildBondInfo();
        info.interestType = "OTHER";

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Bond(DATA_DATE, info, buildMarketData(), new Calendar()));

        Assertions.assertTrue(exception.getMessage().contains("INTEREST_TYPE"));
    }

    @Test
    public void testBondFutureAllowsZeroPosition() {
        BondFuture.BondFutureTradeInfo info = buildBondFutureInfo();
        info.underlyingPosition = 0.0;

        Assertions.assertDoesNotThrow(() -> new BondFuture(
                DATA_DATE, info, buildMarketData(), new Calendar(), new JSONObject()));
    }

    @Test
    public void testBondFutureRequiresConvertFactors() {
        BondFuture.BondFutureTradeInfo info = buildBondFutureInfo();
        info.convertFactors = null;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BondFuture(DATA_DATE, info, buildMarketData(), new Calendar(), new JSONObject()));

        Assertions.assertTrue(exception.getMessage().contains("CONVERT_FACTORS"));
    }

    @Test
    public void testBondFutureRejectsZeroConvertFactor() {
        BondFuture.BondFutureTradeInfo info = buildBondFutureInfo();
        info.convertFactors.get(0).convertFactor = 0.0;

        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class,
                () -> new BondFuture(DATA_DATE, info, buildMarketData(), new Calendar(), new JSONObject()));

        Assertions.assertTrue(exception.getMessage().contains("CONVERT_FACTOR"));
    }

    @Test
    public void testBondFutureParsesMaturityDateByFieldFormat() {
        BondFuture.BondFutureTradeInfo info = JSON.parseObject(
                "{\"MATURITY_DATE\":\"20260331\"}", BondFuture.BondFutureTradeInfo.class);

        Assertions.assertEquals(LocalDate.of(2026, 3, 31), info.maturityDate);
    }

    @Test
    public void testBondFutureAbsFlagDefaultsToFalse() {
        BondFuture.BondFutureTradeInfo info = new BondFuture.BondFutureTradeInfo();

        Assertions.assertFalse(info.absFlag);
    }

    private Bond.BondTradeInfo buildBondInfo() {
        Bond.BondTradeInfo info = new Bond.BondTradeInfo();
        info.productCode = EngineConstants.PRODUCT_CODE.BOND;
        info.instrumentId = "UT_BOND_001";
        info.bondId = "UT_BOND_ID_001";
        info.currencyCode = "USD";
        info.issueDate = DATA_DATE.minusYears(1);
        info.maturityDate = DATA_DATE.plusYears(1);
        info.interestStub = "ShortStart";
        info.interestType = "FIXED";
        info.interestRate = 0.03;
        info.payFreq = "6M";
        info.discountCurve = "IR_USD";
        return info;
    }

    private BondFuture.BondFutureTradeInfo buildBondFutureInfo() {
        BondFuture.ConvertFactor factor = new BondFuture.ConvertFactor();
        factor.underlyingBondId = "UT_BOND_ID_001";
        factor.convertFactor = 0.95;

        BondFuture.BondFutureTradeInfo info = new BondFuture.BondFutureTradeInfo();
        info.productCode = EngineConstants.PRODUCT_CODE.BOND_FUTURE;
        info.instrumentId = "UT_BOND_FUTURE_001";
        info.currencyCode = "USD";
        info.underlyingPosition = 1.0;
        info.discountCurve = "IR_USD";
        info.futurePrice = 100.0;
        info.maturityDate = DATA_DATE.plusMonths(3);
        info.convertFactors = List.of(factor);
        return info;
    }

    private MarketData buildMarketData() {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildIrCurve());
        marketData.fxSpot = buildFxSpot();
        return marketData;
    }

    private IrSpot.IrSpotInfo buildIrCurve() {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = "IR_USD";
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, 0.028);
        info.curveData.put(365, 0.031);
        info.curveData.put(730, 0.033);
        return info;
    }

    private FxSpot.FxSpotInfo buildFxSpot() {
        FxSpot.FxSpotInfo info = new FxSpot.FxSpotInfo();
        info.curveType = "FX_SPOT";
        info.dataDate = DATA_DATE;
        info.pDataDate = DATA_DATE;
        info.curveData = new HashMap<>();
        info.curveData.put("USD/CNY", 7.20);
        return info;
    }
}
