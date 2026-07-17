package com.zcyh.mr.marketdata.curvegeneration.converter;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.CurveInput;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.DeltaTermVol;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration.IrCurve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 波动率 RRBF 转 Delta 网格（二阶 Vanna-Volga 闭式方法）
 *
 * 从市场报价 (ATM/RR/BF) 构建完整波动率微笑曲面：
 * 1. 线性分解提取三个支柱波动率 (25DP/ATM/25DC)
 * 2. 基于 Spot Delta 约定求解支柱执行价
 * 3. 预计算支柱点的 d1/d2（均在 σ_ATM 展开点）
 * 4. 对每个目标 delta，通过 K 空间 Newton-Raphson 迭代求解 σ-K 耦合
 * 5. VV 闭式解基于 BS 价格函数的二阶 Taylor 展开：
 * σ(K) = σ + [-σ + √(σ² + d₁d₂(2σP + Q))] / (d₁d₂)
 *
 * 参考: Castagna & Mercurio (2007) "Consistent Pricing of FX Options"
 */
public class VolRrbf2Delta {

        private static final Logger logger = LoggerFactory.getLogger(VolRrbf2Delta.class);

        /* ==================== 常量 ==================== */

        /** Delta 网格：-0.95 到 -0.05，步长 0.05（全部为 Put Delta） */
        private static final double[] DELTA_GRID = {
                        -0.95, -0.90, -0.85, -0.80, -0.75, -0.70, -0.65, -0.60, -0.55,
                        -0.50,
                        -0.45, -0.40, -0.35, -0.30, -0.25, -0.20, -0.15, -0.10, -0.05
        };
        /** 输出层 Delta 平移量（需要原始 Put Delta 时改为 0.0） */
        private static final double OUTPUT_DELTA_SHIFT = 1.0;

        /** Brent 最大迭代轮次 */
        private static final int BRENT_MAX_ITER = 80;
        /** Brent 根精度（x=ln(K/F)） */
        private static final double BRENT_X_TOL = 1e-10;
        /** 扫描点数 */
        private static final int SCAN_POINTS = 401;
        /** 扫描点数上限 */
        private static final int MAX_SCAN_POINTS = 1201;
        /** 扫描扩边次数 */
        private static final int MAX_SCAN_EXPAND = 2;
        /** 扫描扩边倍数 */
        private static final double SCAN_EXPAND_FACTOR = 1.5;
        /** 可达域 clip 保护 */
        private static final double DELTA_CLIP_EPS = 1e-4;
        /** 最小总方差 */
        private static final double MIN_TOTAL_VAR = 1e-10;
        /** 最小波动率 */
        private static final double MIN_SIGMA = 1e-6;
        /** 左右翼切换阈值（call delta） */
        private static final double DELTA_RIGHT_SWITCH = 0.15;
        private static final double DELTA_LEFT_SWITCH = 0.85;
        /** 切换带宽（x=ln(K/F)） */
        private static final double TAIL_TRANSITION_WIDTH = 0.40;
        /** 翼部斜率估计的有限差分步长 */
        private static final double TAIL_SLOPE_DX = 0.03;
        /** Brent 浮点保护 */
        private static final double BRENT_EPS = 2e-15;

        /** Delta 残差收敛阈值 */
        private static final double DELTA_TOL = 1e-10;
        /** log-moneyness 最小带宽 */
        private static final double LOG_MONEYNESS_MIN_BAND = 0.35;
        /** log-moneyness 最大带宽 */
        private static final double LOG_MONEYNESS_MAX_BAND = 1.20;
        /** 带宽系数 */
        private static final double LOG_MONEYNESS_BAND_MULTIPLIER = 6.0;
        /** 绝对下限 K/F */
        private static final double ABS_MIN_K_RATIO = 0.05;
        /** 绝对上限 K/F */
        private static final double ABS_MAX_K_RATIO = 3.00;

        private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

        /* ==================== 正态分布工具 ==================== */

        /** 标准正态概率密度函数 */
        private static double normalPdf(double x) {
                return Math.exp(-0.5 * x * x) / SQRT_2PI;
        }

