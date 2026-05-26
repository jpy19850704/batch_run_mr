package com.zcyh.mr.product.basic.option;

import java.util.List;
import java.util.Map;

/**
 * 数字期权定价工具。
 * 使用 Call Spread 复制法：Digital(K) ≈ (Call(K-ε) - Call(K+ε)) / (2ε)，
 * sigma 统一使用 goalSeek(K) 获取的隐含波动率，ε 按 σ√t 自适应。
 * VV 启用时对 Call Spread 两端分别做 VV 修正再差分，保证量纲一致。
 * Greek 全部通过数值扰动 digitalCSWithVv() 计算，自动包含 VV。
 */
public class DigOptUtil extends OptUtil {

    private static final double MIN_SHIFT = 1e-6;
    private static final double MIN_TIME = 1e-8;

    private boolean call;
    private boolean cash;
    private double s;
    private double k;
    private double rebate;
    private double rd;
    private double rf;
    private double rebase;
    private double sigma;
    private double t;
    private double t2;
    private String model;

    private List<Map<String, Object>> vol;
    private boolean vvFlag;

    public DigOptUtil(boolean call, boolean cash, double s, double k, double rebate,
            double rd, double rf, double rebase, double t, double t2,
            List<Map<String, Object>> vol, double sigma, String model) {
        this(call, cash, s, k, rebate, rd, rf, rebase, t, t2, vol, sigma, model, false);
    }

    public DigOptUtil(boolean call, boolean cash, double s, double k, double rebate,
            double rd, double rf, double rebase, double t, double t2,
            List<Map<String, Object>> vol, double sigma, String model, boolean vvFlag) {
        this.call = call;
        this.cash = cash;
        this.s = s;
        this.k = k;
        this.rebate = rebate;
        this.rd = rd;
        this.rf = rf;
        this.rebase = rebase;
        this.t = t;
        this.t2 = t2;
        this.vol = vol;
        this.sigma = sigma;
        this.model = model;
        this.vvFlag = vvFlag;
    }

    // ---------- 定价入口 ----------

    /** 含 VV 的定价（当 vvFlag=true） */
    public double getValue() {
        return digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
    }

