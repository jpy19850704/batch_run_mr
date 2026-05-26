package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.marketdata.VolUtil;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vanna-Volga (VV) Overhedge 调整工具。
 * 使用 3 个 pillar（标准 Δ 期权）的 overhedge 成本，调整 BS 定价。
 *
 * 核心公式：VV_adj = p × Σ(wi × ci)
 * - ci = pillar i 的 BS overhedge 成本
 * - wi = 权重，由 exotic 的 Vega/Vanna/Volga 与 pillar 的比值确定
 * - p = 存活概率（非路径依赖产品 p=1.0）
 */
public class VannaVolgaAdjuster extends OptUtil {

    /** 可选 pillar delta 集合 */
    private static final double[] ALL_DELTAS = { 0.10, 0.25, 0.50, 0.75, 0.90 };

    /** 默认标准三元组 */
    private static final double[] DEFAULT_PILLARS = { 0.25, 0.50, 0.75 };

    // ========== 公共接口 ==========

    /**
     * 计算 VV overhedge 调整量。
     *
     * @param s           标的即期价格
     * @param k           期权行权价
     * @param rd          本币利率
     * @param rf          外币利率
     * @param sigma       goalSeek σ(K)
     * @param t           到期时间（年化）
     * @param volCur      波动率曲线
     * @param isDouble    是否双障碍
     * @param noTouchProb 存活（不触碰）概率；非路径依赖产品传 1.0
     * @return VV 调整量（正值加价，负值减价）
     */
    public static double adjust(double s, double k,
            double rd, double rf, double sigma, double t,
            List<Map<String, Object>> volCur,
            boolean isDouble, double noTouchProb) {
        return adjustInternal(s, k, rd, rf, sigma, t, volCur, isDouble, noTouchProb, null);
    }

    /**
     * 计算 VV overhedge 调整量（使用外部传入的 exotic Greeks）。
     * 适用于结构产品按腿独立计算 Vega/Vanna/Volga 的场景。
     *
     * @param exoticVega  exotic 腿 Vega（对 sigma 的一阶导，非每 1% 口径）
     * @param exoticVanna exotic 腿 Vanna
     * @param exoticVolga exotic 腿 Volga
     */
    public static double adjustWithExoticGreeks(double s, double k,
            double rd, double rf, double sigma, double t,
            List<Map<String, Object>> volCur,
            boolean isDouble, double noTouchProb,
            double exoticVega, double exoticVanna, double exoticVolga) {
        double[] exoticGreeks = new double[] { exoticVega, exoticVanna, exoticVolga };
        return adjustInternal(s, k, rd, rf, sigma, t, volCur, isDouble, noTouchProb, exoticGreeks);
    }

