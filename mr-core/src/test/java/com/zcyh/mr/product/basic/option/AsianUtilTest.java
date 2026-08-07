package com.zcyh.mr.product.basic.option;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

public class AsianUtilTest {

    @Test
    public void testDiscreteObservationFirstMoment() {
        double spot = 100.0;
        double rd = 0.03;
        double rf = 0.01;
        AsianUtil util = new AsianUtil(true, 100.0, rd, rf);

        AsianUtil.PriceResult result = util.price(
                spot,
                0.20,
                0.97,
                Arrays.asList(0.10, 0.40, 1.00),
                0,
                null);

        double expectedForward = spot * (
                Math.exp((rd - rf) * 0.10)
                        + Math.exp((rd - rf) * 0.40)
                        + Math.exp((rd - rf) * 1.00)) / 3.0;
        Assertions.assertEquals(expectedForward, result.forwardEq, 1e-12);
    }

    @Test
    public void testSingleFutureObservationEqualsBlackPrice() {
        double spot = 100.0;
        double strike = 102.0;
        double rd = 0.03;
        double rf = 0.01;
        double sigma = 0.20;
        double time = 0.50;
        double presentValueFactor = 0.98;
        AsianUtil util = new AsianUtil(true, strike, rd, rf);

        AsianUtil.PriceResult result = util.price(
                spot,
                sigma,
                presentValueFactor,
                Collections.singletonList(time),
                0,
                null);

        double forward = spot * Math.exp((rd - rf) * time);
        double sigmaTotal = sigma * Math.sqrt(time);
        double d1 = (Math.log(forward / strike) + 0.5 * sigmaTotal * sigmaTotal) / sigmaTotal;
        double d2 = d1 - sigmaTotal;
        double expected = presentValueFactor
                * (forward * EurOptUtil.cdf(d1) - strike * EurOptUtil.cdf(d2));
        Assertions.assertEquals(expected, result.price, 1e-10);
    }

    @Test
    public void testPastAverageAdjustsRemainingStrike() {
        AsianUtil util = new AsianUtil(true, 100.0, 0.03, 0.01);

        double strike = util.equivalentStrike(5, 2, 90.0);

        Assertions.assertEquals(106.66666666666667, strike, 1e-12);
    }

    @Test
    public void testHistoricalOnlyUsesDiscountedIntrinsicValue() {
        AsianUtil util = new AsianUtil(false, 100.0, 0.03, 0.01);

        AsianUtil.PriceResult result = util.price(
                105.0,
                0.20,
                0.97,
                Collections.emptyList(),
                4,
                96.0);

        Assertions.assertEquals(0.97 * 4.0, result.price, 1e-12);
        Assertions.assertEquals(1.0, result.pastWeight, 1e-12);
        Assertions.assertEquals(0.0, result.futureWeight, 1e-12);
    }
}
