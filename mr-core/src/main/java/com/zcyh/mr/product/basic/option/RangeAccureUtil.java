package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.support.Convert;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.VolUtil;
import com.zcyh.mr.marketdata.VolSurfacePoint;

import java.util.List;
import java.util.Map;

import static com.zcyh.mr.product.basic.option.EurOptUtil.cdf;
import static com.zcyh.mr.product.basic.option.EurOptUtil.pdf;

/**
 * 区间累计期权定价工具。
 * 基于 BS 模型的修正公式，考虑波动率微笑斜率(sigma1)对区间概率的影响。
 *
 * 使用模式（两阶段接口）：
 * 1. 构造实例（不触发 goalSeek）
 * 2a. 调用 calibrate() 进行 sigma 校准（FRTB 场景估值时使用）
 * 2b. 或调用 setSigmas() 注入已缓存的 sigma（Greeks 数值扰动时复用基准结果）
 * 3. 调用 getValue() / getDelta() 等进行定价和 Greeks 计算
 *
 * 若跳过步骤 2，首次调用估值/Greeks 方法时会自动触发 calibrate()（延迟校准）。
 */
public class RangeAccureUtil {

    // ===== 输入校验常量 =====
    private static final double MIN_TIME = 1e-12;
    private static final double MIN_PRICE = 1e-12;

    /** Greeks 数值扰动步长 */
    public static final class GreekEps {
        public static final double delta = 0.001;
        public static final double gamma = 0.001;
        public static final double theta = 1 / 365.0;
        public static final double drho = 0.001;
        public static final double frho = 0.001;
        public static final double vega = 0.001;
    }

    private boolean call; // 看涨(true)/看跌(false)
    private boolean cash; // 现金交割标志（区间累计固定为 true）
    private double s; // 标的即期价格
    private double k; // 行权价（上障碍或下障碍）
    private double rebate; // 每个观察日的应计收益金额
    private double rd; // 本币无风险利率
    private double rf; // 外币（标的）无风险利率
    private double rebase; // 折现基准利率（通常等于 rd 或独立的基准曲线利率）
    private double sigma; // 隐含波动率（通过 goalSeek 校准）
    private double t; // 观察日年化期限（dataDate → obsDate）
    private double t2; // 交割年化期限（通常与 t 相同）
    private double sigma1; // 波动率微笑斜率（dSigma/dK，中心差分近似）
    private double df; // 到期日折现因子
    private double df1; // 观察日折现因子（用于 deltaK 缩放）
    private double f; // 远期价格（由调用方根据标的类型计算传入）
    private List<VolSurfacePoint> vol; // 波动率曲线数据
    private String model; // 定价模型，只有 bachelier 走 normal 口径，其它按 black

    private boolean calibrated = false; // sigma 是否已校准

    /**
     * 构造区间累计定价工具。不自动触发 sigma 校准，由调用方显式调用 calibrate() 或 setSigmas()。
     *
     * @param call   看涨(true)/看跌(false)
     * @param cash   现金交割标志
     * @param s      标的即期价格
     * @param k      行权价（上障碍或下障碍）
     * @param rebate 每个观察日的应计收益金额
     * @param rd     本币无风险利率
     * @param rf     外币无风险利率
     * @param rebase 折现基准利率
     * @param t      观察日年化期限
     * @param t2     交割年化期限
     * @param df     到期日折现因子
     * @param df1    观察日折现因子
     * @param f      远期价格
     * @param vol    波动率曲线
     */
    public RangeAccureUtil(boolean call, boolean cash, double s, double k, double rebate, double rd, double rf,
            double rebase, double t, double t2, double df, double df1, double f, List<VolSurfacePoint> vol) {
        this(call, cash, s, k, rebate, rd, rf, rebase, t, t2, df, df1, f, vol, "black");
    }

    public RangeAccureUtil(boolean call, boolean cash, double s, double k, double rebate, double rd, double rf,
            double rebase, double t, double t2, double df, double df1, double f, List<VolSurfacePoint> vol,
            String model) {
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
        this.df = df;
        this.df1 = df1;
        this.f = f;
        this.vol = vol;
        this.model = model;
        validateInputs();
    }

    /** 输入校验：防止后续公式中出现 NaN / Infinity */
    private void validateInputs() {
        if (t <= MIN_TIME) {
            throw new IllegalArgumentException("年化期限 t 必须大于 0: " + t);
        }
        if (k <= MIN_PRICE) {
            throw new IllegalArgumentException("行权价 k 必须大于 0: " + k);
        }
        if (f <= MIN_PRICE) {
            throw new IllegalArgumentException("远期价 f 必须大于 0: " + f);
        }
    }