    /**
     * VV 调整核心实现。
     * 当 exoticGreeksOverride 为空时，使用 vanilla 解析 Greeks；
     * 当 exoticGreeksOverride 非空时，按调用方传入的腿级 Greeks 计算。
     */
    private static double adjustInternal(double s, double k,
            double rd, double rf, double sigma, double t,
            List<Map<String, Object>> volCur,
            boolean isDouble, double noTouchProb,
            double[] exoticGreeksOverride) {
        if (t <= 0 || sigma <= 0 || volCur == null || volCur.isEmpty()
                || !Double.isFinite(s) || !Double.isFinite(k) || s <= 0 || k <= 0) {
            return 0.0;
        }

        // 选取 pillar 三元组
        double[] pillars;
        if (isDouble) {
            pillars = DEFAULT_PILLARS;
        } else {
            double optDelta = computeBsDelta(s, k, rd, rf, sigma, t);
            pillars = selectPillars(optDelta);
        }

        // 从波动率曲线插值 3 个 pillar sigma
        double sigma1 = interpolateSigmaAtDelta(volCur, pillars[0]);
        double sigma2 = interpolateSigmaAtDelta(volCur, pillars[1]);
        double sigma3 = interpolateSigmaAtDelta(volCur, pillars[2]);

        // 以 exotic 自身的 sigma 作为统一参考波动率
        double sigmaRef = sigma;
        if (sigmaRef <= 0 || sigma1 <= 0 || sigma2 <= 0 || sigma3 <= 0) {
            return 0.0;
        }

        double fwd = s * Math.exp((rd - rf) * t);
        double k1 = getStrikeFromDelta(fwd, sigma1, t, pillars[0], rf, isAtmDelta(pillars[0]));
        double k2 = getStrikeFromDelta(fwd, sigma2, t, pillars[1], rf, isAtmDelta(pillars[1]));
        double k3 = getStrikeFromDelta(fwd, sigma3, t, pillars[2], rf, isAtmDelta(pillars[2]));
        if (!Double.isFinite(k1) || !Double.isFinite(k2) || !Double.isFinite(k3)
                || k1 <= 0 || k2 <= 0 || k3 <= 0) {
            return 0.0;
        }

        // 3 个 pillar 的 overhedge 成本：pillar strike 下 BS(σi) - BS(σ_ref)
        double c1 = vanillaBS(s, k1, rd, rf, sigma1, t) - vanillaBS(s, k1, rd, rf, sigmaRef, t);
        double c2 = vanillaBS(s, k2, rd, rf, sigma2, t) - vanillaBS(s, k2, rd, rf, sigmaRef, t);
        double c3 = vanillaBS(s, k3, rd, rf, sigma3, t) - vanillaBS(s, k3, rd, rf, sigmaRef, t);

        // exotic 与 pillar 的 [Vega, Vanna, Volga]
        double[] gE;
        if (exoticGreeksOverride == null) {
            gE = blackGreeksByStrike(s, k, rd, rf, sigmaRef, t);
        } else {
            if (exoticGreeksOverride.length != 3
                    || !Double.isFinite(exoticGreeksOverride[0])
                    || !Double.isFinite(exoticGreeksOverride[1])
                    || !Double.isFinite(exoticGreeksOverride[2])) {
                return 0.0;
            }
            gE = exoticGreeksOverride;
        }
        // pillar 的 BS Greeks 应使用各自 smile pillar 的 sigma，不能统一退回到 sigmaRef。
        double[] g1 = blackGreeksByStrike(s, k1, rd, rf, sigma1, t);
        double[] g2 = blackGreeksByStrike(s, k2, rd, rf, sigma2, t);
        double[] g3 = blackGreeksByStrike(s, k3, rd, rf, sigma3, t);
        if (gE == null || g1 == null || g2 == null || g3 == null) {
            return 0.0;
        }

        double[][] a = {
                { g1[0], g2[0], g3[0] },
                { g1[1], g2[1], g3[1] },
                { g1[2], g2[2], g3[2] }
        };
        double[] b = { gE[0], gE[1], gE[2] };
        double[] w = solve3x3(a, b);
        if (w == null) {
            return 0.0;
        }

        double vvAdj = w[0] * c1 + w[1] * c2 + w[2] * c3;

        // noTouch 概率异常时按 0 处理，避免异常输入放大 VV 调整。
        double p = Double.isFinite(noTouchProb) ? Math.max(0.0, Math.min(1.0, noTouchProb)) : 0.0;
        return p * vvAdj;
    }

    // ========== Pillar 选取 ==========

    /**
     * 根据期权 BS Delta 选取最近的 3 个 pillar。
     */
    public static double[] selectPillars(double optionDelta) {
        double absDelta = Math.abs(optionDelta);
        double distA = Math.abs(absDelta - 0.25);
        double distB = Math.abs(absDelta - 0.50);
        double distC = Math.abs(absDelta - 0.75);

        if (distA <= distB && distA <= distC)
            return new double[] { 0.10, 0.25, 0.50 };
        if (distC <= distB)
            return new double[] { 0.50, 0.75, 0.90 };
        return DEFAULT_PILLARS;
    }

