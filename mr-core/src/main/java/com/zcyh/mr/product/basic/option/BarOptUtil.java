package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.marketdata.VolSurfacePoint;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.List;

public class BarOptUtil {
    private static final double MIN_SHIFT = 1e-6;
    private static final double MIN_TIME = 1e-8;
    private static final double VV_SIGMA_SHIFT = 1e-3;
    private static final double VV_SPOT_SHIFT_RATIO = 1e-4;
    private static final double MIN_POSITIVE = 1e-12;

    private double s;
    private double rebate;
    private double h;
    private double l;
    private double u;
    private double rd;
    private double rf;
    private double rebase;
    private double sigma;
    private double t;
    private String barrierDirection;
    private boolean knockout;
    private boolean barrierHit;
    private String type;

    /** VV 相关字段 */
    private boolean vvFlag;
    private List<VolSurfacePoint> volCurve;
    private double vvStrike;
    private boolean isDouble;

    /**
     * 主估值结果对象：只承载本次主路径需要输出到 detail 的中间量。
     */
    public static class PricingResult {
        public double value;
        public double baseValue;
        public double noTouchProb = Double.NaN;
        public double vvAdjustment = 0.0;
    }

    public BarOptUtil() {
    }

    public BarOptUtil(double s, double rebate, double h, double l, double u, double rd, double rf, double rebase,
            double sigma, double t, String barrierDirection, boolean knockout, boolean barrierHit, String type) {
        this(s, rebate, h, l, u, rd, rf, rebase, sigma, t, barrierDirection, knockout, barrierHit, type,
                false, null, 0.0, false);
    }

