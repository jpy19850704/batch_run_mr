package com.zcyh.mr.product.basic.option;

/**
 * 欧式单边障碍期权工具。
 * 仅到期日检测障碍（非连续监控），定价基于 Reiner-Rubinstein 解析公式。
 * Greek 采用数值扰动法计算。
 */
public class EuroSingleUtil extends OptUtil {

    private static final double MIN_TIME = 1e-8;
    private static final double DEFAULT_SHIFT = 0.001;

    private double s;
    private double k;
    private double h;
    private double rd;
    private double rf;
    private double rds;
    private double sigma;
    private double t;
    private double ts;
    private String barrierDirection;
    private boolean knockout;
    private boolean callOption;

    public EuroSingleUtil(double s, double k, double h, double rd, double rf, double rds, double sigma, double t,
            double ts, String barrierDirection, boolean knockout, boolean callOption) {
        this.s = s;
        this.k = k;
        this.h = h;
        this.rd = rd;
        this.rf = rf;
        this.rds = rds;
        this.sigma = sigma;
        this.t = t;
        this.ts = ts;
        this.barrierDirection = barrierDirection;
        this.knockout = knockout;
        this.callOption = callOption;
    }

    // ---------- 定价 ----------

    public double getValue() {
        return priceCore(s, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
    }

    public double getValue(double sigma) {
        return priceCore(s, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
    }

    public double getValue(double rd, double rf, double rds, double sigma) {
        return priceCore(s, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
    }

    public double getSigma() {
        return sigma;
    }

    // ---------- Greeks 希腊值 ----------

    /**
     * Delta：标的价格相对变动 0.1% 的中心差分。
     */
    public double Delta() {
        double shift = s * DEFAULT_SHIFT;
        double vUp = priceCore(s + shift, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
        if (s - shift <= 0) {
            double vMid = getValue();
            return (vUp - vMid) / shift;
        }
        double vDown = priceCore(s - shift, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
        return (vUp - vDown) / (2 * shift);
    }

    /**
     * Gamma：标的价格相对变动 0.1% 的二阶差分。
     */
    public double Gamma() {
        double shift = s * DEFAULT_SHIFT;
        double vMid = getValue();
        if (s - shift <= 0) {
            double vUp = priceCore(s + shift, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
            double vUp2 = priceCore(s + 2 * shift, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout,
                    callOption);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vUp = priceCore(s + shift, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
        double vDown = priceCore(s - shift, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, knockout, callOption);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    /**
     * Theta：前向一天的价值变化。
     */
    public double Theta() {
        double shiftDays = 1.0;
        double tUp = Math.max(MIN_TIME, (t * 365 - shiftDays) / 365.0);
        double tDown = Math.max(MIN_TIME, (t * 365 + shiftDays) / 365.0);
        double vUp = priceCore(s, k, h, rd, rf, rds, sigma, tUp, ts, barrierDirection, knockout, callOption);
        double vDown = priceCore(s, k, h, rd, rf, rds, sigma, tDown, ts, barrierDirection, knockout, callOption);
        double daySpan = (tDown - tUp) * 365.0;
        if (daySpan <= 0)
            daySpan = 1.0;
        return (vUp - vDown) / daySpan;
    }

    /**
     * Vega：波动率扰动 0.1% 的中心差分，单位为 1% 波动率变化。
     */
    public double Vega() {
        double shift = 0.001;
        double vUp = priceCore(s, k, h, rd, rf, rds, sigma + shift, t, ts, barrierDirection, knockout, callOption);
        double vDown = priceCore(s, k, h, rd, rf, rds, sigma - shift, t, ts, barrierDirection, knockout, callOption);
        return (vUp - vDown) / (2 * shift * 100);
    }

    /**
     * DRho：本币利率敏感度。rd == rds 时联动扰动。
     */
    public double DRho() {
        double shift = 0.001;
        double vUp, vDown;
        if (rd == rds) {
            vUp = priceCore(s, k, h, rd + shift, rf, rds + shift, sigma, t, ts, barrierDirection, knockout, callOption);
            vDown = priceCore(s, k, h, rd - shift, rf, rds - shift, sigma, t, ts, barrierDirection, knockout,
                    callOption);
        } else {
            vUp = priceCore(s, k, h, rd, rf, rds + shift, sigma, t, ts, barrierDirection, knockout, callOption);
            vDown = priceCore(s, k, h, rd, rf, rds - shift, sigma, t, ts, barrierDirection, knockout, callOption);
        }
        return (vUp - vDown) / (2 * shift * 100);
    }

    /**
     * FRho：外币利率敏感度。
     */
    public double FRho() {
        double shift = 0.001;
        double vUp = priceCore(s, k, h, rd, rf + shift, rds, sigma, t, ts, barrierDirection, knockout, callOption);
        double vDown = priceCore(s, k, h, rd, rf - shift, rds, sigma, t, ts, barrierDirection, knockout, callOption);
        return (vUp - vDown) / (2 * shift * 100);
    }

    // ---------- 核心定价函数（全参数版） ----------

    /**
     * 欧式单边障碍期权解析定价。
     * knockout=true 时直接计算 KO 价格，knockout=false 时使用 KI = Vanilla - KO 平价关系。
     */
    private static double priceCore(double s, double k, double h,
            double rd, double rf, double rds, double sigma,
            double t, double ts, String barrierDirection, boolean knockout, boolean callOption) {
        if (knockout) {
            if (("down".equalsIgnoreCase(barrierDirection) && s <= h)
                    || ("up".equalsIgnoreCase(barrierDirection) && s >= h)) {
                return 0;
            }
            double xi = "down".equalsIgnoreCase(barrierDirection) ? 1 : -1;
            double phi = callOption ? 1 : -1;
            double f = s * Math.exp((rd - rf) * t);
            double b = Math.log(f / s) / t;
            double sst = sigma * Math.sqrt(t);
            double x1 = (Math.log(f / k) + 0.5 * Math.pow(sst, 2)) / sst;
            double x2 = (Math.log(f / h) + 0.5 * Math.pow(sst, 2)) / sst;
            double y1 = (Math.log(Math.pow(h, 2) / (k * s)) + b * t + 0.5 * Math.pow(sst, 2)) / sst;
            double y2 = (Math.log(h / s) + b * t + 0.5 * Math.pow(sst, 2)) / sst;
            double df = Math.exp(-rd * t);
            double p = 2 * b / Math.pow(sigma, 2);
            double b1 = df * phi * (f * cdf(phi * x1) - k * cdf(phi * (x1 - sst)));
            double b2 = df * phi * (f * cdf(phi * x2) - k * cdf(phi * (x2 - sst)));
            double b3 = df * phi
                    * (f * Math.pow(h / s, p + 1) * cdf(xi * y1) - k * Math.pow(h / s, p - 1) * cdf(xi * (y1 - sst)));
            double b4 = df * phi
                    * (f * Math.pow(h / s, p + 1) * cdf(xi * y2) - k * Math.pow(h / s, p - 1) * cdf(xi * (y2 - sst)));
            double dfs = Math.exp(-rds * (ts - t));
            if (phi * xi == 1) {
                if ((phi == 1 && k > h) || (phi == -1 && k < h))
                    return dfs * (b1 - b3);
                else
                    return dfs * (b2 - b4);
            } else {
                if ((phi == -1 && k > h) || (phi == 1 && k < h))
                    return dfs * (b1 - b2 + b3 - b4);
            }
            return 0;
        }
        return BS(callOption, true, s, k, rd, rf, sigma, t, ts, "black")
                - priceCore(s, k, h, rd, rf, rds, sigma, t, ts, barrierDirection, true, callOption);
    }
}
