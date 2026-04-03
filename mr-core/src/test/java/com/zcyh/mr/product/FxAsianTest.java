package com.zcyh.mr.product;

import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.fx.FxAsian;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

public class FxAsianTest {

    @Test
    public void testDefaultCashAndDetailFields() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        FxAsian.FxAsianInfo info = buildBaseInfo(dataDate);
        info.settleType = null;
        info.obsStartDate = LocalDate.of(2026, 1, 5);
        info.obsEndDate = LocalDate.of(2026, 1, 15);
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
        FxAsian.FxAsianInfo info = buildBaseInfo(dataDate);
        info.obsStartDate = LocalDate.of(2026, 1, 11);
        info.obsEndDate = LocalDate.of(2026, 1, 20);
        info.fixingId = null;

        MarketData marketData = buildMarketData(dataDate);
        FxAsian product = new FxAsian(dataDate, info, marketData);
        FxAsian.FxAsianMeasure measure = product.calc();

        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertTrue(Double.isFinite(measure.valuation));
        Assertions.assertNotNull(measure.detail);
        Assertions.assertNull(measure.detail.get("AVERAGE_PAST"));
    }

    private FxAsian.FxAsianInfo buildBaseInfo(LocalDate dataDate) {
        FxAsian.FxAsianInfo info = new FxAsian.FxAsianInfo();
        info.productCode = Constants.PRODUCT_CODE.FX_ASIAN;
        info.instrumentId = "UT_FX_ASIAN_001";
        info.callOrPut = "CALL";
        info.buyOrSell = "B";
        info.contractSize = 1_000_000.0;
        info.strikePrice = 1.10;
        info.maturityDate = dataDate.plusMonths(6);
        info.settleDate = dataDate.plusMonths(6).plusDays(2);
        info.baseCurrencyCode = "USD";
        info.underlyingCurrencyCode = "EUR";
        info.baseDiscountCurve = "IR_USD";
        info.underlyingDiscountCurve = "IR_EUR";
        info.volatilitySurface = "FXVOL_EURUSD";
        info.instrumentCurrency = "USD";
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
        info.termType = "days";
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

    private Map<String, Object> volPoint(int optionTerm, double delta, double volRate) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("OPTION_TERM", optionTerm);
        point.put("DELTA", delta);
        point.put("VOLATILITY_RATE", volRate);
        return point;
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