    /**
     * 计算期权的 BS Spot Delta（0~1 范围）。
     */
    public static double computeBsDelta(double s, double k, double rd, double rf, double sigma, double t) {
        if (sigma <= 0 || t <= 0)
            return 0.5;
        double d = d1(s * Math.exp((rd - rf) * t), k, sigma, t);
        double spotDelta = Math.exp(-rf * t) * cdf(d);
        if (!Double.isFinite(spotDelta)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, spotDelta));
    }

    // ========== 内部工具 ==========

    /** 标准 d1 */
    private static double d1(double fwd, double k, double sigma, double t) {
        if (sigma <= 0 || t <= 0 || k <= 0 || fwd <= 0)
            return 0;
        return (Math.log(fwd / k) + 0.5 * sigma * sigma * t) / (sigma * Math.sqrt(t));
    }

    /** Vanilla BS (Call) 定价简化版 */
    private static double vanillaBS(double s, double k, double rd, double rf, double sigma, double t) {
        return BS(true, true, s, k, rd, rf, sigma, t, t, "black");
    }

    /**
     * 从波动率曲线按 Delta 插值 sigma。
     * 波动率曲线格式：[{DELTA: 0.25, VOLATILITY_RATE: 0.12}, ...]
     */
    public static double interpolateSigmaAtDelta(List<Map<String, Object>> volCur, double targetDelta) {
        if (volCur == null || volCur.isEmpty())
            return 0.0;

        TreeMap<Double, Double> points = new TreeMap<>();
        for (Map<String, Object> row : volCur) {
            double delta = toDouble(row.get("DELTA"));
            double vol = toDouble(row.get("VOLATILITY_RATE"));
            if (Double.isFinite(delta) && Double.isFinite(vol) && vol > 0.0) {
                points.put(delta, vol);
            }
        }
        if (points.isEmpty()) {
            return 0.0;
        }
        return Interpolation.interpolate(
                points.keySet().toArray(new Double[0]),
                points.values().toArray(new Double[0]),
                targetDelta,
                VolUtil.requireAxis2InterpolateType(volCur));
    }

    /**
     * 从 delta 反算 pillar strike。
     * 
     * @param isAtm true 时使用 ATM 公式 K = F × e^(0.5 σ² t)
     */
    private static double getStrikeFromDelta(double fwd, double sigma, double t,
            double delta, double rf, boolean isAtm) {
        if (isAtm || Math.abs(delta - 0.5) < 1e-10) {
            return fwd * Math.exp(0.5 * sigma * sigma * t);
        }
        double target = delta * Math.exp(rf * t);
        target = Math.max(1e-12, Math.min(1.0 - 1e-12, target));
        double sst = sigma * Math.sqrt(t);
        return fwd * Math.exp(-ppf(target) * sst + 0.5 * sigma * sigma * t);
    }

    private static boolean isAtmDelta(double delta) {
        return Math.abs(delta - 0.5) < 1e-10;
    }

    /**
     * Black 模型解析 Greek（Call 口径）。
     * 返回 [vega, vanna, volga]
     */
    private static double[] blackGreeksByStrike(double s, double k, double rd, double rf, double sigma, double t) {
        if (!(s > 0) || !(k > 0) || !(sigma > 0) || !(t > 0)) {
            return null;
        }
        double fwd = s * Math.exp((rd - rf) * t);
        double d1 = d1(fwd, k, sigma, t);
        double d2 = d1 - sigma * Math.sqrt(t);
        double dfFor = Math.exp(-rf * t);
        double vega = s * dfFor * pdf(d1) * Math.sqrt(t);
        if (!Double.isFinite(vega) || Math.abs(vega) < 1e-15) {
            return null;
        }
        double vanna = -dfFor * pdf(d1) * d2 / sigma;
        double volga = vega * d1 * d2 / sigma;
        if (!Double.isFinite(vanna) || !Double.isFinite(volga)) {
            return null;
        }
        return new double[] { vega, vanna, volga };
    }

    /**
     * 解 3x3 线性方程组 A*x=b（带部分选主元）。
     */
    private static double[] solve3x3(double[][] a, double[] b) {
        if (a == null || b == null || a.length != 3 || b.length != 3
                || a[0].length != 3 || a[1].length != 3 || a[2].length != 3) {
            return null;
        }
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            m[i][0] = a[i][0];
            m[i][1] = a[i][1];
            m[i][2] = a[i][2];
            m[i][3] = b[i];
        }

        for (int col = 0; col < 3; col++) {
            int pivot = col;
            double maxAbs = Math.abs(m[col][col]);
            for (int r = col + 1; r < 3; r++) {
                double v = Math.abs(m[r][col]);
                if (v > maxAbs) {
                    maxAbs = v;
                    pivot = r;
                }
            }
            if (maxAbs < 1e-15) {
                return null;
            }
            if (pivot != col) {
                double[] tmp = m[col];
                m[col] = m[pivot];
                m[pivot] = tmp;
            }

            double diag = m[col][col];
            for (int c = col; c < 4; c++) {
                m[col][c] /= diag;
            }
            for (int r = 0; r < 3; r++) {
                if (r == col) {
                    continue;
                }
                double factor = m[r][col];
                for (int c = col; c < 4; c++) {
                    m[r][c] -= factor * m[col][c];
                }
            }
        }

        return new double[] { m[0][3], m[1][3], m[2][3] };
    }

    private static double toDouble(Object v) {
        if (v == null)
            return 0.0;
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