        /**
         * 标准正态累积分布函数
         * Abramowitz & Stegun 近似，精度 |ε| < 7.5e-8
         */
        private static double normalCdf(double x) {
                if (x < -8.0)
                        return 0.0;
                if (x > 8.0)
                        return 1.0;
                double t = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
                double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937
                                + t * (-1.821255978 + t * 1.330274429))));
                double result = 1.0 - normalPdf(Math.abs(x)) * poly;
                return x >= 0 ? result : 1.0 - result;
        }

        /**
         * 标准正态逆函数 (Peter Acklam 算法)
         * 精度 |ε| < 1.15e-9
         */
        private static double normalCdfInverse(double p) {
                if (p <= 0)
                        return -8.0;
                if (p >= 1)
                        return 8.0;

                final double a1 = -3.969683028665376e+01, a2 = 2.209460984245205e+02;
                final double a3 = -2.759285104469687e+02, a4 = 1.383577518672690e+02;
                final double a5 = -3.066479806614716e+01, a6 = 2.506628277459239e+00;
                final double b1 = -5.447609879822406e+01, b2 = 1.615858368580409e+02;
                final double b3 = -1.556989798598866e+02, b4 = 6.680131188771972e+01;
                final double b5 = -1.328068155288572e+01;
                final double c1 = -7.784894002430293e-03, c2 = -3.223964580411365e-01;
                final double c3 = -2.400758277161838e+00, c4 = -2.549732539343734e+00;
                final double c5 = 4.374664141464968e+00, c6 = 2.938163982698783e+00;
                final double d1 = 7.784695709041462e-03, d2 = 3.224671290700398e-01;
                final double d3 = 2.445134137142996e+00, d4 = 3.754408661907416e+00;

                final double pLow = 0.02425, pHigh = 1.0 - pLow;
                double q, r;

                if (p < pLow) {
                        q = Math.sqrt(-2.0 * Math.log(p));
                        return (((((c1 * q + c2) * q + c3) * q + c4) * q + c5) * q + c6) /
                                        ((((d1 * q + d2) * q + d3) * q + d4) * q + 1.0);
                } else if (p <= pHigh) {
                        q = p - 0.5;
                        r = q * q;
                        return (((((a1 * r + a2) * r + a3) * r + a4) * r + a5) * r + a6) * q /
                                        (((((b1 * r + b2) * r + b3) * r + b4) * r + b5) * r + 1.0);
                } else {
                        q = Math.sqrt(-2.0 * Math.log(1.0 - p));
                        return -(((((c1 * q + c2) * q + c3) * q + c4) * q + c5) * q + c6) /
                                        ((((d1 * q + d2) * q + d3) * q + d4) * q + 1.0);
                }
        }

        /* ==================== Delta / Strike 转换 ==================== */

        /**
         * ATM Delta-Neutral Straddle 执行价
         * K_ATM = F × exp(σ²T/2)
         */
        private static double atmDnsStrike(double F, double T, double sigma) {
                return F * Math.exp(0.5 * sigma * sigma * T);
        }

        /**
         * 从绝对 Delta 值计算执行价（Spot Delta 约定）
         *
         * @param absDelta 绝对 Delta 值
         * @param F        远期价格
         * @param T        到期时间（年）
         * @param sigma    波动率
         * @param rf       外币连续利率
         * @param isCall   true=看涨，false=看跌
         */
        private static double deltaToStrike(double absDelta, double F, double T,
                        double sigma, double rf, boolean isCall) {
                double sqrtT = Math.sqrt(T);
                double dfF = Math.exp(-rf * T);
                if (dfF <= 0) {
                        return F;
                }
                double p = absDelta / dfF;
                p = clamp(p, 1e-12, 1.0 - 1e-12);
                double nd1;
                if (isCall) {
                        nd1 = normalCdfInverse(p);
                } else {
                        nd1 = -normalCdfInverse(p);
                }
                return F * Math.exp(-nd1 * sigma * sqrtT + 0.5 * sigma * sigma * T);
        }

        /**
         * BS Call Delta（Spot Delta 约定）
         * Δ_call = exp(-rf×T) × N(d₁)
         */
        private static double bsCallDelta(double F, double K, double T,
                        double rf, double sigma) {
                if (T <= 0 || sigma <= 0 || K <= 0)
                        return (F > K) ? 1.0 : 0.0;
                double sqrtT = Math.sqrt(T);
                double nd1 = (Math.log(F / K) + 0.5 * sigma * sigma * T) / (sigma * sqrtT);
                return Math.exp(-rf * T) * normalCdf(nd1);
        }

        /**
         * BS Put Delta（Spot Delta 约定）
         * Δ_put = exp(-rf×T) × (N(d₁) - 1)
         */
        private static double bsPutDelta(double F, double K, double T,
                        double rf, double sigma) {
                if (T <= 0 || sigma <= 0 || K <= 0)
                        return (F > K) ? 0.0 : -1.0;
                double sqrtT = Math.sqrt(T);
                double nd1 = (Math.log(F / K) + 0.5 * sigma * sigma * T) / (sigma * sqrtT);
                return Math.exp(-rf * T) * (normalCdf(nd1) - 1.0);
        }

        /* ==================== VV 核心逻辑 ==================== */

        /**
         * 将 RRBF 波动率转换为 Delta 网格波动率
         *
         * @param input     曲线输入（含 ATM_VOL/RR_VOL/BF_VOL）
         * @param curvePool 已生成的曲线池
         * @param calendar  日历对象
         * @return 19 × N 期限的微笑曲面数据
         */
        public List<DeltaTermVol> convert(CurveInput input,
                        Map<String, List<IrCurve>> curvePool,
                        Calendar calendar) {
                if (input.curveData == null || input.curveData.isEmpty()) {
                        return Collections.emptyList();
                }

                LocalDate dataDate = input.dataDate;
                String calName = input.calendar != null ? input.calendar : "";
                String interpolateType = input.getInterpolateType();
                double fxSpot = input.fxSpot != null ? input.fxSpot : 0;

                // 即期汇率必须为正值，否则远期汇率和 ln(F/K) 计算将产生 NaN
                if (fxSpot <= 0) {
                        return Collections.emptyList();
                }

                // 获取基准和标的折现曲线
                List<IrCurve> baseCurve = curvePool.getOrDefault(
                                input.baseDiscountCurve, Collections.emptyList());
                List<IrCurve> undCurve = curvePool.getOrDefault(
                                input.underlyingDiscountCurve, Collections.emptyList());
                if (baseCurve.isEmpty() || undCurve.isEmpty()) {
                        throw new IllegalArgumentException("缺少依赖曲线 BASE_DISCOUNT_CURVE=" + input.baseDiscountCurve
                                        + ", UNDERLYING_DISCOUNT_CURVE=" + input.underlyingDiscountCurve);
                }

                // 插值函数要求有序输入，按期限升序处理
                List<IrCurve> sortedBaseCurve = new ArrayList<>(baseCurve);
                sortedBaseCurve.sort(Comparator.comparingDouble(c -> c.termDays));
                List<IrCurve> sortedUndCurve = new ArrayList<>(undCurve);
                sortedUndCurve.sort(Comparator.comparingDouble(c -> c.termDays));

                Double[] baseTermDays = sortedBaseCurve.stream()
                                .map(c -> c.termDays).toArray(Double[]::new);
                Double[] baseRates = sortedBaseCurve.stream()
                                .map(c -> c.rate).toArray(Double[]::new);
                Double[] undTermDays = sortedUndCurve.stream()
                                .map(c -> c.termDays).toArray(Double[]::new);
                Double[] undRates = sortedUndCurve.stream()
                                .map(c -> c.rate).toArray(Double[]::new);

                List<DeltaTermVol> result = new ArrayList<>();
                SolveCounter totalCounter = new SolveCounter();

                for (JSONObject jo : input.curveData) {
                        String termCode = jo.getString("TERM_CODE");
                        double atmVol = jo.getDoubleValue("ATM_VOL");
                        double rrVol = jo.getDoubleValue("RR_VOL");
                        double bfVol = jo.getDoubleValue("BF_VOL");

                        LocalDate endDate = calendar.resolveTermDate(calName, dataDate, termCode);
                        double termDays = ChronoUnit.DAYS.between(dataDate, endDate);
                        double T = termDays / 365.0;
                        if (T <= 0)
                                continue;

                        // 插值利率并计算远期汇率
                        double rd = 0, rf = 0;
                        double forward = fxSpot;
                        if (baseTermDays.length > 0 && undTermDays.length > 0) {
                                rd = Interpolation.interpolate(baseTermDays, baseRates, termDays, interpolateType);
                                rf = Interpolation.interpolate(undTermDays, undRates, termDays, interpolateType);
                                forward = fxSpot * Math.exp((rd - rf) * T);
                        }

                        // 构建 VV 支柱、翼部参数和求解上下文
                        VVPillar pillar = buildPillar(forward, T, rf, atmVol, rrVol, bfVol);
                        TailParams tail = buildTailParams(forward, T, rf, atmVol, pillar);
                        double[] bounds = buildStrikeBounds(forward, T, atmVol);
                        double xMin = Math.log(bounds[0] / forward);
                        double xMax = Math.log(bounds[1] / forward);
                        double baseXMax = Math.max(Math.abs(xMin), Math.abs(xMax));
                        SmileContext ctx = new SmileContext(forward, T, rf, atmVol, pillar, tail, baseXMax);

                        // 对每个 delta 点求解
                        for (double delta : DELTA_GRID) {
                                SolveResult solved = solveForDelta(delta, ctx);
                                totalCounter.add(solved.status);

                                DeltaTermVol pt = new DeltaTermVol();
                                pt.curveId = input.curveId;
                                pt.dataDate = dataDate;
                                pt.termCode = termCode;
                                pt.termDays = termDays;
                                pt.termYear = T;
                                pt.delta = delta + OUTPUT_DELTA_SHIFT;
                                pt.fxVol = solved.sigma;
                                pt.fxForward = forward;
                                pt.strike = solved.strike;
                                result.add(pt);
                        }
                }
                if (totalCounter.nonSolvedCount() > 0) {
                        logger.info("VolRrbf2Delta {} 非标准求解统计: solved={}, extrapolated={}, clipped={}, fallback={}",
                                        input.curveId,
                                        totalCounter.solved,
                                        totalCounter.extrapolated,
                                        totalCounter.clipped,
                                        totalCounter.fallback);
                }
                return result;
        }

        /**
         * VV 支柱数据：每期限计算一次，复用于所有 delta 点
         */
        private static class VVPillar {
                double K1, K2, K3; // 25DP / ATM / 25DC 执行价
                double sig1, sig3; // 25DP / 25DC 波动率
                double lnK1, lnK2, lnK3;
                double d1K1, d2K1; // 支柱 d1,d2（在 σ_ATM 处）
                double d1K3, d2K3;
        }

        /**
         * 翼部外推参数（总方差线性外推）
         */
        private static class TailParams {
                double x0Left, x1Left;
                double x0Right, x1Right;
                double w0Left, w0Right;
                double slopeLeft, slopeRight;
        }

        /**
         * 单期限求解上下文
         */
        private static class SmileContext {
                final double forward;
                final double T;
                final double rf;
                final double sigmaATM;
                final VVPillar pillar;
                final TailParams tail;
                final double baseXMax;

                SmileContext(double forward, double t, double rf, double sigmaATM, VVPillar pillar,
                                TailParams tail, double baseXMax) {
                        this.forward = forward;
                        this.T = t;
                        this.rf = rf;
                        this.sigmaATM = sigmaATM;
                        this.pillar = pillar;
                        this.tail = tail;
                        this.baseXMax = Math.max(baseXMax, 0.60);
                }
        }

        /**
         * 扫描点
         */
        private static class ScanPoint {
                final double x;
                final double K;
                final double sigma;
                final double delta;

                ScanPoint(double x, double k, double sigma, double delta) {
                        this.x = x;
                        this.K = k;
                        this.sigma = sigma;
                        this.delta = delta;
                }
        }

        /**
         * 扫描结果容器
         */
        private static class ScanGrid {
                final List<ScanPoint> points = new ArrayList<>();
                double deltaMin = Double.POSITIVE_INFINITY;
                double deltaMax = Double.NEGATIVE_INFINITY;
        }

        /**
         * 根括号区间
         */
        private static class Bracket {
                final double xLeft;
                final double xRight;

                Bracket(double xLeft, double xRight) {
                        this.xLeft = xLeft;
                        this.xRight = xRight;
                }
        }

        /**
         * 求解状态
         */
        private enum SolveStatus {
                SOLVED,
                EXTRAPOLATED,
                CLIPPED,
                FALLBACK
        }

        /**
         * 求解结果
         */
        private static class SolveResult {
                final double sigma;
                final double strike;
                final SolveStatus status;

                SolveResult(double sigma, double strike, SolveStatus status) {
                        this.sigma = sigma;
                        this.strike = strike;
                        this.status = status;
                }
        }

        /**
         * 统计容器
         */
        private static class SolveCounter {
                int solved = 0;
                int extrapolated = 0;
                int clipped = 0;
                int fallback = 0;

                void add(SolveStatus status) {
                        if (status == SolveStatus.SOLVED) {
                                solved++;
                        } else if (status == SolveStatus.EXTRAPOLATED) {
                                extrapolated++;
                        } else if (status == SolveStatus.CLIPPED) {
                                clipped++;
                        } else {
                                fallback++;
                        }
                }

                int nonSolvedCount() {
                        return extrapolated + clipped + fallback;
                }
        }

        /**
         * 构建 VV 支柱数据
         */
        private VVPillar buildPillar(double F, double T, double rf,
                        double atmVol, double rrVol, double bfVol) {
                VVPillar p = new VVPillar();
                double sqrtT = Math.sqrt(T);

                // 支柱波动率
                p.sig1 = atmVol + bfVol - rrVol / 2.0; // 25DP

                p.sig3 = atmVol + bfVol + rrVol / 2.0; // 25DC

                // 支柱执行价
                p.K1 = deltaToStrike(0.25, F, T, p.sig1, rf, false);
                p.K2 = atmDnsStrike(F, T, atmVol);
                p.K3 = deltaToStrike(0.25, F, T, p.sig3, rf, true);

                p.lnK1 = Math.log(p.K1);
                p.lnK2 = Math.log(p.K2);
                p.lnK3 = Math.log(p.K3);

                // 各支柱 d1/d2（在 σ_ATM 处计算）
                p.d1K1 = (Math.log(F / p.K1) + 0.5 * atmVol * atmVol * T) / (atmVol * sqrtT);
                p.d2K1 = p.d1K1 - atmVol * sqrtT;
                p.d1K3 = (Math.log(F / p.K3) + 0.5 * atmVol * atmVol * T) / (atmVol * sqrtT);
                p.d2K3 = p.d1K3 - atmVol * sqrtT;

                return p;
        }

        /**
         * 给定目标 put delta，求解 K* 使得 delta_put(K*, σ(K*)) = δ_target
         * 流程：扫描找括号 + Brent；不可达域显式外推；最终多级回退。
         */
        private SolveResult solveForDelta(double targetDelta, SmileContext ctx) {
                double xMax = ctx.baseXMax;
                int points = SCAN_POINTS;
                ScanGrid lastGrid = null;

                for (int attempt = 0; attempt <= MAX_SCAN_EXPAND; attempt++) {
                        ScanGrid grid = scanGrid(ctx, xMax, points, false);
                        if (grid.points.size() < 2) {
                                break;
                        }
                        lastGrid = grid;

                        SolveResult exact = exactHit(targetDelta, grid, SolveStatus.SOLVED);
                        if (exact != null) {
                                return exact;
                        }

                        SolveResult solved = solveFromGrid(targetDelta, ctx, grid, SolveStatus.SOLVED, false);
                        if (solved != null) {
                                return solved;
                        }

                        xMax *= SCAN_EXPAND_FACTOR;
                        points = Math.min(MAX_SCAN_POINTS, points + 200);
                }

                if (lastGrid != null && lastGrid.points.size() >= 2) {
                        double lower = lastGrid.deltaMin + DELTA_CLIP_EPS;
                        double upper = lastGrid.deltaMax - DELTA_CLIP_EPS;
                        if (lower < upper) {
                                if (targetDelta < lower || targetDelta > upper) {
                                        SolveResult extrap = extrapolateOutsideDomain(targetDelta, lastGrid,
                                                        SolveStatus.EXTRAPOLATED);
                                        if (extrap != null) {
                                                return extrap;
                                        }
                                        double clipped = clamp(targetDelta, lower, upper);
                                        SolveResult clippedSolved = solveFromGrid(clipped, ctx, lastGrid,
                                                        SolveStatus.CLIPPED, false);
                                        if (clippedSolved != null) {
                                                return clippedSolved;
                                        }
                                } else {
                                        SolveResult interp = interpolateByDelta(targetDelta, lastGrid,
                                                        SolveStatus.FALLBACK);
                                        if (interp != null) {
                                                return interp;
                                        }
                                }
                        }
                }

                SolveResult tailSolved = solveWithTailOnly(targetDelta, ctx);
                if (tailSolved != null) {
                        return tailSolved;
                }
                return new SolveResult(ctx.sigmaATM, ctx.forward, SolveStatus.FALLBACK);
        }

        /**
         * 从扫描网格中求解（括号 + Brent）
         */
        private SolveResult solveFromGrid(double targetDelta, SmileContext ctx, ScanGrid grid,
                        SolveStatus status, boolean tailOnly) {
                List<Bracket> brackets = findBrackets(grid, targetDelta);
                if (brackets.isEmpty()) {
                        return null;
                }

                Bracket best = selectBracket(brackets);
                double rootX = brentSolve(targetDelta, ctx, best.xLeft, best.xRight, tailOnly);
                if (!Double.isFinite(rootX)) {
                        return null;
                }

                double K = ctx.forward * Math.exp(rootX);
                double sigma = sigmaAtStrike(K, ctx, tailOnly);
                if (!Double.isFinite(sigma) || sigma <= 0) {
                        return null;
                }
                return new SolveResult(sigma, K, status);
        }

        /**
         * 扫描命中点（delta 近似相等）
         */
        private SolveResult exactHit(double targetDelta, ScanGrid grid, SolveStatus status) {
                ScanPoint best = null;
                double bestErr = Double.POSITIVE_INFINITY;
                for (ScanPoint pt : grid.points) {
                        double err = Math.abs(pt.delta - targetDelta);
                        if (err < bestErr) {
                                bestErr = err;
                                best = pt;
                        }
                }
                if (best != null && bestErr < DELTA_TOL) {
                        return new SolveResult(best.sigma, best.K, status);
                }
                return null;
        }

        /**
         * 不可达域外推（以 delta 为自变量，外推 sigma 与 lnK）
         */
        private SolveResult extrapolateOutsideDomain(double targetDelta, ScanGrid grid, SolveStatus status) {
                if (grid.points.size() < 2) {
                        return null;
                }
                List<ScanPoint> sorted = new ArrayList<>(grid.points);
                sorted.sort(Comparator.comparingDouble(p -> p.delta));

                ScanPoint p1;
                ScanPoint p2;
                if (targetDelta < sorted.get(0).delta) {
                        p1 = sorted.get(0);
                        p2 = firstDistinct(sorted, 0, 1);
                } else if (targetDelta > sorted.get(sorted.size() - 1).delta) {
                        p2 = sorted.get(sorted.size() - 1);
                        p1 = firstDistinct(sorted, sorted.size() - 1, -1);
                } else {
                        return null;
                }
                if (p1 == null || p2 == null || Math.abs(p2.delta - p1.delta) < 1e-12) {
                        return null;
                }
                return linearByDelta(targetDelta, p1, p2, status);
        }

        /**
         * 域内插值回退（按 delta 插值）
         */
        private SolveResult interpolateByDelta(double targetDelta, ScanGrid grid, SolveStatus status) {
                if (grid.points.size() < 2) {
                        return null;
                }
                List<ScanPoint> sorted = new ArrayList<>(grid.points);
                sorted.sort(Comparator.comparingDouble(p -> p.delta));

                if (targetDelta <= sorted.get(0).delta) {
                        ScanPoint p2 = firstDistinct(sorted, 0, 1);
                        if (p2 != null) {
                                return linearByDelta(targetDelta, sorted.get(0), p2, status);
                        }
                }
                if (targetDelta >= sorted.get(sorted.size() - 1).delta) {
                        ScanPoint p1 = firstDistinct(sorted, sorted.size() - 1, -1);
                        if (p1 != null) {
                                return linearByDelta(targetDelta, p1, sorted.get(sorted.size() - 1), status);
                        }
                }

                for (int i = 1; i < sorted.size(); i++) {
                        ScanPoint left = sorted.get(i - 1);
                        ScanPoint right = sorted.get(i);
                        if (targetDelta >= left.delta && targetDelta <= right.delta) {
                                if (Math.abs(right.delta - left.delta) < 1e-12) {
                                        continue;
                                }
                                return linearByDelta(targetDelta, left, right, status);
                        }
                }
                return null;
        }

        /**
         * 线性外推/插值（sigma 与 lnK）
         */
        private SolveResult linearByDelta(double targetDelta, ScanPoint p1, ScanPoint p2, SolveStatus status) {
                double d1 = p1.delta;
                double d2 = p2.delta;
                if (Math.abs(d2 - d1) < 1e-12) {
                        return null;
                }
                double w = (targetDelta - d1) / (d2 - d1);
                double sigma = p1.sigma + w * (p2.sigma - p1.sigma);
                sigma = Math.max(sigma, MIN_SIGMA);

                double lnK1 = Math.log(p1.K);
                double lnK2 = Math.log(p2.K);
                double lnK = lnK1 + w * (lnK2 - lnK1);
                double K = Math.exp(lnK);
                if (!Double.isFinite(K) || K <= 0) {
                        return null;
                }
                return new SolveResult(sigma, K, status);
        }

        /**
         * tail-only 模式回退
         */
        private SolveResult solveWithTailOnly(double targetDelta, SmileContext ctx) {
                double xMax = ctx.baseXMax * SCAN_EXPAND_FACTOR;
                int points = SCAN_POINTS;
                ScanGrid lastGrid = null;

                for (int attempt = 0; attempt <= MAX_SCAN_EXPAND; attempt++) {
                        ScanGrid grid = scanGrid(ctx, xMax, points, true);
                        if (grid.points.size() < 2) {
                                break;
                        }
                        lastGrid = grid;
                        SolveResult exact = exactHit(targetDelta, grid, SolveStatus.FALLBACK);
                        if (exact != null) {
                                return exact;
                        }
                        SolveResult solved = solveFromGrid(targetDelta, ctx, grid, SolveStatus.FALLBACK, true);
                        if (solved != null) {
                                return solved;
                        }
                        xMax *= SCAN_EXPAND_FACTOR;
                        points = Math.min(MAX_SCAN_POINTS, points + 200);
                }

                if (lastGrid != null && lastGrid.points.size() >= 2) {
                        SolveResult extrap = extrapolateOutsideDomain(targetDelta, lastGrid, SolveStatus.FALLBACK);
                        if (extrap != null) {
                                return extrap;
                        }
                        SolveResult interp = interpolateByDelta(targetDelta, lastGrid, SolveStatus.FALLBACK);
                        if (interp != null) {
                                return interp;
                        }
                }
                return null;
        }

        /**
         * logK 扫描（按 Put Delta 口径）
         */
        private ScanGrid scanGrid(SmileContext ctx, double xMax, int points, boolean tailOnly) {
                ScanGrid grid = new ScanGrid();
                if (points < 3) {
                        return grid;
                }
                for (int i = 0; i < points; i++) {
                        double x = -xMax + 2.0 * xMax * i / (points - 1.0);
                        double K = ctx.forward * Math.exp(x);
                        double sigma = sigmaAtStrike(K, ctx, tailOnly);
                        if (!Double.isFinite(sigma) || sigma <= 0) {
                                continue;
                        }
                        double delta = bsPutDelta(ctx.forward, K, ctx.T, ctx.rf, sigma);
                        if (!Double.isFinite(delta)) {
                                continue;
                        }
                        grid.points.add(new ScanPoint(x, K, sigma, delta));
                        grid.deltaMin = Math.min(grid.deltaMin, delta);
                        grid.deltaMax = Math.max(grid.deltaMax, delta);
                }
                return grid;
        }

        /**
         * 在扫描网格中寻找变号括号
         */
        private List<Bracket> findBrackets(ScanGrid grid, double targetDelta) {
                List<Bracket> brackets = new ArrayList<>();
                List<ScanPoint> pts = grid.points;
                for (int i = 1; i < pts.size(); i++) {
                        ScanPoint left = pts.get(i - 1);
                        ScanPoint right = pts.get(i);
                        double fLeft = left.delta - targetDelta;
                        double fRight = right.delta - targetDelta;
                        if (!Double.isFinite(fLeft) || !Double.isFinite(fRight)) {
                                continue;
                        }
                        if (Math.abs(fLeft) < DELTA_TOL || Math.abs(fRight) < DELTA_TOL || fLeft * fRight < 0) {
                                brackets.add(new Bracket(left.x, right.x));
                        }
                }
                return brackets;
        }

        /**
         * 多括号时选最接近 forward 的根区间
         */
        private Bracket selectBracket(List<Bracket> brackets) {
                Bracket best = null;
                double bestAbsMid = Double.POSITIVE_INFINITY;
                for (Bracket b : brackets) {
                        double mid = 0.5 * (b.xLeft + b.xRight);
                        double absMid = Math.abs(mid);
                        if (absMid < bestAbsMid) {
                                bestAbsMid = absMid;
                                best = b;
                        }
                }
                return best;
        }

        /**
         * Brent 根求解（变量 x=ln(K/F)）
         */
        private double brentSolve(double targetDelta, SmileContext ctx, double xLeft, double xRight, boolean tailOnly) {
                double a = xLeft;
                double b = xRight;
                double fa = deltaResidualByX(a, targetDelta, ctx, tailOnly);
                double fb = deltaResidualByX(b, targetDelta, ctx, tailOnly);

                if (!Double.isFinite(fa) || !Double.isFinite(fb) || fa * fb > 0) {
                        return Double.NaN;
                }
                if (Math.abs(fa) < Math.abs(fb)) {
                        double tx = a;
                        a = b;
                        b = tx;
                        double tf = fa;
                        fa = fb;
                        fb = tf;
                }

                double c = a;
                double fc = fa;
                double d = b - a;
                double e = d;

                for (int iter = 0; iter < BRENT_MAX_ITER; iter++) {
                        if (Math.abs(fc) < Math.abs(fb)) {
                                double tx = a;
                                a = b;
                                b = c;
                                c = tx;
                                double tf = fa;
                                fa = fb;
                                fb = fc;
                                fc = tf;
                        }

                        double tol = 2.0 * BRENT_EPS * Math.abs(b) + BRENT_X_TOL;
                        double m = 0.5 * (c - b);
                        if (Math.abs(m) <= tol || Math.abs(fb) < DELTA_TOL) {
                                return b;
                        }

                        if (Math.abs(e) >= tol && Math.abs(fa) > Math.abs(fb)) {
                                double s = fb / fa;
                                double p;
                                double q;
                                if (almostEqual(a, c, 1e-15)) {
                                        p = 2.0 * m * s;
                                        q = 1.0 - s;
                                } else {
                                        double q1 = fa / fc;
                                        double r = fb / fc;
                                        p = s * (2.0 * m * q1 * (q1 - r) - (b - a) * (r - 1.0));
                                        q = (q1 - 1.0) * (r - 1.0) * (s - 1.0);
                                }

                                if (p > 0) {
                                        q = -q;
                                }
                                p = Math.abs(p);
                                double min1 = 3.0 * m * q - Math.abs(tol * q);
                                double min2 = Math.abs(e * q);
                                if (2.0 * p < Math.min(min1, min2)) {
                                        e = d;
                                        d = p / q;
                                } else {
                                        d = m;
                                        e = m;
                                }
                        } else {
                                d = m;
                                e = m;
                        }

                        a = b;
                        fa = fb;
                        if (Math.abs(d) > tol) {
                                b += d;
                        } else {
                                b += Math.copySign(tol, m);
                        }
                        fb = deltaResidualByX(b, targetDelta, ctx, tailOnly);
                        if (!Double.isFinite(fb)) {
                                return Double.NaN;
                        }

                        if ((fb > 0 && fc > 0) || (fb < 0 && fc < 0)) {
                                c = a;
                                fc = fa;
                                d = b - a;
                                e = d;
                        }
                }
                return Double.NaN;
        }

        private double deltaResidualByX(double x, double targetDelta, SmileContext ctx, boolean tailOnly) {
                double K = ctx.forward * Math.exp(x);
                double sigma = sigmaAtStrike(K, ctx, tailOnly);
                double delta = bsPutDelta(ctx.forward, K, ctx.T, ctx.rf, sigma);
                return delta - targetDelta;
        }

        /**
         * VV + tail 平滑后 sigma(K)
         */
        private double sigmaAtStrike(double K, SmileContext ctx, boolean tailOnly) {
                if (!(K > 0) || !(ctx.T > 0)) {
                        return ctx.sigmaATM;
                }

                double x = Math.log(K / ctx.forward);
                double sigmaVV = vvClosedForm(K, ctx.forward, ctx.T, ctx.sigmaATM, ctx.pillar);
                double wVV = Math.max(sigmaVV * sigmaVV * ctx.T, MIN_TOTAL_VAR);
                double w;

                if (x >= ctx.tail.x0Right) {
                        double wTail = tailRightTotalVar(x, ctx.tail);
                        if (tailOnly) {
                                w = wTail;
                        } else {
                                double alpha = weightRight(x, ctx.tail);
                                w = alpha * wVV + (1.0 - alpha) * wTail;
                        }
                } else if (x <= ctx.tail.x0Left) {
                        double wTail = tailLeftTotalVar(x, ctx.tail);
                        if (tailOnly) {
                                w = wTail;
                        } else {
                                double alpha = weightLeft(x, ctx.tail);
                                w = alpha * wVV + (1.0 - alpha) * wTail;
                        }
                } else {
                        w = wVV;
                }

                double sigma = Math.sqrt(Math.max(w, MIN_TOTAL_VAR) / ctx.T);
                if (!Double.isFinite(sigma) || sigma <= 0) {
                        return Math.max(ctx.sigmaATM, MIN_SIGMA);
                }
                return sigma;
        }

        private static double tailRightTotalVar(double x, TailParams tail) {
                return Math.max(tail.w0Right + tail.slopeRight * (x - tail.x0Right), MIN_TOTAL_VAR);
        }

        private static double tailLeftTotalVar(double x, TailParams tail) {
                return Math.max(tail.w0Left + tail.slopeLeft * (x - tail.x0Left), MIN_TOTAL_VAR);
        }

        private static double weightRight(double x, TailParams tail) {
                if (x <= tail.x0Right) {
                        return 1.0;
                }
                if (x >= tail.x1Right) {
                        return 0.0;
                }
                double u = (x - tail.x0Right) / (tail.x1Right - tail.x0Right);
                return 1.0 - smoothstep(u);
        }

        private static double weightLeft(double x, TailParams tail) {
                if (x >= tail.x0Left) {
                        return 1.0;
                }
                if (x <= tail.x1Left) {
                        return 0.0;
                }
                double u = (tail.x0Left - x) / (tail.x0Left - tail.x1Left);
                return 1.0 - smoothstep(u);
        }

        private static double smoothstep(double u) {
                double t = clamp(u, 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
        }

        /**
         * 根据期限构建翼部外推参数
         */
        private TailParams buildTailParams(double F, double T, double rf, double sigmaATM, VVPillar pillar) {
                TailParams tail = new TailParams();
                // 翼部过渡区仍以 Call Delta 分位来锚定，与输出采用 Put Delta 网格无关
                double callDeltaFloor = 0.05;
                double deltaCap = Math.exp(-rf * T) - DELTA_CLIP_EPS;
                deltaCap = Math.max(callDeltaFloor + 2.0 * DELTA_CLIP_EPS, Math.min(0.999, deltaCap));

                double deltaRight = Math.max(callDeltaFloor + DELTA_CLIP_EPS,
                                Math.min(DELTA_RIGHT_SWITCH, deltaCap * 0.8));
                double deltaLeft = Math.max(deltaRight + DELTA_CLIP_EPS,
                                Math.min(DELTA_LEFT_SWITCH, deltaCap));

                double kRight0 = deltaToStrike(deltaRight, F, T, sigmaATM, rf, true);
                double kLeft0 = deltaToStrike(deltaLeft, F, T, sigmaATM, rf, true);

                tail.x0Right = Math.log(kRight0 / F);
                tail.x1Right = tail.x0Right + TAIL_TRANSITION_WIDTH;
                tail.x0Left = Math.log(kLeft0 / F);
                tail.x1Left = tail.x0Left - TAIL_TRANSITION_WIDTH;

                tail.w0Right = Math.max(vvClosedForm(kRight0, F, T, sigmaATM, pillar), MIN_SIGMA);
                tail.w0Right = tail.w0Right * tail.w0Right * T;
                tail.w0Left = Math.max(vvClosedForm(kLeft0, F, T, sigmaATM, pillar), MIN_SIGMA);
                tail.w0Left = tail.w0Left * tail.w0Left * T;

                double slopeLimit = tailSlopeLimit(T);
                double rawRight = totalVarSlope(F, T, sigmaATM, pillar, tail.x0Right);
                double rawLeft = totalVarSlope(F, T, sigmaATM, pillar, tail.x0Left);

                tail.slopeRight = clamp(rawRight, 0.0, slopeLimit);
                tail.slopeLeft = clamp(rawLeft, -slopeLimit, 0.0);

                if (Math.abs(tail.slopeRight) < 1e-8) {
                        tail.slopeRight = 0.25 * slopeLimit;
                }
                if (Math.abs(tail.slopeLeft) < 1e-8) {
                        tail.slopeLeft = -0.25 * slopeLimit;
                }
                return tail;
        }

        private double totalVarSlope(double F, double T, double sigmaATM, VVPillar pillar, double x0) {
                double xL = x0 - TAIL_SLOPE_DX;
                double xR = x0 + TAIL_SLOPE_DX;
                double kL = F * Math.exp(xL);
                double kR = F * Math.exp(xR);
                double sL = Math.max(vvClosedForm(kL, F, T, sigmaATM, pillar), MIN_SIGMA);
                double sR = Math.max(vvClosedForm(kR, F, T, sigmaATM, pillar), MIN_SIGMA);
                double wL = sL * sL * T;
                double wR = sR * sR * T;
                double slope = (wR - wL) / (2.0 * TAIL_SLOPE_DX);
                if (!Double.isFinite(slope)) {
                        return 0.0;
                }
                return slope;
        }

        private static double tailSlopeLimit(double T) {
                if (T < 0.25) {
                        return 0.10;
                }
                if (T <= 2.0) {
                        return 0.15;
                }
                return 0.20;
        }

        /**
         * 构建动态执行价边界（K/F）
         * 先按 sigma*sqrt(T) 生成对称 log-moneyness 区间，再施加绝对边界。
         */
        private static double[] buildStrikeBounds(double F, double T, double sigmaATM) {
                double band = LOG_MONEYNESS_BAND_MULTIPLIER * sigmaATM * Math.sqrt(T);
                band = Math.max(LOG_MONEYNESS_MIN_BAND, Math.min(LOG_MONEYNESS_MAX_BAND, band));

                double kMin = F * Math.exp(-band);
                double kMax = F * Math.exp(band);

                kMin = Math.max(kMin, F * ABS_MIN_K_RATIO);
                kMax = Math.min(kMax, F * ABS_MAX_K_RATIO);

                if (kMin >= kMax) {
                        kMin = F * ABS_MIN_K_RATIO;
                        kMax = F * ABS_MAX_K_RATIO;
                }
                return new double[] { kMin, kMax };
        }

        private static ScanPoint firstDistinct(List<ScanPoint> points, int start, int step) {
                ScanPoint anchor = points.get(start);
                int i = start + step;
                while (i >= 0 && i < points.size()) {
                        ScanPoint current = points.get(i);
                        if (Math.abs(current.delta - anchor.delta) > 1e-12) {
                                return current;
                        }
                        i += step;
                }
                return null;
        }

        private static boolean almostEqual(double a, double b, double tol) {
                return Math.abs(a - b) <= tol;
        }

        private static double clamp(double value, double lower, double upper) {
                return Math.max(lower, Math.min(upper, value));
        }

        /**
         * 二阶 Vanna-Volga 闭式解
         *
         * σ(K) = σ + [-σ + √(σ² + d₁(K)·d₂(K)·(2σP + Q))] / (d₁(K)·d₂(K))
         *
         * 其中:
         * Yᵢ = log-strike Lagrange 权重（对每个 K 重新计算）
         * P = Σ Yᵢ·(σᵢ - σ)
         * Q = Σ Yᵢ·d₁(Kᵢ)·d₂(Kᵢ)·(σᵢ - σ)²
         */
        private double vvClosedForm(double K, double F, double T,
                        double sigmaATM, VVPillar p) {
                double sqrtT = Math.sqrt(T);
                double lnK = Math.log(K);

                // 对每个 K 重算 Lagrange 权重 Yᵢ（log-strike 空间）
                double Y1 = (lnK - p.lnK2) * (lnK - p.lnK3) / ((p.lnK1 - p.lnK2) * (p.lnK1 - p.lnK3));
                double Y3 = (lnK - p.lnK1) * (lnK - p.lnK2) / ((p.lnK3 - p.lnK1) * (p.lnK3 - p.lnK2));

                // 目标点 d₁/d₂（在 σ_ATM 处）
                double d1K = (Math.log(F / K) + 0.5 * sigmaATM * sigmaATM * T) / (sigmaATM * sqrtT);
                double d2K = d1K - sigmaATM * sqrtT;
                double d1d2 = d1K * d2K;

                double ds1 = p.sig1 - sigmaATM;
                double ds3 = p.sig3 - sigmaATM;

                double P = Y1 * ds1 + Y3 * ds3;
                double Q = Y1 * p.d1K1 * p.d2K1 * ds1 * ds1
                                + Y3 * p.d1K3 * p.d2K3 * ds3 * ds3;

                // 近 ATM 区域 d1d2 → 0，使用线性近似避免除零
                if (Math.abs(d1d2) < 1e-10) {
                        return sigmaATM + P + Q / (2.0 * sigmaATM);
                }

                double disc = sigmaATM * sigmaATM + d1d2 * (2.0 * sigmaATM * P + Q);
                if (disc < 0)
                        disc = 0;

                double result = sigmaATM + (-sigmaATM + Math.sqrt(disc)) / d1d2;
                if (Double.isNaN(result) || Double.isInfinite(result) || result <= 0) {
                        return sigmaATM;
                }
                return result;
        }
}
