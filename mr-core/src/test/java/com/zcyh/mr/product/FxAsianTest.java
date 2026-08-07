package com.zcyh.mr.product;

import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.fx.FxAsian;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

public class FxAsianTest {

    @Test
    public void testCashAndDetailFields() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        FxAsian.FxAsianTradeInfo info = buildBaseInfo(dataDate);
        info.obsDates = dailyDates(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 15));
        info.fixingId = "FX_ASIAN_FIX";

        MarketData marketData = buildMarketData(dataDate);
        FxAsian product = new FxAsian(dataDate, info, marketData);
        FxAsian.FxAsianMeasure measure = product.calc();

        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertTrue(Double.isFinite(measure.valuation));
        Assertions.assertNotNull(measure.detail);

        List<String> expectedKeys = Arrays.asList(
                "AVERAGE_PAST",
                "PAST_WEIGHT",
                "FUTURE_WEIGHT",
                "FORWARD_EQ",
                "STRIKE_EQ",
                "SIGMA_EQ",
                "D1_EQ",
                "D2_EQ");
        for (String key : expectedKeys) {
            Assertions.assertTrue(measure.detail.containsKey(key), "缺少detail字段: " + key);
        }
        Assertions.assertEquals(8, measure.detail.size());
        Assertions.assertFalse(measure.detail.containsKey("SPOT"));
        Assertions.assertFalse(measure.detail.containsKey("PRICING_METHOD"));
        Assertions.assertFalse(measure.detail.containsKey("PV_UNIT_RAW"));
        Assertions.assertFalse(measure.detail.containsKey("DISCOUNT_FACTOR"));
        Assertions.assertFalse(measure.detail.containsKey("FIXING_USED_COUNT"));
        Assertions.assertFalse(measure.detail.containsKey("FIXING_MISSING_COUNT"));
    }

    @Test
    public void testFutureOnlyNoFixingRequired() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        FxAsian.FxAsianTradeInfo info = buildBaseInfo(dataDate);
        info.obsDates = dailyDates(LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 20));
        info.fixingId = null;

        MarketData marketData = buildMarketData(dataDate);
        FxAsian product = new FxAsian(dataDate, info, marketData);
        FxAsian.FxAsianMeasure measure = product.calc();

        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertTrue(Double.isFinite(measure.valuation));
        Assertions.assertNotNull(measure.detail);
        Assertions.assertNull(measure.detail.get("AVERAGE_PAST"));
    }

    @Test
    public void testMissingSettleTypeIsRejected() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        FxAsian.FxAsianTradeInfo info = buildBaseInfo(dataDate);
        info.settleType = null;
        info.obsDates = dailyDates(LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 20));

        FxAsian.FxAsianMeasure measure = new FxAsian(
                dataDate, info, buildMarketData(dataDate)).calc();

        Assertions.assertEquals("ERROR", measure.status);
        Assertions.assertTrue(measure.logs.get(0).message.contains("SETTLE_TYPE"));
    }

    @Test
    public void testOnlyConfiguredObservationDatesAreUsed() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        FxAsian.FxAsianTradeInfo info = buildBaseInfo(dataDate);
        info.obsDates = "2026-01-08,2026-01-10,2026-01-15";
        info.fixingId = "FX_ASIAN_FIX";

        FxAsian.FxAsianMeasure measure = new FxAsian(
                dataDate, info, buildMarketData(dataDate)).calc();

        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertEquals(1.0945, (Double) measure.detail.get("AVERAGE_PAST"), 1e-12);
        Assertions.assertEquals(2.0 / 3.0, (Double) measure.detail.get("PAST_WEIGHT"), 1e-12);
        Assertions.assertEquals(1.0 / 3.0, (Double) measure.detail.get("FUTURE_WEIGHT"), 1e-12);
    }

    @Test
    public void testPhysicalSettlementUsesForwardRatio() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        MarketData marketData = buildMarketData(dataDate);

        FxAsian.FxAsianTradeInfo physicalInfo = buildBaseInfo(dataDate);
        physicalInfo.settleType = "PHYSICAL";
        physicalInfo.obsDates = dailyDates(LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 20));

        IrSpot baseIr = new IrSpot(marketData.irSpot.get(physicalInfo.baseDiscountCurve));
        IrSpot underIr = new IrSpot(marketData.irSpot.get(physicalInfo.underlyingDiscountCurve));
        double forwardRatio = underIr.fwdDiscount(physicalInfo.maturityDate, physicalInfo.settleDate)
                / baseIr.fwdDiscount(physicalInfo.maturityDate, physicalInfo.settleDate);

        FxAsian.FxAsianTradeInfo adjustedCashInfo = buildBaseInfo(dataDate);
        adjustedCashInfo.strikePrice = physicalInfo.strikePrice / forwardRatio;
        adjustedCashInfo.obsDates = physicalInfo.obsDates;

        FxAsian.FxAsianMeasure physical = new FxAsian(
                dataDate, physicalInfo, marketData).calc(marketData);
        FxAsian.FxAsianMeasure adjustedCash = new FxAsian(
                dataDate, adjustedCashInfo, marketData).calc(marketData);

        Assertions.assertEquals(
                adjustedCash.valuationUnit * forwardRatio,
                physical.valuationUnit,
                1e-10);
    }

    @Test
    public void testMaturityDateCanEqualDataDate() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        FxAsian.FxAsianTradeInfo info = buildBaseInfo(dataDate);
        info.maturityDate = dataDate;
        info.settleDate = dataDate.plusDays(2);
        info.strikePrice = 1.08;
        info.obsDates = "2026-01-08,2026-01-09,2026-01-10";
        info.fixingId = "FX_ASIAN_FIX";

        MarketData marketData = buildMarketData(dataDate);
        FxAsian.FxAsianMeasure measure = new FxAsian(dataDate, info, marketData).calc();

        double average = (1.094 + 1.0945 + 1.095) / 3.0;
        double discount = new IrSpot(marketData.irSpot.get(info.baseDiscountCurve))
                .discount(info.settleDate);
        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertEquals(discount * (average - info.strikePrice), measure.valuationUnit, 1e-12);
    }

    @Test
    public void testGreeksAreIndependentOfPosition() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        MarketData marketData = buildMarketData(dataDate);

        FxAsian.FxAsianTradeInfo buyInfo = buildBaseInfo(dataDate);
        buyInfo.obsDates = dailyDates(LocalDate.of(2026, 1, 11), LocalDate.of(2026, 1, 20));

        FxAsian.FxAsianTradeInfo sellInfo = buildBaseInfo(dataDate);
        sellInfo.buyOrSell = "S";
        sellInfo.contractSize = buyInfo.contractSize * 2.0;
        sellInfo.obsDates = buyInfo.obsDates;

        FxAsian.FxAsianMeasure buy = new FxAsian(dataDate, buyInfo, marketData).calc();
        FxAsian.FxAsianMeasure sell = new FxAsian(dataDate, sellInfo, marketData).calc();

        Assertions.assertEquals("SUCCESS", buy.status);
        Assertions.assertEquals("SUCCESS", sell.status);
        Assertions.assertEquals(buy.delta, sell.delta, 1e-12);
        Assertions.assertEquals(buy.gamma, sell.gamma, 1e-12);
        Assertions.assertEquals(buy.vega, sell.vega, 1e-12);
        Assertions.assertEquals(buy.theta, sell.theta, 1e-12);
        Assertions.assertEquals(-2.0 * buy.valuation, sell.valuation, 1e-6);
    }

    private FxAsian.FxAsianTradeInfo buildBaseInfo(LocalDate dataDate) {
        FxAsian.FxAsianTradeInfo info = new FxAsian.FxAsianTradeInfo();
        info.productCode = EngineConstants.PRODUCT_CODE.FX_ASIAN;
        info.instrumentId = "UT_FX_ASIAN_001";
        info.callOrPut = "CALL";
        info.buyOrSell = "B";
        info.contractSize = 1_000_000.0;
        info.strikePrice = 1.10;
        info.maturityDate = dataDate.plusMonths(6);
        info.settleDate = dataDate.plusMonths(6).plusDays(2);
        info.settleType = "CASH";
        info.baseCurrencyCode = "USD";
        info.underlyingCurrencyCode = "EUR";
        info.baseDiscountCurve = "IR_USD";
        info.underlyingDiscountCurve = "IR_EUR";
        info.volatilitySurface = "FXVOL_EURUSD";
        info.currencyCode = "USD";
        return info;
    }

    private MarketData buildMarketData(LocalDate dataDate) {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_USD", buildIrCurve(dataDate, "IR_USD", 0.028, 0.031));
        marketData.irSpot.put("IR_EUR", buildIrCurve(dataDate, "IR_EUR", 0.016, 0.020));
        marketData.fxSpot = buildFxSpot(dataDate);
        marketData.fxVol.put("FXVOL_EURUSD", buildFxVol(dataDate));
        marketData.fixingRate.put("FX_ASIAN_FIX", buildFixing(dataDate));
        return marketData;
    }

    private IrSpot.IrSpotInfo buildIrCurve(LocalDate dataDate, String code, double r0, double r1y) {
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.curveType = "IR_SPOT";
        info.curveCode = code;
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.dayCount = "actual/365";
        info.freq = "cont";
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, r0);
        info.curveData.put(365, r1y);
        return info;
    }

    private FxSpot.FxSpotInfo buildFxSpot(LocalDate dataDate) {
        FxSpot.FxSpotInfo info = new FxSpot.FxSpotInfo();
        info.curveType = "FX_SPOT";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.curveData = new HashMap<>();
        info.curveData.put("EUR/USD", 1.10);
        info.curveData.put("USD/CNY", 7.20);
        return info;
    }

    private FxVol.FxVolInfo buildFxVol(LocalDate dataDate) {
        FxVol.FxVolInfo info = new FxVol.FxVolInfo();
        info.curveType = "FX_VOL";
        info.curveCode = "FXVOL_EURUSD";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.curveData = new ArrayList<>();
        info.curveData.add(volPoint(180, 0.10, 0.170));
        info.curveData.add(volPoint(180, 0.25, 0.160));
        info.curveData.add(volPoint(180, 0.50, 0.150));
        info.curveData.add(volPoint(180, 0.75, 0.155));
        info.curveData.add(volPoint(180, 0.90, 0.165));
        info.shockCurveData = new ArrayList<>();
        return info;
    }

    private VolSurfacePoint volPoint(int optionTerm, double delta, double volRate) {
        return new VolSurfacePoint(optionTerm, delta, volRate);
    }

    private String dailyDates(LocalDate start, LocalDate end) {
        List<String> dates = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            dates.add(date.toString());
        }
        return String.join(",", dates);
    }

    private Fixing.FixingInfo buildFixing(LocalDate dataDate) {
        Fixing.FixingInfo info = new Fixing.FixingInfo();
        info.curveType = "FIXING";
        info.fixingId = "FX_ASIAN_FIX";
        info.dataDate = dataDate;
        info.pDataDate = dataDate.minusDays(30);
        info.interpolateType = "forward";
        info.curveData = new Series<>(LocalDate.class, Double.class);
        for (int i = 0; i <= 40; i++) {
            LocalDate d = dataDate.minusDays(30).plusDays(i);
            info.curveData.put(d, 1.08 + i * 0.0005);
        }
        return info;
    }
}
