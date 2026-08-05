package com.zcyh.mr.marketdata;

import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.support.Series;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class IrSpotPreparedInterpolationTest {

    @Test
    void preparedInterpolator_whenUsingSupportedTypes_shouldMatchExistingInterpolation() {
        Series<Integer, Double> curve = buildCurve();
        String[] types = {"linear", "forward", "linervar", "log", "cubicspline", "unknown"};
        int[] points = {-10, 0, 45, 90, 200, 365, 800};

        for (String type : types) {
            Interpolation.PreparedInterpolator prepared = Interpolation.prepare(curve, type);
            for (int point : points) {
                double expected = Interpolation.interpolate(curve, point, type);
                Assertions.assertEquals(expected, prepared.interpolate(point), 1e-14,
                        () -> "type=" + type + ", point=" + point);
            }
        }
    }

    @Test
    void irSpot_whenUsingPreparedCurveAndShock_shouldKeepPricingResults() {
        LocalDate dataDate = LocalDate.of(2026, 1, 1);
        IrSpot.IrSpotInfo info = new IrSpot.IrSpotInfo();
        info.pDataDate = dataDate;
        info.dataDate = dataDate;
        info.freq = "cont";
        info.dayCount = "actual/365";
        info.interpolateType = "linear";
        info.curveData = buildCurve();
        info.shockCurveData.put(0, 0.001);
        info.shockCurveData.put(365, 0.002);

        LocalDate start = dataDate.plusDays(45);
        LocalDate end = dataDate.plusDays(200);
        double startRate = legacyRate(info, 45);
        double endRate = legacyRate(info, 200);
        double expectedStartDf = CurveFunc.discountFactor(
                dataDate, start, startRate, info.freq, info.dayCount);
        double expectedEndDf = CurveFunc.discountFactor(
                dataDate, end, endRate, info.freq, info.dayCount);
        double expectedFwdDf = expectedEndDf / expectedStartDf;
        double expectedFwdRate = CurveFunc.rateFromDiscountFactor(
                start, end, expectedFwdDf, info.freq, info.dayCount);

        IrSpot irSpot = new IrSpot(info);

        Assertions.assertEquals(startRate, irSpot.spotRate(start), 1e-14);
        Assertions.assertEquals(expectedStartDf, irSpot.discount(start), 1e-14);
        Assertions.assertEquals(expectedFwdDf, irSpot.fwdDiscount(start, end), 1e-14);
        Assertions.assertEquals(expectedFwdRate, irSpot.fwdRate(start, end), 1e-14);
    }

    private double legacyRate(IrSpot.IrSpotInfo info, int days) {
        double rate = Interpolation.interpolate(info.curveData, days, info.interpolateType);
        return rate + Interpolation.interpolate(info.shockCurveData, days, "linear");
    }

    private Series<Integer, Double> buildCurve() {
        Series<Integer, Double> curve = new Series<>(Integer.class, Double.class);
        curve.put(0, 0.010);
        curve.put(90, 0.015);
        curve.put(365, 0.022);
        curve.put(730, 0.028);
        return curve;
    }
}