    // ===== 两阶段接口：校准 + 估值 =====

    /**
     * 阶段 1：校准 sigma（通过 goalSeek 迭代求解隐含波动率）。
     * FRTB 场景估值时，市场数据变化需重新校准。
     */
    public void calibrate() {
        initSigma();
        calibrated = true;
    }

    /**
     * 注入已缓存的 sigma，跳过 goalSeek。
     * 非 FRTB 场景下（如 Greeks 数值扰动）复用基准校准结果。
     */
    public void setSigmas(double[] sigmas) {
        this.sigma = sigmas[0];
        this.sigma1 = sigmas[1];
        calibrated = true;
    }

    /** 延迟校准：若调用方未显式校准，则首次使用时自动触发 */
    private void ensureCalibrated() {
        if (!calibrated) {
            calibrate();
        }
    }

    /** 获取校准后的 sigma 数组 [sigma, sigma1] */
    public double[] getSigmas() {
        ensureCalibrated();
        return new double[] { sigma, sigma1 };
    }

    /**
     * 通过中心差分近似计算 sigma 和 sigma1（波动率微笑斜率）。
     * 内部创建 3 个 EurOptUtil 实例分别在 k±deltaK 和 k 处进行 goalSeek。
     */
    private void initSigma() {
        if (isBachelierModel()) {
            this.sigma = interpolateAtmVol(vol);
            this.sigma1 = 0.0;
            return;
        }
        double deltaK = 0.01 * f * df1;
        EurOptUtil eurUtil1 = new EurOptUtil(call, cash, s, k + deltaK, rd, rf, t, t2, vol, "black");
        EurOptUtil eurUtil2 = new EurOptUtil(call, cash, s, k - deltaK, rd, rf, t, t2, vol, "black");
        EurOptUtil eurUtil3 = new EurOptUtil(call, cash, s, k, rd, rf, t, t2, vol, "black");
        this.sigma = eurUtil3.getSigma();
        this.sigma1 = (eurUtil1.getSigma() - eurUtil2.getSigma()) / 2 / deltaK;
    }

    /** 计算 d2 参数（标准正态分布位置） */
    public double getD2() {
        ensureCalibrated();
        if (isBachelierModel()) {
            return (f - k) / (sigma * Math.sqrt(t));
        }
        return (Math.log(f / k) + (-(sigma * sigma) / 2) * t) / (sigma * Math.sqrt(t));
    }

    // ===== 估值方法 =====

    /** 使用校准 sigma 定价 */
    public double getValue() {
        ensureCalibrated();
        return bs(f, k, df, sigma, sigma1, t, rebate);
    }

    /** 使用外部传入 sigma 数组定价（不触发校准） */
    public double getValue(double[] sigmas) {
        return bs(f, k, df, sigmas[0], sigmas[1], t, rebate);
    }

    /** 使用外部传入 sigma 定价（sigma1 仍用校准值） */
    public double getValue(double sigma) {
        return bs(f, k, df, sigma, sigma1, t, rebate);
    }

    /**
     * 区间累计核心定价公式：
     * V = rebate × df × [Φ(d2) - k × φ(d2) × √t × σ']
     * 其中 σ' 为波动率微笑斜率（sigma1）。
     */
    public double bs(double f, double k, double df, double sigma, double sigma1, double t, double rebate) {
        if (isBachelierModel()) {
            double d = (f - k) / (sigma * Math.sqrt(t));
            return rebate * df * (call ? cdf(d) : cdf(-d));
        }
        double d2 = (Math.log(f / k) + (-(sigma * sigma) / 2) * t) / (sigma * Math.sqrt(t));
        return rebate * df * (cdf(d2) - k * pdf(d2) * Math.sqrt(t) * sigma1);
    }

    private boolean isBachelierModel() {
        return "bachelier".equalsIgnoreCase(model == null ? "" : model.trim());
    }

