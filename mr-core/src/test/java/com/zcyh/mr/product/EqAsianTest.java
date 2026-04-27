package com.zcyh.mr.product;

import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.eq.EqAsian;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

public class EqAsianTest {

    @Test
    public void testEqAsianCalcAndDetailFields() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        EqAsian.EqAsianInfo info = buildBaseInfo(dataDate);
        info.obsStartDate = LocalDate.of(2026, 1, 5);
        info.obsEndDate = LocalDate.of(2026, 1, 20);
        info.fixingId = "EQ_ASIAN_FIX";

        MarketData marketData = buildMarketData(dataDate);
        EqAsian product = new EqAsian(dataDate, info, marketData);
        EqAsian.EqAsianMeasure measure = product.calc();

        Assertions.assertEquals("SUCCESS", measure.status);
        Assertions.assertTrue(Double.isFinite(measure.valuation));
        Assertions.assertTrue(Double.isFinite(measure.valuationCny));
        Assertions.assertNotNull(measure.detail);
        Assertions.assertNotNull(measure.sensitivityList);

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
    }

    private EqAsian.EqAsianInfo buildBaseInfo(LocalDate dataDate) {
        EqAsian.EqAsianInfo info = new EqAsian.EqAsianInfo();
        info.productCode = Constants.PRODUCT_CODE.EQ_ASIAN;
        info.instrumentId = "UT_EQ_ASIAN_001";
        info.callOrPut = "CALL";
        info.buyOrSell = "B";
        info.contractSize = 1000.0;
        info.strikePrice = 100.0;
        info.maturityDate = dataDate.plusMonths(6);
        info.settleDate = dataDate.plusMonths(6).plusDays(2);
        info.currencyCode = "CNY";
        info.discountCurve = "IR_CNY";
        info.referenceCurve = "EQ_IDX";
        info.volatilitySurface = "EQVOL_IDX";
        info.frtbEqBucket = "11";
        return info;
    }

    private MarketData buildMarketData(LocalDate dataDate) {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_CNY", buildIrCurve(dataDate, "IR_CNY", 0.020, 0.024));
        marketData.eqSpot.put("EQ_IDX", buildEqCurve(dataDate, "EQ_IDX", 102.0, 108.0));
        marketData.eqVol.put("EQVOL_IDX", buildEqVol(dataDate));
        marketData.fxSpot = buildFxSpot(dataDate);
        marketData.fixingRate.put("EQ_ASIAN_FIX", buildFixing(dataDate, "EQ_ASIAN_FIX", 98.0, 0.20));
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

    private EqSpot.EqSpotInfo buildEqCurve(LocalDate dataDate, String code, double s0, double s1y) {
        EqSpot.EqSpotInfo info = new EqSpot.EqSpotInfo();
        info.curveType = "EQ_SPOT";
        info.curveCode = code;
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, s0);
        info.curveData.put(365, s1y);
        return info;
    }

    private EqVol.EqVolInfo buildEqVol(LocalDate dataDate) {
        EqVol.EqVolInfo info = new EqVol.EqVolInfo();
        info.curveType = "EQ_VOL";
        info.curveCode = "EQVOL_IDX";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.curveData = new ArrayList<>();
        info.curveData.add(volPoint(180, 0.10, 0.260));
        info.curveData.add(volPoint(180, 0.25, 0.240));
        info.curveData.add(volPoint(180, 0.50, 0.220));
        info.curveData.add(volPoint(180, 0.75, 0.230));
        info.curveData.add(volPoint(180, 0.90, 0.250));
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

    private FxSpot.FxSpotInfo buildFxSpot(LocalDate dataDate) {
        FxSpot.FxSpotInfo info = new FxSpot.FxSpotInfo();
        info.curveType = "FX_SPOT";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.curveData = new HashMap<>();
        info.curveData.put("USD/CNY", 7.20);
        return info;
    }

    private Fixing.FixingInfo buildFixing(LocalDate dataDate, String fixingId, double start, double step) {
        Fixing.FixingInfo info = new Fixing.FixingInfo();
        info.curveType = "FIXING";
        info.fixingId = fixingId;
        info.dataDate = dataDate;
        info.pDataDate = dataDate.minusDays(30);
        info.interpolateType = "forward";
        info.curveData = new Series<>(LocalDate.class, Double.class);
        for (int i = 0; i <= 40; i++) {
            LocalDate d = dataDate.minusDays(30).plusDays(i);
            info.curveData.put(d, start + i * step);
        }
        return info;
    }
}
