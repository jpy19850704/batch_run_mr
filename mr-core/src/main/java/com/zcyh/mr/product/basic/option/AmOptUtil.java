package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.core.Convert;
import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.marketdata.VolUtil;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.List;
import java.util.Map;

/**
 * 美式期权定价工具（Bjerksund-Stensland 近似模型）
 * 支持 CALL 和 PUT，PUT 通过 Put-Call 转换实现
 */
public class AmOptUtil {
    private final boolean call;
    private final boolean cash;
    private final double s;
    private final double k;
    private final double rd;
    private final double rf;
    private double sigma;
    private final double maturityT;
    private final double settleT;
    private final String volInterpolateType;

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double sigma, double maturityT, double settleT) {
        this.call = call;
        this.cash = cash;
        this.s = s;
        this.k = k;
        this.rd = rd;
        this.rf = rf;
        this.sigma = sigma;
        this.maturityT = maturityT;
        this.settleT = settleT;
        this.volInterpolateType = null;
    }

    /**
     * 从波动率曲面迭代求解 sigma 的构造方式
     */
    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double maturityT, double settleT,
            List<Map<String, Object>> vol) {
        this(call, cash, s, k, rd, rf, maturityT, settleT, vol,
                VolUtil.requireAxis2InterpolateType(vol));
    }

    public AmOptUtil(boolean call, boolean cash, double s, double k,
            double rd, double rf, double maturityT, double settleT,
            List<Map<String, Object>> vol, String volInterpolateType) {
        this.call = call;
        this.cash = cash;
        this.s = s;
        this.k = k;
        this.rd = rd;
        this.rf = rf;
        this.maturityT = maturityT;
        this.settleT = settleT;
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
     * Bjerksund-Stensland CALL 近似
     */
    private double bsCall(double s, double k, double t, double rd, double rf,
            double sigma, boolean cash, double t2) {
        double f;
        if (cash) {
            f = s * Math.exp((rd - rf) * t);
        } else {
            f = s * Math.exp((rd - rf) * t2);
        }
        double b = Math.log(f / s) / t;
        double beta = (0.5 - b / (sigma * sigma))
                + Math.sqrt(Math.pow(b / (sigma * sigma) - 0.5, 2) + 2 * rd / (sigma * sigma));

        double b0 = k * Math.max(1, rd / (rd - b));
        double b00 = beta * k / (beta - 1);

        double h = -(b * t + 2 * sigma * Math.sqrt(t)) * b0 / (b00 - b0);
        double x = b0 + (b00 - b0) * (1 - Math.exp(h));
        double alpha = (x - k) * Math.pow(x, -beta);

        return alpha * Math.pow(s, beta)
                - alpha * phi(s, t, beta, x, x, b, sigma, rd)
                + phi(s, t, 1.0, x, x, b, sigma, rd)
                - phi(s, t, 1.0, k, x, b, sigma, rd)
                - k * phi(s, t, 0.0, x, x, b, sigma, rd)
                + k * phi(s, t, 0.0, k, x, b, sigma, rd);
    }

    /**
     * 美式期权定价（支持 CALL 和 PUT）
     * PUT 通过 Put-Call 转换：AmPut = AmCall(K,S,...) - S*exp(-rf*T) + K*exp(-rd*T)
     */
    private double americanPrice(double s, double k, double t, double rd, double rf,
            double sigma, boolean call, boolean cash, double t2) {
        if (t <= 0) {
            double f;
            if (cash) {
                f = s * Math.exp((rd - rf) * t);
            } else {
                f = s * Math.exp((rd - rf) * t2);
            }
            if (call) {
                return Math.max(f - k, 0) * Math.exp(-rd * t2);
            } else {
                return Math.max(k - f, 0) * Math.exp(-rd * t2);
            }
        }
        if (call) {
            return bsCall(s, k, t, rd, rf, sigma, cash, t2);
        } else {
            // Put-Call 转换：将 PUT 转为等价 CALL 问题
            // AmPut(S,K,rd,rf) = AmCall(K,S,rf,rd) - S*exp(-rf*T) + K*exp(-rd*T)
            double callVal = bsCall(k, s, t, rf, rd, sigma, cash, t2);
            double varT = cash ? t : t2;
            return callVal - s * Math.exp(-rf * varT) + k * Math.exp(-rd * varT);
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
        double f;
        if (cash) {
            f = s * Math.exp((rd - rf) * maturityT);
        } else {
            f = s * Math.exp((rd - rf) * settleT);
        }
        double deltainit = 0.5;
        double epsilon = 0.001;
        Double[] x1 = vol.stream().map(e -> Convert.toDouble(e.get("DELTA"))).toArray(Double[]::new);
        Double[] y1 = vol.stream().map(e -> Convert.toDouble(e.get("VOLATILITY_RATE"))).toArray(Double[]::new);
        double sig = Interpolation.interpolate(x1, y1, deltainit, volInterpolateType);
        double val = epsilon / (f / s);

        // 用欧式 BS delta 迭代（与 EurOptUtil 一致）
        double delta = calcBsDelta(s, k, maturityT, rd, rf, sig, cash, settleT, val, epsilon, f);
        double diff = Math.abs(delta - deltainit);
        int i = 0;
        while (diff > 0.0001 && i < 50) {
            deltainit = delta;
            sig = Interpolation.interpolate(x1, y1, deltainit, volInterpolateType);
            delta = calcBsDelta(s, k, maturityT, rd, rf, sig, cash, settleT, val, epsilon, f);
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
