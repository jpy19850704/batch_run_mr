package com.zcyh.mr.product.basic.option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
            double presentValueFactor,
            List<Double> futureObservationTimes,
            int pastObsCount,
            Double averagePast) {
        PriceResult result = new PriceResult();
        List<Double> futureTimes = normalizeFutureTimes(futureObservationTimes);
        int futureCount = futureTimes.size();
        int m = Math.max(pastObsCount, 0);
        int n = m + futureCount;
        if (n <= 0) {
            throw new IllegalArgumentException("亚式期权观察日不能为空");
        }
        double pastWeight = m / (double) n;
        double futureWeight = 1.0 - pastWeight;

        result.pastWeight = pastWeight;
        result.futureWeight = futureWeight;
        result.averagePast = averagePast;

        if (!Double.isFinite(presentValueFactor) || presentValueFactor <= 0.0) {
            throw new IllegalArgumentException("亚式期权现值因子必须为正有限数");
        }
        if (futureCount <= 0) {
            double avg = averagePast == null ? 0.0 : averagePast;
            double intrinsic = payoff(avg, strike);
            result.price = presentValueFactor * intrinsic;
            result.forwardEq = avg;
            result.strikeEq = strike;
            result.sigmaEq = 0.0;
            return result;
        }

        double avgPast = averagePast == null ? 0.0 : averagePast;
        double strikeEq = (strike * n - avgPast * m) / (double) futureCount;
        result.strikeEq = strikeEq;

        MomentPair momentPair = futureAverageMoments(spot, sigma, futureTimes);
        double forwardEq = momentPair.firstMoment;
        result.forwardEq = forwardEq;

        if (!Double.isFinite(forwardEq) || forwardEq <= 0.0) {
            result.price = 0.0;
            result.sigmaEq = 0.0;
            return result;
        }

        if (strikeEq <= 0.0) {
            double optionFuture = call ? Math.max(forwardEq - strikeEq, 0.0) : 0.0;
            result.price = presentValueFactor * futureWeight * optionFuture;
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

        result.price = presentValueFactor * futureWeight * Math.max(optionFuture, 0.0);
        double sigmaHorizon = Math.max(futureTimes.get(futureTimes.size() - 1), 1.0 / 365.0);
        result.sigmaEq = sigmaTotal / Math.sqrt(sigmaHorizon);
        return result;
    }

    public double equivalentStrike(int totalObsCount, int pastObsCount, Double averagePast) {
        int n = Math.max(totalObsCount, 1);
        int m = Math.max(0, Math.min(pastObsCount, n));
        int futureCount = n - m;
        if (futureCount <= 0) {
            return strike;
        }
        double avgPast = averagePast == null ? 0.0 : averagePast;
        return (strike * n - avgPast * m) / futureCount;
    }

    private double payoff(double fwd, double k) {
        if (call) {
            return Math.max(fwd - k, 0.0);
        }
        return Math.max(k - fwd, 0.0);
    }

    private MomentPair futureAverageMoments(double spot, double sigma, List<Double> futureTimes) {
        double b = rd - rf;
        int n = futureTimes.size();
        double[] expBt = new double[n];
        double firstSum = 0.0;
        for (int i = 0; i < n; i++) {
            double t = futureTimes.get(i);
            expBt[i] = Math.exp(b * t);
            firstSum += expBt[i];
        }
        double mean = spot * firstSum / n;

        double secondSum = 0.0;
        double laterExpBt = firstSum;
        for (int i = 0; i < n; i++) {
            double t = futureTimes.get(i);
            laterExpBt -= expBt[i];
            secondSum += Math.exp((2.0 * b + sigma * sigma) * t);
            secondSum += 2.0 * Math.exp((b + sigma * sigma) * t) * laterExpBt;
        }
        double second = spot * spot * secondSum / (n * (double) n);
        if (!Double.isFinite(second) || second <= 0.0) {
            second = mean * mean;
        }
        return new MomentPair(mean, second);
    }

    private List<Double> normalizeFutureTimes(List<Double> futureObservationTimes) {
        if (futureObservationTimes == null || futureObservationTimes.isEmpty()) {
            return Collections.emptyList();
        }
        List<Double> result = new ArrayList<>(futureObservationTimes.size());
        for (Double time : futureObservationTimes) {
            if (time == null || !Double.isFinite(time) || time <= 0.0) {
                throw new IllegalArgumentException("未来观察期限必须为正有限数: " + time);
            }
            result.add(time);
        }
        Collections.sort(result);
        return result;
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
