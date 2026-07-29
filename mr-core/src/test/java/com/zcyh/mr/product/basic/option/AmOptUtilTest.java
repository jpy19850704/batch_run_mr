package com.zcyh.mr.product.basic.option;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmOptUtilTest {

    @Test
    void cashCall_whenForeignRateIsNonPositive_shouldEqualEuropeanCall() {
        double american = price(true, 7.20, 7.15, 0.02, 0.0, 0.11, 0.5);
        double european = EurOptUtil.priceByModel(true, true, 7.20, 7.15, 0.02, 0.0, 0.11, 0.5, 0.5, "black");

        assertEquals(european, american, 1e-12);
    }

    @Test
    void cashCall_whenSpotExceedsExerciseBoundary_shouldEqualIntrinsicValue() {
        assertEquals(900.0, price(true, 1000.0, 100.0, 0.05, 0.02, 0.20, 1.0), 1e-12);
    }

    @Test
    void cashPut_shouldEqualTransformedAmericanCallWithoutParityAdjustment() {
        double put = price(false, 7.20, 7.15, 0.02, 0.045, 0.11, 0.5);
        double transformedCall = price(true, 7.15, 7.20, 0.045, 0.02, 0.11, 0.5);

        assertEquals(transformedCall, put, 1e-12);
        assertTrue(put >= 0.0);
    }

    @Test
    void cashPut_whenDomesticRateIsNonPositive_shouldEqualEuropeanPut() {
        double american = price(false, 7.20, 7.30, 0.0, 0.03, 0.11, 0.5);
        double european = EurOptUtil.priceByModel(false, true, 7.20, 7.30, 0.0, 0.03, 0.11, 0.5, 0.5, "black");

        assertEquals(european, american, 1e-12);
    }

    @Test
    void cashPricing_nearZeroRates_shouldRemainFiniteAndRespectIntrinsicValue() {
        double call = price(true, 7.20, 7.15, 1e-14, -1e-14, 0.11, 0.5);
        double put = price(false, 7.20, 7.30, -1e-14, 1e-14, 0.11, 0.5);

        assertTrue(Double.isFinite(call));
        assertTrue(Double.isFinite(put));
        assertTrue(call >= 0.05);
        assertTrue(put >= 0.10);
    }

    @Test
    void cashCall_normalApproximationRegion_shouldKeepExistingResult() {
        double value = price(
                true,
                7.20,
                7.15,
                0.020048648648648647,
                0.04503243243243243,
                0.10288795954458187,
                184.0 / 365.0);

        assertEquals(0.19347670746063983, value, 1e-12);
    }

    @Test
    void physicalPut_shouldUseAdjustedStrikeAndSettlementAdjustment() {
        double s = 7.20;
        double k = 7.30;
        double t = 0.5;
        double rd = 0.02;
        double rf = 0.03;
        double sigma = 0.11;
        double discountFactor = 0.998;
        double forwardRatio = 1.001;

        double physical = new AmOptUtil(false, false, s, k, rd, rf, sigma, t, 0.52,
                discountFactor, forwardRatio).getValue();
        double adjustedPut = price(false, s, k / forwardRatio, rd, rf, sigma, t);

        assertEquals(discountFactor * forwardRatio * adjustedPut, physical, 1e-12);
    }

    @Test
    void physicalCall_shouldUseAdjustedStrikeAndSettlementAdjustment() {
        double s = 7.20;
        double k = 7.15;
        double t = 0.5;
        double rd = 0.02;
        double rf = 0.045;
        double sigma = 0.11;
        double discountFactor = 0.998;
        double forwardRatio = 0.999;

        double physical = new AmOptUtil(true, false, s, k, rd, rf, sigma, t, 0.52,
                discountFactor, forwardRatio).getValue();
        double adjustedCall = price(true, s, k / forwardRatio, rd, rf, sigma, t);

        assertEquals(discountFactor * forwardRatio * adjustedCall, physical, 1e-12);
    }

    private double price(boolean call, double s, double k, double rd, double rf, double sigma, double t) {
        return new AmOptUtil(call, true, s, k, rd, rf, sigma, t, t).getValue();
    }
}
