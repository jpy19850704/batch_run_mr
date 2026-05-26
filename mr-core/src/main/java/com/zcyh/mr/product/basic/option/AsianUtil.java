package com.zcyh.mr.product.basic.option;

/**
 * 亚式期权等效定价工具。
 * 说明：
 * 1. 历史部分均值由外部传入 averagePast；
 * 2. 未来部分采用对数正态矩匹配；
 * 3. 最终价格按历史/未来观测权重缩放。
 */
public class AsianUtil {

    private static final double EPS = 1e-12;

    private final boolean call;
    private final double strike;
    private final double rd;
    private final double rf;

    public AsianUtil(boolean call, double strike, double rd, double rf) {
        this.call = call;
        this.strike = strike;
        this.rd = rd;
        this.rf = rf;
    }

    public PriceResult price(
            double spot,
            double sigma,
            double settleT,
            double obsStartT,
            double obsEndT,
            int totalObsCount,
            int pastObsCount,
            Double averagePast) {
        PriceResult result = new PriceResult();
        int n = Math.max(totalObsCount, 1);
        int m = clampCount(pastObsCount, 0, n);
        int futureCount = n - m;
        double pastWeight = m / (double) n;
        double futureWeight = 1.0 - pastWeight;

        result.pastWeight = pastWeight;
        result.futureWeight = futureWeight;
        result.averagePast = averagePast;

        double discount = Math.exp(-rd * Math.max(settleT, 0.0));
        if (futureCount <= 0) {
            double avg = averagePast == null ? 0.0 : averagePast;
            double intrinsic = payoff(avg, strike);
            result.price = discount * intrinsic;
            result.forwardEq = avg;
            result.strikeEq = strike;
            result.sigmaEq = 0.0;
            return result;
        }

        double avgPast = averagePast == null ? 0.0 : averagePast;
        double strikeEq = (strike * n - avgPast * m) / (double) futureCount;
        result.strikeEq = strikeEq;

        double tStart = Math.max(obsStartT, 0.0);
        double tEnd = Math.max(obsEndT, 0.0);
        if (tEnd < tStart) {
            double tmp = tEnd;
            tEnd = tStart;
            tStart = tmp;
        }

        MomentPair momentPair = futureAverageMoments(spot, sigma, tStart, tEnd);
        double forwardEq = momentPair.firstMoment;
        result.forwardEq = forwardEq;

        if (!Double.isFinite(forwardEq) || forwardEq <= 0.0) {
            result.price = 0.0;
            result.sigmaEq = 0.0;
            return result;
        }

        if (strikeEq <= 0.0) {
            double optionFuture = call ? Math.max(forwardEq - strikeEq, 0.0) : 0.0;
            result.price = discount * futureWeight * optionFuture;
            result.sigmaEq = 0.0;
            return result;
        }

        double m2 = Math.max(momentPair.secondMoment, forwardEq * forwardEq * (1.0 + EPS));
        double sigmaTotal2 = Math.max(Math.log(m2 / (forwardEq * forwardEq)), 0.0);
        double sigmaTotal = Math.sqrt(sigmaTotal2);

        double optionFuture;
        if (sigmaTotal <= 1e-10) {
            optionFuture = payoff(forwardEq, strikeEq);
            result.d1Eq = null;
            result.d2Eq = null;
        } else {
            double d1 = (Math.log(forwardEq / strikeEq) + 0.5 * sigmaTotal2) / sigmaTotal;
            double d2 = d1 - sigmaTotal;
            double w = call ? 1.0 : -1.0;
            optionFuture = w * (forwardEq * EurOptUtil.cdf(w * d1) - strikeEq * EurOptUtil.cdf(w * d2));
            result.d1Eq = d1;
            result.d2Eq = d2;
        }

        result.price = discount * futureWeight * Math.max(optionFuture, 0.0);
        double sigmaHorizon = Math.max(tEnd, 1.0 / 365.0);
        result.sigmaEq = sigmaTotal / Math.sqrt(sigmaHorizon);
        return result;
    }

    private double payoff(double fwd, double k) {
        if (call) {
            return Math.max(fwd - k, 0.0);
        }
        return Math.max(k - fwd, 0.0);
    }

    private MomentPair futureAverageMoments(double spot, double sigma, double tStart, double tEnd) {
        double b = rd - rf;
        double tau = Math.max(tEnd - tStart, 0.0);
        if (tau <= 1e-10) {
            double mean = spot * Math.exp(b * tEnd);
            double second = spot * spot * Math.exp((2.0 * b + sigma * sigma) * tEnd);
            return new MomentPair(mean, second);
        }

        double mean = spot * Math.exp(b * tStart) * integralExp(b, tau) / tau;

        double secondFactor;
        double denom = b + sigma * sigma;
        if (Math.abs(denom) <= 1e-10) {
            secondFactor = secondMomentFactorByNumericIntegral(b, sigma, tau);
        } else {
            double term1 = integralExp(2.0 * b + sigma * sigma, tau);
            double term2 = integralExp(b, tau);
            secondFactor = 2.0 * (term1 - term2) / (tau * tau * denom);
            if (!Double.isFinite(secondFactor) || secondFactor <= 0.0) {
                secondFactor = secondMomentFactorByNumericIntegral(b, sigma, tau);
            }
        }
        double second = spot * spot * Math.exp((2.0 * b + sigma * sigma) * tStart) * secondFactor;
        if (!Double.isFinite(second) || second <= 0.0) {
            second = mean * mean;
        }
        return new MomentPair(mean, second);
    }

    private double secondMomentFactorByNumericIntegral(double b, double sigma, double tau) {
        int steps = 120;
        double du = tau / steps;
        double sum = 0.0;
        for (int i = 0; i < steps; i++) {
            double u = (i + 0.5) * du;
            for (int j = 0; j < steps; j++) {
                double v = (j + 0.5) * du;
                double exponent = b * (u + v) + sigma * sigma * Math.min(u, v);
                sum += Math.exp(exponent);
            }
        }
        return sum * du * du / (tau * tau);
    }

    private double integralExp(double a, double t) {
        if (Math.abs(a) <= 1e-10) {
            return t;
        }
        return Math.expm1(a * t) / a;
    }

    private int clampCount(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private static final class MomentPair {
        private final double firstMoment;
        private final double secondMoment;

        private MomentPair(double firstMoment, double secondMoment) {
            this.firstMoment = firstMoment;
            this.secondMoment = secondMoment;
        }
    }

    public static class PriceResult {
        public double price;
        public double sigmaEq;
        public Double d1Eq;
        public Double d2Eq;
        public double forwardEq;
        public double strikeEq;
        public double pastWeight;
        public double futureWeight;
        public Double averagePast;
    }
}