    /** 含 VV 参数的完整构造函数 */
    public BarOptUtil(double s, double rebate, double h, double l, double u, double rd, double rf, double rebase,
            double sigma, double t, String barrierDirection, boolean knockout, boolean barrierHit, String type,
            boolean vvFlag, List<VolSurfacePoint> volCurve, double vvStrike, boolean isDouble) {
        this.s = s;
        this.rebate = rebate;
        this.h = h;
        this.l = l;
        this.u = u;
        this.rd = rd;
        this.rf = rf;
        this.rebase = rebase;
        this.sigma = sigma;
        this.t = t;
        this.barrierDirection = barrierDirection;
        this.knockout = knockout;
        this.barrierHit = barrierHit;
        this.type = type;
        this.vvFlag = vvFlag;
        this.volCurve = volCurve;
        this.vvStrike = vvStrike;
        this.isDouble = isDouble;
    }
    /**
     * 含 VV 的定价（当 vvFlag=true 时自动叠加 VV）。
     */
    public double getValue(double rd, double rf, double rebase, double sigma) {
        return barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma, t);
    }

    /**
     * 含 VV 的定价入口。
     */
    public double getValue() {
        return evaluate().value;
    }

    /**
     * 指定 sigma 的定价（场景估值）。
     */
    public double getValue(double sigma) {
        return barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma, t);
    }

    /**
     * 返回主估值结果对象，供 detail 直接读取。
     */
    public PricingResult evaluate() {
        return evaluate(s, rebate, h, l, u, rd, rf, rebase, sigma, t);
    }

    public double getSigma() {
        return sigma;
    }
    public double cdf(double x) {
        NormalDistribution normalDistribution = new NormalDistribution();
        return normalDistribution.cumulativeProbability(x);
    }

    public double Barrier(double sigma) {
        return Barrier(s, rebate, h, l, u, rd, rf, rebase, sigma, t, barrierDirection, knockout, barrierHit);
    }

    public double Barrier() {
        double df = Math.exp(-rebase * t);
        if ("Single_Barrier".equalsIgnoreCase(type)) {
            if (barrierHit) {
                if (knockout)
                    return 0;
                else
                    return rebate * df;
            } else if (("Down".equalsIgnoreCase(barrierDirection) && s <= h && !knockout)
                    || ("Up".equalsIgnoreCase(barrierDirection) && s >= h && !knockout)) {
                return rebate * df;
            } else if (("Down".equalsIgnoreCase(barrierDirection) && s <= h && knockout)
                    || ("Up".equalsIgnoreCase(barrierDirection) && s >= h && knockout)) {
                return 0;
            } else {
                double phi = "Down".equalsIgnoreCase(barrierDirection) ? 1 : -1;
                double b = rd - rf;
                double F = s * Math.exp(b * t);
                double x2 = (Math.log(F / h) + 0.5 * sigma * sigma * t) / (sigma * Math.sqrt(t));
                double y2 = (Math.log(h / s) + (b + 0.5 * sigma * sigma) * t) / (sigma * Math.sqrt(t));
                double bb2 = df * cdf(phi * (x2 - sigma * Math.sqrt(t)));
                double bb4 = df * Math.pow((h / s), (2 * b / Math.pow(sigma, 2) - 1))
                        * cdf(phi * (y2 - sigma * Math.sqrt(t)));
                double value = rebate * (bb2 - bb4);
                if (!knockout)
                    value = df * rebate - value;
                return value;
            }
        } else if ("double_Barrier".equalsIgnoreCase(type)) {
            if (barrierHit) {
                if (knockout)
                    return 0;
                else
                    return rebate * df;
            } else if (!knockout && (s <= l || s >= u)) {
                return rebate * df;
            } else if (knockout && (s <= l || s >= u)) {
                return 0;
            } else {
                double b = rd - rf;
                double vt = sigma * Math.sqrt(t);
                double d1 = (Math.log(s / l) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d2 = (Math.log(s / u) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d3 = (Math.log(l * l / (s * l)) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d4 = (Math.log(l * l / (s * u)) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d5 = d1 - vt;
                double d6 = d2 - vt;
                double d7 = d3 - vt;
                double d8 = d4 - vt;
                double value = 0;
                for (int i = -15; i < 16; i++) {
                    double ddn = Math.log(Math.pow(u / l, 2 * i)) / vt;
                    double fn5 = Math.pow(u / l, i * (2 * b / (sigma * sigma) - 1));
                    double fn6 = Math.pow(((l / s) * Math.pow(u / l, i)), (2 * b / (sigma * sigma) - 1));
                    double g5 = fn5 * (cdf(d5 + ddn) - cdf(d6 + ddn));
                    double g6 = fn6 * (cdf(d7 + ddn) - cdf(d8 + ddn));
                    value = value + df * rebate * (g5 - g6);
                }
                if (!knockout)
                    value = df * rebate - value;
                return value;
            }
        } else {
            throw new RuntimeException("PRODUCT_CODE 不支持");
        }
    }

    public double Barrier(double s, double rebate, double h, double l, double u, double rd, double rf, double rebase,
            double sigma, double t, String barrierDirection, boolean knockout, boolean barrierHit) {
        double df = Math.exp(-rebase * t);
        if ("Single_Barrier".equalsIgnoreCase(type)) {
            if (barrierHit) {
                if (knockout)
                    return 0;
                else
                    return rebate * df;
            } else if (("Down".equalsIgnoreCase(barrierDirection) && s <= h && !knockout)
                    || ("Up".equalsIgnoreCase(barrierDirection) && s >= h && !knockout)) {
                return rebate * df;
            } else if (("Down".equalsIgnoreCase(barrierDirection) && s <= h && knockout)
                    || ("Up".equalsIgnoreCase(barrierDirection) && s >= h && knockout)) {
                return 0;
            } else {
                double phi = "Down".equalsIgnoreCase(barrierDirection) ? 1 : -1;
                double b = rd - rf;
                double F = s * Math.exp(b * t);
                double x2 = (Math.log(F / h) + 0.5 * sigma * sigma * t) / (sigma * Math.sqrt(t));
                double y2 = (Math.log(h / s) + (b + 0.5 * sigma * sigma) * t) / (sigma * Math.sqrt(t));
                double bb2 = df * cdf(phi * (x2 - sigma * Math.sqrt(t)));
                double bb4 = df * Math.pow((h / s), (2 * b / Math.pow(sigma, 2) - 1))
                        * cdf(phi * (y2 - sigma * Math.sqrt(t)));
                double value = rebate * (bb2 - bb4);
                if (!knockout)
                    value = df * rebate - value;
                return value;
            }
        } else if ("double_Barrier".equalsIgnoreCase(type)) {
            if (barrierHit) {
                if (knockout)
                    return 0;
                else
                    return rebate * df;
            } else if (!knockout && (s <= l || s >= u)) {
                return rebate * df;
            } else if (knockout && (s <= l || s >= u)) {
                return 0;
            } else {
                double b = rd - rf;
                double vt = sigma * Math.sqrt(t);
                double d1 = (Math.log(s / l) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d2 = (Math.log(s / u) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d3 = (Math.log(l * l / (s * l)) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d4 = (Math.log(l * l / (s * u)) + (b + 0.5 * sigma * sigma) * t) / vt;
                double d5 = d1 - vt;
                double d6 = d2 - vt;
                double d7 = d3 - vt;
                double d8 = d4 - vt;
                double value = 0;
                for (int i = -15; i < 16; i++) {
                    double ddn = Math.log(Math.pow(u / l, 2 * i)) / vt;
                    double fn5 = Math.pow(u / l, i * (2 * b / (sigma * sigma) - 1));
                    double fn6 = Math.pow(((l / s) * Math.pow(u / l, i)), (2 * b / (sigma * sigma) - 1));
                    double g5 = fn5 * (cdf(d5 + ddn) - cdf(d6 + ddn));
                    double g6 = fn6 * (cdf(d7 + ddn) - cdf(d8 + ddn));
                    value = value + df * rebate * (g5 - g6);
                }
                if (!knockout)
                    value = df * rebate - value;
                return value;
            }
        } else {
            throw new RuntimeException("PRODUCT_CODE 不支持");
        }
    }

    // ---------- 含 VV 的定价方法（用于 Greeks 扰动） ----------
    /**
     * Barrier 定价 + VV overhedge 调整。
     * 所有 Greeks 通过该方法数值扰动，自动含 VV。
     */
    private double barrierWithVv(double s, double rebate, double h, double l, double u,
            double rd, double rf, double rebase, double sigma, double t) {
        return evaluate(s, rebate, h, l, u, rd, rf, rebase, sigma, t).value;
    }

    /**
     * 返回主估值结果对象，避免 detail 再次走公共定价入口导致递归。
     */
    private PricingResult evaluate(double s, double rebate, double h, double l, double u,
            double rd, double rf, double rebase, double sigma, double t) {
        PricingResult result = new PricingResult();
        double base = Barrier(s, rebate, h, l, u, rd, rf, rebase, sigma, t, barrierDirection, knockout, barrierHit);
        result.baseValue = base;
        result.value = base;
        if (barrierHit) {
            result.noTouchProb = 0.0;
            return result;
        }
        double noTouchProb = noTouchProb(s, rd, rf, rebase, sigma, t, l, u, h, barrierDirection, type);
        result.noTouchProb = noTouchProb;
        if (!vvFlag || volCurve == null || volCurve.isEmpty() || t <= 0 || sigma <= 0) {
            return result;
        }
        double vvAdj = computeScaledVvAdjustment(
                s, vvStrike, h, l, u, rebate, rd, rf, rebase, sigma, t,
                barrierDirection, knockout, barrierHit, type, volCurve, isDouble, noTouchProb);
        if (!Double.isFinite(vvAdj)) {
            vvAdj = 0.0;
        }
        result.vvAdjustment = vvAdj;
        result.value = base + vvAdj;
        return result;
    }

    // ---------- Greeks（全部通过 barrierWithVv 扰动，自动含 VV） ----------

    public double Delta() {
        double shift = Math.max(MIN_SHIFT, Math.min(0.001, Math.abs(s) * 1e-4));
        double vUp = barrierWithVv(s + shift, rebate, h, l, u, rd, rf, rebase, sigma, t);
        if (s - shift <= 0) {
            double vMid = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma, t);
            return (vUp - vMid) / shift;
        }
        double vDown = barrierWithVv(s - shift, rebate, h, l, u, rd, rf, rebase, sigma, t);
        return (vUp - vDown) / (2 * shift);
    }

    public double Gamma() {
        double shift = Math.max(MIN_SHIFT, Math.min(0.001, Math.abs(s) * 1e-4));
        double vMid = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma, t);
        if (s - shift <= 0) {
            double vUp = barrierWithVv(s + shift, rebate, h, l, u, rd, rf, rebase, sigma, t);
            double vUp2 = barrierWithVv(s + 2 * shift, rebate, h, l, u, rd, rf, rebase, sigma, t);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vUp = barrierWithVv(s + shift, rebate, h, l, u, rd, rf, rebase, sigma, t);
        double vDown = barrierWithVv(s - shift, rebate, h, l, u, rd, rf, rebase, sigma, t);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    public double Theta() {
        double shiftDays = 1.0;
        double tUp = Math.max(MIN_TIME, (t * 365 - shiftDays) / 365.0);
        double tDown = Math.max(MIN_TIME, (t * 365 + shiftDays) / 365.0);
        double vUp = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma, tUp);
        double vDown = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma, tDown);
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
            vUp = barrierWithVv(s, rebate, h, l, u, rd + shift, rf, rebase + shift, sigma, t);
            vDown = barrierWithVv(s, rebate, h, l, u, rd - shift, rf, rebase - shift, sigma, t);
        } else {
            vUp = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase + shift, sigma, t);
            vDown = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase - shift, sigma, t);
        }
        return (vUp - vDown) / (2 * shift * 100);
    }

    public double FRho() {
        double shift = 0.001;
        double vUp = barrierWithVv(s, rebate, h, l, u, rd, rf + shift, rebase, sigma, t);
        double vDown = barrierWithVv(s, rebate, h, l, u, rd, rf - shift, rebase, sigma, t);
        return (vUp - vDown) / (2 * shift * 100);
    }

    public double Vega() {
        double shift = 0.001;
        double vUp = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma + shift, t);
        double vDown = barrierWithVv(s, rebate, h, l, u, rd, rf, rebase, sigma - shift, t);
        return (vUp - vDown) / (2 * shift * 100);
    }

    // ========== 存活概率 ==========

    /**
     * 基于当前实例参数计算障碍存活概率。
     * 利用 no-touch binary barrier 定价反推：p = V(rebate=1, KO=true) / df。
     */
    public double noTouchProb() {
        if (barrierHit) {
            return 0.0;
        }
        return noTouchProb(s, rd, rf, rebase, sigma, t, l, u, h, barrierDirection, type);
    }

    /**
     * 通用静态方法：计算障碍存活概率。
     * <ul>
     * <li>Double: 用双障碍 (lower, upper) 直接计算</li>
     * <li>Up（单障碍）: 下界取极小值 s×0.001</li>
     * <li>Down（单障碍）: 上界取极大值 s×1000</li>
     * </ul>
     *
     * @param s                标的即期价格
     * @param rd               本币利率
     * @param rf               外币利率
     * @param rebase           折现利率
     * @param sigma            波动率
     * @param t                到期时间（年化）
     * @param lower            下障碍（双障碍时使用）
     * @param upper            上障碍（双障碍时使用）
     * @param h                单障碍价格（单障碍时使用）
     * @param barrierDirection 障碍方向 Up/Down（单障碍时使用）
     * @param type             Single_Barrier 或 double_Barrier
     * @return 存活概率 [0, 1]
     */
    public static double noTouchProb(double s, double rd, double rf, double rebase,
            double sigma, double t, double lower, double upper, double h,
            String barrierDirection, String type) {
        if (!Double.isFinite(s) || s <= 0 || !Double.isFinite(t) || t <= 0) {
            return 0.0;
        }
        if (!Double.isFinite(sigma) || sigma <= 0) {
            sigma = 1e-6;
        }
        double df = Math.exp(-rebase * t);
        if (!Double.isFinite(df) || df <= 0) {
            return 0.0;
        }

        double value;
        if ("double_Barrier".equalsIgnoreCase(type)) {
            if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower <= 0 || upper <= 0 || lower >= upper) {
                return 0.0;
            }
            if (s <= lower || s >= upper) {
                return 0.0;
            }
            BarOptUtil util = new BarOptUtil(s, 1.0, 0.0, lower, upper,
                    rd, rf, rebase, sigma, t, "up", true, false, "double_Barrier");
            value = util.Barrier();
        } else {
            if ("Up".equalsIgnoreCase(barrierDirection)) {
                if (!Double.isFinite(h) || h <= 0) {
                    return 0.0;
                }
                if (s >= h) {
                    return 0.0;
                }
            } else if ("Down".equalsIgnoreCase(barrierDirection)) {
                if (!Double.isFinite(h) || h <= 0) {
                    return 0.0;
                }
                if (s <= h) {
                    return 0.0;
                }
            } else {
                return 0.0;
            }
            // 单障碍直接按单障碍 no-touch 口径估算，避免 pseudo double 在极值下数值溢出。
            BarOptUtil util = new BarOptUtil(s, 1.0, h, Double.NaN, Double.NaN,
                    rd, rf, rebase, sigma, t, barrierDirection, true, false, "Single_Barrier");
            value = util.Barrier();
        }
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        double p = value / df;
        if (!Double.isFinite(p)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, p));
    }

    /**
     * 计算障碍二元期权的 VV 缩放调整。
     * 采用障碍腿本体 Greeks（Vega/Vanna/Volga）直接做 VV 调整，不再使用 strike 两侧差分。
     */
    public static double computeScaledVvAdjustment(double s, double strike,
            double h, double l, double u, double rebate,
            double rd, double rf, double rebase, double sigma, double t,
            String barrierDirection, boolean knockout, boolean barrierHit, String type,
            List<VolSurfacePoint> volCurve,
            boolean isDouble, double noTouchProb) {
        if (!Double.isFinite(s) || s <= 0 || !Double.isFinite(strike) || strike <= 0
                || !Double.isFinite(rebate) || rebate == 0.0
                || !Double.isFinite(sigma) || sigma <= 0
                || !Double.isFinite(t) || t <= 0
                || volCurve == null || volCurve.isEmpty()) {
            return 0.0;
        }
        double[] greeks = calcBarrierLegGreeks(
                s, h, l, u, rebate, rd, rf, rebase, sigma, t,
                barrierDirection, knockout, barrierHit, type);
        if (greeks == null) {
            return 0.0;
        }
        double vvAdj = VannaVolgaAdjuster.adjustWithExoticGreeks(
                s, strike, rd, rf, sigma, t, volCurve, isDouble, noTouchProb,
                greeks[0], greeks[1], greeks[2]);
        if (!Double.isFinite(vvAdj)) {
            return 0.0;
        }
        return vvAdj;
    }

    /**
     * 障碍腿级 Greeks（Vega/Vanna/Volga）数值计算。
     */
    private static double[] calcBarrierLegGreeks(double s,
            double h, double l, double u, double rebate,
            double rd, double rf, double rebase, double sigma, double t,
            String barrierDirection, boolean knockout, boolean barrierHit, String type) {
        double sMid = Math.max(MIN_POSITIVE, s);
        double sigmaMid = Math.max(MIN_POSITIVE, sigma);

        double ds = Math.max(MIN_SHIFT, Math.abs(sMid) * VV_SPOT_SHIFT_RATIO);
        double sUp = sMid + ds;
        double sDown = Math.max(MIN_POSITIVE, sMid - ds);
        double dsEff = sUp - sDown;
        if (dsEff <= 0) {
            return null;
        }

        double dSigma = Math.min(VV_SIGMA_SHIFT, sigmaMid * 0.5);
        dSigma = Math.max(MIN_POSITIVE, dSigma);
        double sigmaUp = sigmaMid + dSigma;
        double sigmaDown = Math.max(MIN_POSITIVE, sigmaMid - dSigma);
        double dSigmaEff = sigmaUp - sigmaDown;
        if (dSigmaEff <= 0) {
            return null;
        }

        double vMid = priceBarrierLeg(sMid, h, l, u, rebate, rd, rf, rebase, sigmaMid, t,
                barrierDirection, knockout, barrierHit, type);
        double vSigmaUp = priceBarrierLeg(sMid, h, l, u, rebate, rd, rf, rebase, sigmaUp, t,
                barrierDirection, knockout, barrierHit, type);
        double vSigmaDown = priceBarrierLeg(sMid, h, l, u, rebate, rd, rf, rebase, sigmaDown, t,
                barrierDirection, knockout, barrierHit, type);
        if (!Double.isFinite(vMid) || !Double.isFinite(vSigmaUp) || !Double.isFinite(vSigmaDown)) {
            return null;
        }

        double vega = (vSigmaUp - vSigmaDown) / dSigmaEff;

        double halfSigma = dSigmaEff * 0.5;
        if (halfSigma <= 0) {
            return null;
        }
        double volga = (vSigmaUp - 2.0 * vMid + vSigmaDown) / (halfSigma * halfSigma);

        double vUpSigmaUp = priceBarrierLeg(sUp, h, l, u, rebate, rd, rf, rebase, sigmaUp, t,
                barrierDirection, knockout, barrierHit, type);
        double vUpSigmaDown = priceBarrierLeg(sUp, h, l, u, rebate, rd, rf, rebase, sigmaDown, t,
                barrierDirection, knockout, barrierHit, type);
        double vDownSigmaUp = priceBarrierLeg(sDown, h, l, u, rebate, rd, rf, rebase, sigmaUp, t,
                barrierDirection, knockout, barrierHit, type);
        double vDownSigmaDown = priceBarrierLeg(sDown, h, l, u, rebate, rd, rf, rebase, sigmaDown, t,
                barrierDirection, knockout, barrierHit, type);
        if (!Double.isFinite(vUpSigmaUp) || !Double.isFinite(vUpSigmaDown)
                || !Double.isFinite(vDownSigmaUp) || !Double.isFinite(vDownSigmaDown)) {
            return null;
        }
        double vanna = ((vUpSigmaUp - vUpSigmaDown) - (vDownSigmaUp - vDownSigmaDown)) / (dsEff * dSigmaEff);

        if (!Double.isFinite(vega) || !Double.isFinite(vanna) || !Double.isFinite(volga)) {
            return null;
        }
        return new double[] { vega, vanna, volga };
    }

    /**
     * 仅计算障碍腿本体价格（不含 VV），供腿级 Greeks 数值扰动使用。
     */
    private static double priceBarrierLeg(double s,
            double h, double l, double u, double rebate,
            double rd, double rf, double rebase, double sigma, double t,
            String barrierDirection, boolean knockout, boolean barrierHit, String type) {
        BarOptUtil util = new BarOptUtil(s, rebate, h, l, u, rd, rf, rebase, sigma, t,
                barrierDirection, knockout, barrierHit, type);
        double value = util.getValue();
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return value;
    }
}
