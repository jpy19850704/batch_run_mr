package com.zcyh.mr.product;

import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.comm.CommAsian;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

public class CommAsianTest {

    @Test
    public void testCommAsianCalcAndDetailFields() {
        LocalDate dataDate = LocalDate.of(2026, 1, 10);
        CommAsian.CommAsianInfo info = buildBaseInfo(dataDate);
        info.obsStartDate = LocalDate.of(2026, 1, 5);
        info.obsEndDate = LocalDate.of(2026, 1, 20);
        info.fixingId = "COMM_ASIAN_FIX";

        MarketData marketData = buildMarketData(dataDate);
        CommAsian product = new CommAsian(dataDate, info, marketData);
        CommAsian.CommAsianMeasure measure = product.calc();

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

    private CommAsian.CommAsianInfo buildBaseInfo(LocalDate dataDate) {
        CommAsian.CommAsianInfo info = new CommAsian.CommAsianInfo();
        info.productCode = EngineConstants.PRODUCT_CODE.COMM_ASIAN;
        info.instrumentId = "UT_COMM_ASIAN_001";
        info.callOrPut = "CALL";
        info.buyOrSell = "B";
        info.contractSize = 500.0;
        info.strikePrice = 510.0;
        info.maturityDate = dataDate.plusMonths(6);
        info.settleDate = dataDate.plusMonths(6).plusDays(2);
        info.currencyCode = "CNY";
        info.discountCurve = "IR_CNY";
        info.referenceCurve = "COMM_OIL";
        info.volatilitySurface = "COMMVOL_OIL";
        info.frtbCommBucket = "3";
        info.frtbCommAsset = "OIL";
        info.frtbCommLocation = "CN";
        info.underlyingCode = "SC";
        return info;
    }

    private MarketData buildMarketData(LocalDate dataDate) {
        MarketData marketData = new MarketData();
        marketData.irSpot.put("IR_CNY", buildIrCurve(dataDate, "IR_CNY", 0.021, 0.026));
        marketData.commSpot.put("COMM_OIL", buildCommCurve(dataDate, "COMM_OIL", 500.0, 530.0));
        marketData.commVol.put("COMMVOL_OIL", buildCommVol(dataDate));
        marketData.fxSpot = buildFxSpot(dataDate);
        marketData.fixingRate.put("COMM_ASIAN_FIX", buildFixing(dataDate, "COMM_ASIAN_FIX", 490.0, 0.50));
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

    private CommSpot.CommSpotInfo buildCommCurve(LocalDate dataDate, String code, double s0, double s1y) {
        CommSpot.CommSpotInfo info = new CommSpot.CommSpotInfo();
        info.curveType = "COMM_SPOT";
        info.curveCode = code;
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.interpolateType = "linear";
        info.curveData = new Series<>(Integer.class, Double.class);
        info.curveData.put(0, s0);
        info.curveData.put(365, s1y);
        info.shockCurveData = new Series<>(Integer.class, Double.class);
        return info;
    }

    private CommVol.CommVolInfo buildCommVol(LocalDate dataDate) {
        CommVol.CommVolInfo info = new CommVol.CommVolInfo();
        info.curveType = "COMM_VOL";
        info.curveCode = "COMMVOL_OIL";
        info.dataDate = dataDate;
        info.pDataDate = dataDate;
        info.curveData = new ArrayList<>();
        info.curveData.add(volPoint(180, 0.10, 0.300));
        info.curveData.add(volPoint(180, 0.25, 0.280));
        info.curveData.add(volPoint(180, 0.50, 0.260));
        info.curveData.add(volPoint(180, 0.75, 0.270));
        info.curveData.add(volPoint(180, 0.90, 0.290));
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
