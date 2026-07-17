package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.support.Convert;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.VolUtil;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.List;
import java.util.Map;

public class EurOptUtil {

    // Greeks 数值扰动步长
    public static final class GreekEps {
        public static final double delta = 0.001;
        public static final double gamma = 0.001;
        public static final double theta = 1 / 365.0;
        public static final double drho = 0.001;
        public static final double frho = 0.001;
        public static final double vega = 0.001;
    }

    private final boolean call;     // 看涨/看跌
    private final boolean cash;     // 现金/实物交割
    private final double s;         // 即期价格
    private final double k;         // 行权价
    private final double rd;        // 本币利率
    private final double rf;        // 外币利率
    private final double sigma;     // 隐含波动率
    private final double maturityT; // 到期年化期限
    private final double settleT;   // 交割年化期限
    private final String model;     // 定价模型: black / bachelier
    private final String volInterpolateType; // 波动率曲线插值类型
    private final List<Map<String, Object>> vol; // 波动率曲线

    public EurOptUtil(boolean call, boolean cash, double s, double k, double rd,
            double rf, double maturityT, double settleT, List<Map<String, Object>> vol, String model) {
        this(call, cash, s, k, rd, rf, maturityT, settleT, vol, model,
                VolUtil.requireAxis2InterpolateType(vol));
    }

    public EurOptUtil(boolean call, boolean cash, double s, double k, double rd,
            double rf, double maturityT, double settleT, List<Map<String, Object>> vol, String model,
            String volInterpolateType) {
        this.call = call;
        this.cash = cash;
        this.s = s;
        this.k = k;
        this.rd = rd;
        this.rf = rf;
        this.vol = vol;
        this.maturityT = maturityT;
        this.settleT = settleT;
        this.model = model;
        this.volInterpolateType = VolUtil.normalizeAxis2InterpolateType(volInterpolateType);
        this.sigma = goalSeek();
    }

    public static double cdf(double x) {
        NormalDistribution normalDistribution = new NormalDistribution();
        return normalDistribution.cumulativeProbability(x);
    }

    public static double pdf(double x) {
        NormalDistribution normalDistribution = new NormalDistribution();
        return normalDistribution.density(x);
    }

    /**
     * 统一欧式模型静态定价入口（参数按 EurOptUtil 口径）。
     */
    public static double priceByModel(boolean call, boolean cash, double s, double k,
            double rd, double rf, double sigma, double maturityT, double settleT, String model) {
        return bsFormula(call, cash, s, k, rd, rf, sigma, maturityT, settleT, model);
    }

    /**
     * 简化入口：直接对远期/现值口径变量定价（rd=rf=0, cash=true）。
     */
    public static double priceByModel(boolean call, double s, double k, double sigma,
            double maturityT, String model) {
        return bsFormula(call, true, s, k, 0, 0, sigma, maturityT, maturityT, model);
    }

    /**
     * 使用对象内置 sigma 定价。
     */
    public double getValue() {
        return bs(call, s, rd, rf, sigma);
    }

    /**
     * 使用外部给定 sigma 定价。
     */
    public double getValue(double sigma) {
        return bs(call, s, rd, rf, sigma);
    }

    /**
     * 使用传入波动率曲线先反解 sigma，再定价。
     */
    public double getValue(List<Map<String, Object>> vol) {
        double sigma = goalSeek(vol);
        return bs(call, s, rd, rf, sigma);
    }

    public double getDelta() {
        double eps = GreekEps.delta;
        return (bs(call, s + eps, rd, rf, sigma) - bs(call, s - eps, rd, rf, sigma)) / (2 * eps);
    }

    public double getGamma() {
        double eps = GreekEps.gamma;
        return (bs(call, s + eps, rd, rf, sigma) - 2 * bs(call, s, rd, rf, sigma) + bs(call, s - eps, rd, rf, sigma))
                / Math.pow(eps, 2);
    }

    /**
     * Theta 口径：前向一天的价值变化，即 V(t+1d) - V(t)。
     */
    public double getTheta() {
        double eps = GreekEps.theta;
        return bs(call, s, rd, rf, sigma, maturityT + eps, settleT + eps)
                - bs(call, s, rd, rf, sigma, maturityT, settleT);
    }

    /**
     * 通用远期价口径 Theta：在给定远期价下，按前向一天计算价值变化。
     */
    public double getThetaByFwdPrice(double f) {
        double eps = GreekEps.theta;
        double rfPlus, rfNow;
        double mtPlus = maturityT + eps;
        double stPlus = settleT + eps;
        if (cash) {
            rfPlus = rd - Math.log(f / s) / mtPlus;
            rfNow = rd - Math.log(f / s) / maturityT;
        } else {
            rfPlus = rd - Math.log(f / s) / stPlus;
            rfNow = rd - Math.log(f / s) / settleT;
        }
        return bs(call, s, rd, rfPlus, sigma, mtPlus, stPlus)
                - bs(call, s, rd, rfNow, sigma, maturityT, settleT);
    }