    private double interpolateAtmVol(List<VolSurfacePoint> volCur) {
        if (volCur == null || volCur.isEmpty()) {
            throw new IllegalArgumentException("bachelier 模型缺少波动率曲线");
        }
        Double[] deltas = volCur.stream()
                .map(VolSurfacePoint::getAxis2Value)
                .toArray(Double[]::new);
        Double[] vols = volCur.stream()
                .map(VolSurfacePoint::getVolatilityRate)
                .toArray(Double[]::new);
        double atmVol = Interpolation.interpolate(deltas, vols, 0.5, VolUtil.requireAxis2InterpolateType(volCur));
        if (!Double.isFinite(atmVol) || atmVol <= 0.0) {
            throw new IllegalArgumentException("bachelier 模型 ATM 波动率无效: " + atmVol);
        }
        return atmVol;
    }

    // ========== Greeks（使用缓存 sigma，避免重复 goalSeek） ==========

    // ---------- IR 口径：直接偏移 f ----------

    /** Delta（IR 口径）：标准绝对冲击 */
    public double getDelta() {
        ensureCalibrated();
        double shift = GreekEps.delta;
        double vUp = bs(f + shift, k, df, sigma, sigma1, t, rebate);
        if (s - shift < 0) {
            // 即期价不足以支撑下冲击，仅用上侧单边差分
            double vBase = bs(f, k, df, sigma, sigma1, t, rebate);
            return (vUp - vBase) / shift;
        }
        double vDown = bs(f - shift, k, df, sigma, sigma1, t, rebate);
        return (vUp - vDown) / (2 * shift);
    }

    /** Delta（IR 口径）：指定绝对冲击 */
    public double getDelta(double shift) {
        ensureCalibrated();
        double vUp = bs(f + shift, k, df, sigma, sigma1, t, rebate);
        if (s - shift < 0) {
            double vBase = bs(f, k, df, sigma, sigma1, t, rebate);
            return (vUp - vBase) / shift;
        }
        double vDown = bs(f - shift, k, df, sigma, sigma1, t, rebate);
        return (vUp - vDown) / (2 * shift);
    }

