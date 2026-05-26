package com.zcyh.mr.product.basic.willow;

public final class WillowAlphaCalibrator {
    private static final int MAX_ITERATIONS = 100;
    private static final double TOLERANCE = 1e-12;

    private WillowAlphaCalibrator() {
    }

    public static double calibrateNormal(double targetDiscount,
            double timeStepYear,
            double[] baseRates,
            double[] stateWeights) {
        return solve(targetDiscount, alpha -> impliedDiscountNormal(alpha, timeStepYear, baseRates, stateWeights));
    }

    public static double calibrateLogNormal(double targetDiscount,
            double timeStepYear,
            double[] baseLogRates,
            double[] stateWeights) {
        return solve(targetDiscount, alpha -> impliedDiscountLogNormal(alpha, timeStepYear, baseLogRates, stateWeights));
    }

    private static double solve(double targetDiscount, DiscountFunction function) {
        if (targetDiscount <= 0.0 || targetDiscount > 1.0 || !Double.isFinite(targetDiscount)) {
            throw new IllegalArgumentException("目标折现因子非法: " + targetDiscount);
        }
        double left = -1.0;
        double right = 1.0;
        double fLeft = function.value(left) - targetDiscount;
        double fRight = function.value(right) - targetDiscount;
        for (int i = 0; i < 20 && fLeft * fRight > 0.0; i++) {
            left *= 2.0;
            right *= 2.0;
            fLeft = function.value(left) - targetDiscount;
            fRight = function.value(right) - targetDiscount;
        }
        if (fLeft * fRight > 0.0) {
            throw new IllegalArgumentException("Willow Alpha校准无法形成根区间");
        }
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = 0.5 * (left + right);
            double fMid = function.value(mid) - targetDiscount;
            if (Math.abs(fMid) < TOLERANCE) {
                return mid;
            }
            if (fLeft * fMid <= 0.0) {
                right = mid;
                fRight = fMid;
            } else {
                left = mid;
                fLeft = fMid;
            }
            if (Math.abs(fRight - fLeft) < TOLERANCE) {
                return 0.5 * (left + right);
            }
        }
        return 0.5 * (left + right);
    }

    private static double impliedDiscountNormal(double alpha,
            double timeStepYear,
            double[] baseRates,
            double[] stateWeights) {
        validateCalibrationInputs(timeStepYear, baseRates, stateWeights);
        double sum = 0.0;
        for (int i = 0; i < baseRates.length; i++) {
            sum += stateWeights[i] / (1.0 + (baseRates[i] + alpha) * timeStepYear);
        }
        return sum;
    }

    private static double impliedDiscountLogNormal(double alpha,
            double timeStepYear,
            double[] baseLogRates,
            double[] stateWeights) {
        validateCalibrationInputs(timeStepYear, baseLogRates, stateWeights);
        double sum = 0.0;
        for (int i = 0; i < baseLogRates.length; i++) {
            double rate = Math.exp(baseLogRates[i] + alpha);
            sum += stateWeights[i] / (1.0 + rate * timeStepYear);
        }
        return sum;
    }

    private static void validateCalibrationInputs(double timeStepYear, double[] rates, double[] stateWeights) {
        if (timeStepYear <= 0.0 || !Double.isFinite(timeStepYear)) {
            throw new IllegalArgumentException("校准时间步长必须大于0");
        }
        if (rates == null || stateWeights == null || rates.length == 0 || rates.length != stateWeights.length) {
            throw new IllegalArgumentException("校准数组长度不一致");
        }
        double sum = 0.0;
        for (int i = 0; i < rates.length; i++) {
            if (!Double.isFinite(rates[i]) || !Double.isFinite(stateWeights[i])) {
                throw new IllegalArgumentException("校准数组包含非法数值: index=" + i);
            }
            if (stateWeights[i] < 0.0) {
                throw new IllegalArgumentException("状态权重不能为负: index=" + i);
            }
            sum += stateWeights[i];
        }
        if (Math.abs(sum - 1.0) > 1e-8) {
            throw new IllegalArgumentException("状态权重和必须为1: " + sum);
        }
    }

    private interface DiscountFunction {
        double value(double alpha);
    }
}