    public double getDRho() {
        double eps = GreekEps.drho;
        return (bs(call, s, rd + eps, rf, sigma) - bs(call, s, rd - eps, rf, sigma)) / (200 * eps);
    }

    public double getFRho() {
        double eps = GreekEps.frho;
        return (bs(call, s, rd, rf + eps, sigma) - bs(call, s, rd, rf - eps, sigma)) / (200 * eps);
    }

    public double getVega() {
        double eps = GreekEps.vega;
        return (bs(call, s, rd, rf, sigma + eps) - bs(call, s, rd, rf, sigma - eps)) / 2 / eps / 100;
    }

    public Double goalSeek() {
        return goalSeek(this.vol);
    }

    /**
     * 仅波动率曲线变化时反解 sigma。
     */
    public Double goalSeek(List<Map<String, Object>> vol) {
        return goalSeek(s, rd, rf, vol);
    }

    /**
     * 利率与波动率曲线同时变化时反解 sigma。
     */
    public Double goalSeek(double rd, double rf, List<Map<String, Object>> vol) {
        return goalSeek(s, rd, rf, vol);
    }

    /**
     * BS 定价函数，可显式传入到期与交割期限。
     */
    private double bs(boolean call, double s, double rd, double rf, double sigma, double maturityT, double settleT) {
        return bsFormula(call, cash, s, k, rd, rf, sigma, maturityT, settleT, model);
    }

    private static double bsFormula(boolean call, boolean cash, double s, double k,
            double rd, double rf, double sigma, double maturityT, double settleT, String model) {
        double w = call ? 1 : -1;
        double f = cash ? s * Math.exp((rd - rf) * maturityT) : s * Math.exp((rd - rf) * settleT);
        if (maturityT <= 0)
            return Math.max(w * (f - k) * Math.exp(-rd * settleT), 0);

        double variableT = cash ? maturityT : settleT;
        double d = (f - k) / (sigma * Math.sqrt(maturityT));
        switch (model.toLowerCase()) {
            case "black":
                double d1 = (Math.log(f / k) + Math.pow(sigma, 2) / 2 * maturityT) / (sigma * Math.sqrt(maturityT));
                double d2 = d1 - sigma * Math.sqrt(maturityT);
                return w * (s * Math.exp(-rf * variableT) * cdf(w * d1) - k * Math.exp(-rd * variableT) * cdf(w * d2));

            case "bachelier":
                return Math.exp(-rd * variableT) * (w * (f - k) * cdf(w * d) + sigma * Math.sqrt(maturityT) * pdf(d));
            default:
                return 0.0;
        }
    }

    private Double goalSeek(double s, double rd, double rf, List<Map<String, Object>> vol) {
        double f;
        if (cash)
            f = s * Math.exp((rd - rf) * maturityT);
        else
            f = s * Math.exp((rd - rf) * settleT);
        double deltainit = 0.5;
        double epsilon = 0.001;
        Double[] x1 = vol.stream().map(e -> Convert.toDouble(e.get("DELTA"))).toArray(Double[]::new);
        Double[] y1 = vol.stream().map(e -> Convert.toDouble(e.get("VOLATILITY_RATE"))).toArray(Double[]::new);
        double sigma = Interpolation.interpolate(x1, y1, deltainit, volInterpolateType);
        double val = epsilon / (f / s);

        double delta = (bs(true, s + val, rd, rf, sigma) - bs(true, s - val, rd, rf, sigma)) / (epsilon * 2) * f / s;
        double diff = Math.abs(delta - deltainit);
        int i = 0;
        while (diff > 0.0001 && i < 50) {
            deltainit = delta;
            sigma = Interpolation.interpolate(x1, y1, deltainit, volInterpolateType);
            delta = (bs(true, s + val, rd, rf, sigma) - bs(true, s - val, rd, rf, sigma)) / (epsilon * 2) * f / s;
            diff = Math.abs(delta - deltainit);
            i++;
        }
        sigma = Interpolation.interpolate(x1, y1, delta, volInterpolateType);
        return sigma;
    }

    private double bs(boolean call, double s, double rd, double rf, double sigma) {
        return bs(call, s, rd, rf, sigma, maturityT, settleT);
    }

    public double getSigma() {
        return sigma;
    }

    public double getRd() {
        return rd;
    }

    public double getRf() {
        return rf;
    }
}
