package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.support.Convert;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.VolUtil;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.List;
import java.util.Map;

/**
 * 美式期权定价工具（Bjerksund-Stensland 近似模型）
 * 支持 CALL 和 PUT，PUT 通过 Put-Call 转换实现
 */
public class AmOptUtil {
    private static final double RATE_EPS = 1e-12;
    private static final double VOL_EPS = 1e-12;

    private final boolean call;
    private final boolean cash;
    private final double s;
    private final double k;
    private final double rd;
    private final double rf;
    private double sigma;
    private final double maturityT;
    private final double settleT;
    private final double physicalDiscountFactor;
    private final double physicalForwardRatio;
    private final String volInterpolateType;

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double sigma, double maturityT, double settleT) {
        this(call, cash, s, k, rd, rf, sigma, maturityT, settleT,
                defaultPhysicalDiscountFactor(cash, rd, maturityT, settleT),
                defaultPhysicalForwardRatio(cash, rd, rf, maturityT, settleT));
    }

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double sigma, double maturityT, double settleT,
            double physicalDiscountFactor, double physicalForwardRatio) {
        this.call = call;
        this.cash = cash;
        this.s = s;
        this.k = k;
        this.rd = rd;
        this.rf = rf;
        this.sigma = sigma;
        this.maturityT = maturityT;
        this.settleT = settleT;
        this.physicalDiscountFactor = physicalDiscountFactor;
        this.physicalForwardRatio = physicalForwardRatio;
        this.volInterpolateType = null;
    }

    /**
     * 从波动率曲面迭代求解 sigma 的构造方式
     */
    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double maturityT, double settleT,
            List<Map<String, Object>> vol) {
        this(call, cash, s, k, rd, rf, maturityT, settleT,
                defaultPhysicalDiscountFactor(cash, rd, maturityT, settleT),
                defaultPhysicalForwardRatio(cash, rd, rf, maturityT, settleT), vol,
                VolUtil.requireAxis2InterpolateType(vol));
    }

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double maturityT, double settleT,
            double physicalDiscountFactor, double physicalForwardRatio,
            List<Map<String, Object>> vol) {
        this(call, cash, s, k, rd, rf, maturityT, settleT,
                physicalDiscountFactor, physicalForwardRatio, vol,
                VolUtil.requireAxis2InterpolateType(vol));
    }

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double maturityT, double settleT,
            List<Map<String, Object>> vol, String volInterpolateType) {
        this(call, cash, s, k, rd, rf, maturityT, settleT,
                defaultPhysicalDiscountFactor(cash, rd, maturityT, settleT),
                defaultPhysicalForwardRatio(cash, rd, rf, maturityT, settleT),
                vol, volInterpolateType);
    }

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double maturityT, double settleT,
            double physicalDiscountFactor, double physicalForwardRatio,
            List<Map<String, Object>> vol, String volInterpolateType) {
        this.call = call;
        this.cash = cash;
        this.s = s;
        this.k = k;
        this.rd = rd;
        this.rf = rf;
        this.maturityT = maturityT;
        this.settleT = settleT;
        this.physicalDiscountFactor = physicalDiscountFactor;
        this.physicalForwardRatio = physicalForwardRatio;
        this.volInterpolateType = VolUtil.normalizeAxis2InterpolateType(volInterpolateType);
        this.sigma = goalSeek(vol);
    }

    public double getValue() {
        return americanPrice(s, k, maturityT, rd, rf, sigma, call, cash, settleT);
    }

    public double getValue(double sigma) {
        return americanPrice(s, k, maturityT, rd, rf, sigma, call, cash, settleT);
    }

    /**
     * 现金结算美式 CALL 分段定价。
     */
    private double americanCallCash(double s, double k, double t, double r, double b, double sigma) {
        double intrinsic = Math.max(s - k, 0.0);
        if (t <= 0) {
            return intrinsic;
        }

        double europeanValue = europeanCallCash(s, k, t, r, b, sigma);
        if (b >= r - RATE_EPS) {
            return europeanValue;
        }
        if (sigma <= VOL_EPS) {
            return Math.max(intrinsic, europeanValue);
        }

        ExerciseBoundary boundary = calculateExerciseBoundary(k, t, r, b, sigma);
        if (boundary == null) {
            return Math.max(intrinsic, europeanValue);
        }
        if (s >= boundary.x) {
            return intrinsic;
        }

        double modelValue = bjerksundStenslandCall(s, k, t, r, b, sigma, boundary);
        return Double.isFinite(modelValue) ? Math.max(modelValue, intrinsic) : Math.max(intrinsic, europeanValue);
    }

    private double europeanCallCash(double s, double k, double t, double r, double b, double sigma) {
        if (t <= 0) {
            return Math.max(s - k, 0.0);
        }
        if (sigma <= VOL_EPS) {
            return Math.exp(-r * t) * Math.max(s * Math.exp(b * t) - k, 0.0);
        }
        double rootT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (b + 0.5 * sigma * sigma) * t) / (sigma * rootT);
        double d2 = d1 - sigma * rootT;
        return s * Math.exp((b - r) * t) * cdf(d1) - k * Math.exp(-r * t) * cdf(d2);
    }

    private ExerciseBoundary calculateExerciseBoundary(double k, double t, double r, double b, double sigma) {
        double sigma2 = sigma * sigma;
        double beta = (0.5 - b / sigma2)
                + Math.sqrt(Math.pow(b / sigma2 - 0.5, 2) + 2 * r / sigma2);
        if (!Double.isFinite(beta) || beta <= 1.0 + RATE_EPS) {
            return null;
        }

        double b0 = k * Math.max(1.0, r / (r - b));
        double bInf = beta * k / (beta - 1.0);
        double denominator = bInf - b0;
        if (!Double.isFinite(denominator) || Math.abs(denominator) <= RATE_EPS) {
            return null;
        }
        double h = -(b * t + 2 * sigma * Math.sqrt(t)) * b0 / denominator;
        double x = b0 + denominator * (1 - Math.exp(h));
        if (!Double.isFinite(x) || x <= 0) {
            return null;
        }
        return new ExerciseBoundary(beta, x);
    }

    private double bjerksundStenslandCall(double s, double k, double t, double r, double b,
            double sigma, ExerciseBoundary boundary) {
        double alpha = (boundary.x - k) * Math.pow(boundary.x, -boundary.beta);
        return alpha * Math.pow(s, boundary.beta)
                - alpha * phi(s, t, boundary.beta, boundary.x, boundary.x, b, sigma, r)
                + phi(s, t, 1.0, boundary.x, boundary.x, b, sigma, r)
                - phi(s, t, 1.0, k, boundary.x, b, sigma, r)
                - k * phi(s, t, 0.0, boundary.x, boundary.x, b, sigma, r)
                + k * phi(s, t, 0.0, k, boundary.x, b, sigma, r);
    }

    /**
     * 美式期权定价。实物交割先按远期比率调整执行价，再将结算价值折现回到期日。
     */
    private double americanPrice(double s, double k, double t, double rd, double rf,
            double sigma, boolean call, boolean cash, double t2) {
        if (cash) {
            if (call) {
                return americanCallCash(s, k, t, rd, rd - rf, sigma);
            }
            return americanCallCash(k, s, t, rf, rf - rd, sigma);
        }

        if (!Double.isFinite(physicalDiscountFactor) || physicalDiscountFactor <= 0.0
                || !Double.isFinite(physicalForwardRatio) || physicalForwardRatio <= 0.0) {
            throw new IllegalArgumentException("实物交割折现因子和远期比率必须为正有限数");
        }
        double adjustedStrike = k / physicalForwardRatio;
        double settlementAdjustment = physicalDiscountFactor * physicalForwardRatio;
        if (call) {
            return settlementAdjustment
                    * americanCallCash(s, adjustedStrike, t, rd, rd - rf, sigma);
        }
        return settlementAdjustment
                * americanCallCash(adjustedStrike, s, t, rf, rf - rd, sigma);
    }

    private static double defaultPhysicalDiscountFactor(boolean cash, double rd,
            double maturityT, double settleT) {
        return cash ? 1.0 : Math.exp(-rd * (settleT - maturityT));
    }

    private static double defaultPhysicalForwardRatio(boolean cash, double rd, double rf,
            double maturityT, double settleT) {
        return cash ? 1.0 : Math.exp((rd - rf) * (settleT - maturityT));
    }

    private static final class ExerciseBoundary {
        private final double beta;
        private final double x;

        private ExerciseBoundary(double beta, double x) {
            this.beta = beta;
            this.x = x;
        }
    }

    /**
     * Bjerksund-Stensland phi 函数
     */
    private double phi(double s, double t, double gamma, double h, double x,
            double b, double sigma, double r) {
        double kk = 2 * b / (sigma * sigma) + 2 * gamma - 1;
        double lmbda = (-r + b * gamma + 0.5 * gamma * (gamma - 1) * sigma * sigma) * t;
        double d = -(Math.log(s / h) + (b + (gamma - 0.5) * sigma * sigma) * t) / (sigma * Math.sqrt(t));
        return Math.exp(lmbda) * Math.pow(s, gamma)
                * (cdf(d) - Math.pow(x / s, kk) * cdf(d - 2 * Math.log(x / s) / (sigma * Math.sqrt(t))));
    }

    public static double cdf(double x) {
        NormalDistribution normalDistribution = new NormalDistribution();
        return normalDistribution.cumulativeProbability(x);
    }

    // ===== Greeks 希腊值 =====

    public double getDelta() {
        double eps = 0.001;
        return (getValue_s(s + eps) - getValue_s(s - eps)) / (2 * eps);
    }

    public double getGamma() {
        double eps = 0.001;
        return (getValue_s(s + eps) - 2 * getValue() + getValue_s(s - eps)) / (eps * eps);
    }

    public double getTheta() {
        double eps = 1.0 / 365;
        return americanPrice(s, k, maturityT + eps, rd, rf, sigma, call, cash, settleT + eps)
                - americanPrice(s, k, maturityT, rd, rf, sigma, call, cash, settleT);
    }

    public double getDRho() {
        double eps = 0.0001;
        return (americanPrice(s, k, maturityT, rd + eps, rf, sigma, call, cash, settleT)
                - americanPrice(s, k, maturityT, rd - eps, rf, sigma, call, cash, settleT)) / (200 * eps);
    }

    public double getFRho() {
        double eps = 0.0001;
        return (americanPrice(s, k, maturityT, rd, rf + eps, sigma, call, cash, settleT)
                - americanPrice(s, k, maturityT, rd, rf - eps, sigma, call, cash, settleT)) / (200 * eps);
    }

    public double getVega() {
        double eps = 0.001;
        return (americanPrice(s, k, maturityT, rd, rf, sigma + eps, call, cash, settleT)
                - americanPrice(s, k, maturityT, rd, rf, sigma - eps, call, cash, settleT)) / 2 / eps / 100;
    }

    public double getSigma() {
        return sigma;
    }

    // ===== 辅助方法 =====

    /** 仅变动 spot 价格求值 */
    private double getValue_s(double s) {
        return americanPrice(s, k, maturityT, rd, rf, sigma, call, cash, settleT);
    }

    /**
     * 迭代求解隐含波动率（Goal Seek）
     */
    public double goalSeek(List<Map<String, Object>> vol) {
        if (volInterpolateType == null || volInterpolateType.trim().isEmpty()) {
            throw new IllegalStateException("美式期权未通过波动率曲线构造，无法执行曲线插值");
        }
        double f = s * Math.exp((rd - rf) * maturityT);
        double pricingStrike = cash ? k : k / physicalForwardRatio;
        double deltainit = 0.5;
        double epsilon = 0.001;
        Double[] x1 = vol.stream().map(e -> Convert.toDouble(e.get("DELTA"))).toArray(Double[]::new);
        Double[] y1 = vol.stream().map(e -> Convert.toDouble(e.get("VOLATILITY_RATE"))).toArray(Double[]::new);
        double sig = Interpolation.interpolate(x1, y1, deltainit, volInterpolateType);
        double val = epsilon / (f / s);

        // 用欧式 BS delta 迭代（与 EurOptUtil 一致）
        double delta = calcBsDelta(s, pricingStrike, maturityT, rd, rf, sig,
                true, maturityT, val, epsilon, f);
        double diff = Math.abs(delta - deltainit);
        int i = 0;
        while (diff > 0.0001 && i < 50) {
            deltainit = delta;
            sig = Interpolation.interpolate(x1, y1, deltainit, volInterpolateType);
            delta = calcBsDelta(s, pricingStrike, maturityT, rd, rf, sig,
                    true, maturityT, val, epsilon, f);
            diff = Math.abs(delta - deltainit);
            i++;
        }
        return Interpolation.interpolate(x1, y1, delta, volInterpolateType);
    }

    /**
     * 用欧式 BS 公式计算 delta（Goal Seek 辅助）
     */
    private double calcBsDelta(double s, double k, double t, double rd, double rf,
            double sig, boolean cash, double t2,
            double val, double epsilon, double f) {
        double v1 = eurBs(s + val, k, t, rd, rf, sig, cash, t2);
        double v2 = eurBs(s - val, k, t, rd, rf, sig, cash, t2);
        return (v1 - v2) / (epsilon * 2) * f / s;
    }

    /**
     * 欧式 BS 公式（仅用于 goalSeek 的 delta 迭代，始终按 CALL 计算）
     */
    private double eurBs(double s, double k, double t, double rd, double rf,
            double sig, boolean cash, double t2) {
        double f = cash ? s * Math.exp((rd - rf) * t) : s * Math.exp((rd - rf) * t2);
        double varT = cash ? t : t2;
        if (t <= 0)
            return Math.max((f - k) * Math.exp(-rd * t2), 0);
        double d1 = (Math.log(f / k) + sig * sig / 2 * t) / (sig * Math.sqrt(t));
        double d2 = d1 - sig * Math.sqrt(t);
        return s * Math.exp(-rf * varT) * cdf(d1) - k * Math.exp(-rd * varT) * cdf(d2);
    }
}