    /** 指定 sigma 的定价（场景估值，不含 VV） */
    public double getValue(double sigma) {
        return digitalCS(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
    }

    /** PV01 计算用 */
    public double getValue(double rd, double rf, double rebase, double sigma) {
        return digitalCS(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
    }

    public double getSigma() {
        return sigma;
    }

    public double getD2() {
        if (isBachelierModel()) {
            double f = cash ? s * Math.exp((rd - rf) * t) : s * Math.exp((rd - rf) * t2);
            return (f - k) / (sigma * Math.sqrt(t));
        }
        return (Math.log(s / k) + (rd - rf - 0.5 * sigma * sigma) * t) / (sigma * Math.sqrt(t));
    }

    // ---------- Call Spread 核心定价 ----------

    /**
     * Call Spread 复制法定价：Digital(K) ≈ rebate × (Call(K-ε) - Call(K+ε)) / (2ε) ×
     * 折现调整。
     * ε 按 K × σ × √t × 0.01 自适应，下限 K × 1e-4。
     */
    private double digitalCS(boolean call, double s, double k, double rebate,
            double rd, double rf, double rebase, double sigma, double t, double t2) {
        if (t <= 0 || sigma <= 0 || k <= 0) {
            double w = call ? 1 : -1;
            return w * (s - k) > 0 ? rebate * Math.exp(-rebase * t2) : 0.0;
        }
        double eps = computeEps(k, sigma, t);
        double kUp = k - eps;
        double kDown = k + eps;
        String pricingModel = pricingModel();
        double spread;
        if (call) {
            double callUp = BS(true, true, s, kUp, rd, rf, sigma, t, t2, pricingModel);
            double callDown = BS(true, true, s, kDown, rd, rf, sigma, t, t2, pricingModel);
            spread = (callUp - callDown) / (2 * eps);
        } else {
            double putUp = BS(false, true, s, kUp, rd, rf, sigma, t, t2, pricingModel);
            double putDown = BS(false, true, s, kDown, rd, rf, sigma, t, t2, pricingModel);
            spread = (putDown - putUp) / (2 * eps);
        }
        double dfAdj = Math.exp((-rebase + rd) * t2);
        return rebate * spread * dfAdj;
    }

    /**
     * 含 VV 的 Call Spread 定价。
     * VV 修正也按 Call Spread 差分：[VV(K-ε) - VV(K+ε)] / (2ε)，
     * 保证量纲与 Digital 价格一致。
     */
    private double digitalCSWithVv(boolean call, double s, double k, double rebate,
            double rd, double rf, double rebase, double sigma, double t, double t2) {
        double base = digitalCS(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (!vvFlag || t <= 0 || sigma <= 0 || k <= 0 || vol == null || vol.isEmpty()) {
            return base;
        }
        double eps = computeEps(k, sigma, t);
        double vvLo = VannaVolgaAdjuster.adjust(s, k - eps, rd, rf, sigma, t, vol, false, 1.0);
        double vvHi = VannaVolgaAdjuster.adjust(s, k + eps, rd, rf, sigma, t, vol, false, 1.0);
        double vvSpread = call ? (vvLo - vvHi) / (2 * eps) : (vvHi - vvLo) / (2 * eps);
        double dfAdj = Math.exp((-rebase + rd) * t2);
        return base + rebate * vvSpread * dfAdj;
    }

    /** ε 自适应计算：σ√t 比例，下限保护 */
    public static double computeEps(double k, double sigma, double t) {
        double eps = k * sigma * Math.sqrt(t) * 0.01;
        return Math.max(eps, k * 1e-4);
    }

    private boolean isBachelierModel() {
        return "bachelier".equalsIgnoreCase(model == null ? "" : model.trim());
    }

    private String pricingModel() {
        return isBachelierModel() ? "bachelier" : "black";
    }

    // ---------- Greeks（全部通过 digitalCSWithVv 扰动，自动含 VV） ----------

    public double Delta() {
        double shift = Math.max(MIN_SHIFT, Math.min(0.001, Math.abs(s) * 1e-4));
        double vUp = digitalCSWithVv(call, s + shift, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (s - shift <= 0) {
            double vMid = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
            return (vUp - vMid) / shift;
        }
        double vDown = digitalCSWithVv(call, s - shift, k, rebate, rd, rf, rebase, sigma, t, t2);
        return (vUp - vDown) / (2 * shift);
    }

    /** 绝对冲击的标准 Delta：∂V/∂S */
    public double Delta(double shift) {
        double absShift = Math.max(MIN_SHIFT, Math.abs(shift));
        double vUp = digitalCSWithVv(call, s + absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (s - absShift <= 0) {
            double vMid = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
            return (vUp - vMid) / absShift;
        }
        double vDown = digitalCSWithVv(call, s - absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        return (vUp - vDown) / (2 * absShift);
    }

    /** 比例冲击的标准 Delta：∂V/∂S，冲击量为 s×shift */
    public double DeltaTimes(double shift) {
        double absShift = Math.max(MIN_SHIFT, Math.abs(s * shift));
        double vUp = digitalCSWithVv(call, s + absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (s - absShift <= 0) {
            double vMid = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
            return (vUp - vMid) / absShift;
        }
        double vDown = digitalCSWithVv(call, s - absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        return (vUp - vDown) / (2 * absShift);
    }

    public double Gamma() {
        double shift = Math.max(MIN_SHIFT, Math.min(0.001, Math.abs(s) * 1e-4));
        double vMid = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (s - shift <= 0) {
            double vUp = digitalCSWithVv(call, s + shift, k, rebate, rd, rf, rebase, sigma, t, t2);
            double vUp2 = digitalCSWithVv(call, s + 2 * shift, k, rebate, rd, rf, rebase, sigma, t, t2);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vUp = digitalCSWithVv(call, s + shift, k, rebate, rd, rf, rebase, sigma, t, t2);
        double vDown = digitalCSWithVv(call, s - shift, k, rebate, rd, rf, rebase, sigma, t, t2);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    /** 绝对冲击的标准 Gamma：∂²V/∂S² */
    public double Gamma(double shift) {
        double absShift = Math.max(MIN_SHIFT, Math.abs(shift));
        double vMid = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (s - absShift <= 0) {
            double vUp = digitalCSWithVv(call, s + absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
            double vUp2 = digitalCSWithVv(call, s + 2 * absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
            return (vUp2 - 2 * vUp + vMid) / (absShift * absShift);
        }
        double vUp = digitalCSWithVv(call, s + absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        double vDown = digitalCSWithVv(call, s - absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        return (vUp - 2 * vMid + vDown) / (absShift * absShift);
    }

    /** 比例冲击的标准 Gamma：∂²V/∂S²，冲击量为 s×shift */
    public double GammaTimes(double shift) {
        double absShift = Math.max(MIN_SHIFT, Math.abs(s * shift));
        double vMid = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, t, t2);
        double vUp = digitalCSWithVv(call, s + absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        if (s - absShift <= 0) {
            double vUp2 = digitalCSWithVv(call, s + 2 * absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
            return (vUp2 - 2 * vUp + vMid) / (absShift * absShift);
        }
        double vDown = digitalCSWithVv(call, s - absShift, k, rebate, rd, rf, rebase, sigma, t, t2);
        return (vUp - 2 * vMid + vDown) / (absShift * absShift);
    }

    public double Theta() {
        double shiftDays = 1.0;
        double tUp = Math.max(MIN_TIME, (t * 365 - shiftDays) / 365.0);
        double tDown = Math.max(MIN_TIME, (t * 365 + shiftDays) / 365.0);
        double vUp = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, tUp, t2);
        double vDown = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma, tDown, t2);
        double daySpan = (tDown - tUp) * 365.0;
        if (daySpan <= 0) {
            daySpan = 1.0;
        }
        return (vUp - vDown) / daySpan;
    }

    public double DRho() {
        double shift = 0.001;
        double vUp, vDown;
        if (rd == rebase) {
            vUp = digitalCSWithVv(call, s, k, rebate, rd + shift, rf, rebase + shift, sigma, t, t2);
            vDown = digitalCSWithVv(call, s, k, rebate, rd - shift, rf, rebase - shift, sigma, t, t2);
        } else {
            vUp = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase + shift, sigma, t, t2);
            vDown = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase - shift, sigma, t, t2);
        }
        return (vUp - vDown) / (shift * 200);
    }

    public double FRho() {
        double shift = 0.001;
        double vUp = digitalCSWithVv(call, s, k, rebate, rd, rf + shift, rebase, sigma, t, t2);
        double vDown = digitalCSWithVv(call, s, k, rebate, rd, rf - shift, rebase, sigma, t, t2);
        return (vUp - vDown) / (shift * 200);
    }

    public double Vega() {
        double shift = 0.001;
        double vUp = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma + shift, t, t2);
        double vDown = digitalCSWithVv(call, s, k, rebate, rd, rf, rebase, sigma - shift, t, t2);
        return (vUp - vDown) / (shift * 200);
    }
}