    /** Gamma（IR 口径）：标准绝对冲击 */
    public double getGamma() {
        ensureCalibrated();
        double shift = GreekEps.gamma;
        double vMid = bs(f, k, df, sigma, sigma1, t, rebate);
        double vUp = bs(f + shift, k, df, sigma, sigma1, t, rebate);
        if (s - shift < 0) {
            // 单侧前向二阶差分：(f(x+2h) - 2f(x+h) + f(x)) / h²
            double vUp2 = bs(f + 2 * shift, k, df, sigma, sigma1, t, rebate);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vDown = bs(f - shift, k, df, sigma, sigma1, t, rebate);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    /** Gamma（IR 口径）：指定绝对冲击 */
    public double getGamma(double shift) {
        ensureCalibrated();
        double vMid = bs(f, k, df, sigma, sigma1, t, rebate);
        double vUp = bs(f + shift, k, df, sigma, sigma1, t, rebate);
        if (s - shift < 0) {
            double vUp2 = bs(f + 2 * shift, k, df, sigma, sigma1, t, rebate);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vDown = bs(f - shift, k, df, sigma, sigma1, t, rebate);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    /** Delta（IR 口径）：比例冲击 s × shift */
    public double getDeltaTimes(double shift) {
        ensureCalibrated();
        double absShift = s * shift;
        double vUp = bs(f + absShift, k, df, sigma, sigma1, t, rebate);
        if (s - absShift < 0) {
            double vBase = bs(f, k, df, sigma, sigma1, t, rebate);
            return (vUp - vBase) / (s * shift);
        }
        double vDown = bs(f - absShift, k, df, sigma, sigma1, t, rebate);
        return (vUp - vDown) / (2 * s * shift);
    }

    /** Gamma（IR 口径）：比例冲击 s × shift */
    public double getGammaTimes(double shift) {
        ensureCalibrated();
        double absShift = s * shift;
        double vMid = bs(f, k, df, sigma, sigma1, t, rebate);
        double vUp = bs(f + absShift, k, df, sigma, sigma1, t, rebate);
        if (s - absShift < 0) {
            double vUp2 = bs(f + 2 * absShift, k, df, sigma, sigma1, t, rebate);
            return (vUp2 - 2 * vUp + vMid) / (absShift * absShift);
        }
        double vDown = bs(f - absShift, k, df, sigma, sigma1, t, rebate);
        return (vUp - 2 * vMid + vDown) / (absShift * absShift);
    }

    // ---------- FX/Comm 口径：偏移 f 时按 carry 调整 ----------

    /** Delta（FX/Comm 口径）：绝对冲击，远期按 carry 调整 */
    public double getDeltaFx(double shift) {
        ensureCalibrated();
        double carry = Math.exp((rd - rf) * t);
        double vUp = bs(f + shift * carry, k, df, sigma, sigma1, t, rebate);
        if (s - shift < 0) {
            double vBase = bs(f, k, df, sigma, sigma1, t, rebate);
            return (vUp - vBase) / shift;
        }
        double vDown = bs(f - shift * carry, k, df, sigma, sigma1, t, rebate);
        return (vUp - vDown) / (2 * shift);
    }

    /** Gamma（FX/Comm 口径）：绝对冲击，远期按 carry 调整 */
    public double getGammaFx(double shift) {
        ensureCalibrated();
        double carry = Math.exp((rd - rf) * t);
        double vMid = bs(f, k, df, sigma, sigma1, t, rebate);
        double vUp = bs(f + shift * carry, k, df, sigma, sigma1, t, rebate);
        if (s - shift < 0) {
            double vUp2 = bs(f + 2 * shift * carry, k, df, sigma, sigma1, t, rebate);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vDown = bs(f - shift * carry, k, df, sigma, sigma1, t, rebate);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    /** Delta（FX/Comm 口径）：比例冲击 s × shift，远期按 carry 调整 */
    public double getDeltaTimesFx(double shift) {
        ensureCalibrated();
        double carry = Math.exp((rd - rf) * t);
        double absShift = s * shift;
        double vUp = bs(f + absShift * carry, k, df, sigma, sigma1, t, rebate);
        if (s - absShift < 0) {
            double vBase = bs(f, k, df, sigma, sigma1, t, rebate);
            return (vUp - vBase) / (s * shift);
        }
        double vDown = bs(f - absShift * carry, k, df, sigma, sigma1, t, rebate);
        return (vUp - vDown) / (2 * s * shift);
    }

    /** Gamma（FX/Comm 口径）：比例冲击 s × shift，远期按 carry 调整 */
    public double getGammaTimesFx(double shift) {
        ensureCalibrated();
        double carry = Math.exp((rd - rf) * t);
        double absShift = s * shift;
        double vMid = bs(f, k, df, sigma, sigma1, t, rebate);
        double vUp = bs(f + absShift * carry, k, df, sigma, sigma1, t, rebate);
        if (s - absShift < 0) {
            double vUp2 = bs(f + 2 * absShift * carry, k, df, sigma, sigma1, t, rebate);
            return (vUp2 - 2 * vUp + vMid) / (absShift * absShift);
        }
        double vDown = bs(f - absShift * carry, k, df, sigma, sigma1, t, rebate);
        return (vUp - 2 * vMid + vDown) / (absShift * absShift);
    }

    // ---------- Theta 时间敏感性 ----------

    /** Theta：前向一天的价值变化（per-day） */
    public double getTheta() {
        ensureCalibrated();
        double shift = GreekEps.theta;
        double vTmr = bs(f, k, df, sigma, sigma1, t - shift, rebate);
        double vBase = bs(f, k, df, sigma, sigma1, t, rebate);
        return vTmr - vBase;
    }

    // ---------- Rho（使用缓存 sigma，不重新 goalSeek） ----------

    /**
     * DRho：本币折现利率敏感性。
     * 仅扰动折现因子 df 捕获纯折现效应，冻结 sigma 和 f。
     */
    public double getDRho() {
        ensureCalibrated();
        double shift = GreekEps.drho;
        double dfUp = df * Math.exp(-shift * t);
        double dfDown = df * Math.exp(shift * t);
        double value_up = bs(f, k, dfUp, sigma, sigma1, t, rebate);
        double value_down = bs(f, k, dfDown, sigma, sigma1, t, rebate);
        return (value_up - value_down) / (shift * 200);
    }

    /**
     * FRho：外币利率敏感性。
     * 仅扰动远期价 f 捕获 carry 效应，冻结 sigma 和 df。
     */
    public double getFRho() {
        ensureCalibrated();
        double shift = GreekEps.frho;
        double fUp = f * Math.exp(-shift * t);
        double fDown = f * Math.exp(shift * t);
        double value_up = bs(fUp, k, df, sigma, sigma1, t, rebate);
        double value_down = bs(fDown, k, df, sigma, sigma1, t, rebate);
        return (value_up - value_down) / (shift * 200);
    }

    /** Vega：波动率敏感性 */
    public double getVega() {
        ensureCalibrated();
        double shift = GreekEps.vega;
        double value_up = getValue(sigma + shift);
        return (value_up - getValue()) / (shift * 100);
    }
}
