package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.VolUtil;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * WeddingCake 定价工具：
 * 三层收益结构（outer/mid/inner）拆解为 2 个 Double Barrier KO 期权 + 固定腿。
 * 支持 Vanna-Volga overhedge 调整（逐 leg 注入）。
 *
 * 定价分解：
 * V = 固定腿 (outRate × df × notional × accrualYear)
 * + 外层腿 (Double Barrier KO, rebate = (midRate - outRate) × notional ×
 * accrualYear)
 * + 内层腿 (Double Barrier KO, rebate = (innerRate - midRate) × notional ×
 * accrualYear)
 */
public class WeddingCakeUtil {
    private static final double MIN_SIGMA = 1e-6;
    private static final double MIN_SPOT = 1e-12;
    private static final double MIN_TIME = 0.0;

    private final double s;
    private final double rd;
    private final double rf;
    private final double rebase;
    private final double t;
    private final double ts;
    private final List<Map<String, Object>> volCurve;

    private final double outerLower;
    private final double outerUpper;
    private final double innerLower;
    private final double innerUpper;

    private final double outRate;
    private final double midRate;
    private final double innerRate;
    private final boolean vvFlag;

    public WeddingCakeUtil(double s, double rd, double rf, double rebase,
            double t, double ts, List<Map<String, Object>> volCurve,
            double outerLower, double outerUpper, double innerLower, double innerUpper,
            double outRate, double midRate, double innerRate, boolean vvFlag) {
        this.s = s;
        this.rd = rd;
        this.rf = rf;
        this.rebase = rebase;
        this.t = t;
        this.ts = ts;
        this.volCurve = volCurve;
        this.outerLower = outerLower;
        this.outerUpper = outerUpper;
        this.innerLower = innerLower;
        this.innerUpper = innerUpper;
        this.outRate = outRate;
        this.midRate = midRate;
        this.innerRate = innerRate;
        this.vvFlag = vvFlag;
    }

    /**
     * 首次基准估值：校准 sigma，使用 BarOptUtil 计算 barrier 概率。
     */
    public Result evaluate(TouchState histState) {
        return evaluate(histState, 1.0, 1.0);
    }

    /**
     * 首次基准估值：在 expectedRate 之外，同时缓存主路径使用的 VV 调整。
     */
    public Result evaluate(TouchState histState, double notional, double accrualYear) {
        Result result = new Result();
        if (histState == TouchState.OUTER_TOUCHED) {
            result.expectedRate = outRate;
            result.stateLabel = "LOCKED_OUTER";
            result.vvAdjOuter = 0.0;
            result.vvAdjInner = 0.0;
            return result;
        }

        double fwd = s * Math.exp((rd - rf) * t);
        result.sigmaOuter = calibratePairSigma(volCurve, s, rd, rf, t, ts, outerLower, outerUpper);

        // 使用 BarOptUtil 实例计算外层 noTouchProb，与 valueUnit() 保持一致
        BarOptUtil outerBar = new BarOptUtil(s, 1.0, 0.0,
                outerLower, outerUpper, rd, rf, rebase, result.sigmaOuter, t,
                null, true, false, "Double_Barrier");
        result.pOuter = outerBar.noTouchProb();
        if (vvFlag) {
            double outerRebate = (midRate - outRate) * notional * accrualYear;
            result.vvAdjOuter = BarOptUtil.computeScaledVvAdjustment(
                    s, fwd, 0.0, outerLower, outerUpper,
                    outerRebate, rd, rf, rebase, result.sigmaOuter, t,
                    null, true, false, "Double_Barrier", volCurve, true, result.pOuter);
        }

        if (histState == TouchState.INNER_TOUCHED) {
            result.expectedRate = outRate + (midRate - outRate) * result.pOuter;
            result.stateLabel = "LOCKED_MID_OR_OUT";
            result.vvAdjInner = 0.0;
            return result;
        }

        result.sigmaInner = calibratePairSigma(volCurve, s, rd, rf, t, ts, innerLower, innerUpper);

        // 使用 BarOptUtil 实例计算内层 noTouchProb
        BarOptUtil innerBar = new BarOptUtil(s, 1.0, 0.0,
                innerLower, innerUpper, rd, rf, rebase, result.sigmaInner, t,
                null, true, false, "Double_Barrier");
        double pInner = innerBar.noTouchProb();
        pInner = Math.min(pInner, result.pOuter);
        result.pInner = pInner;
        if (vvFlag) {
            double innerRebate = (innerRate - midRate) * notional * accrualYear;
            result.vvAdjInner = BarOptUtil.computeScaledVvAdjustment(
                    s, fwd, 0.0, innerLower, innerUpper,
                    innerRebate, rd, rf, rebase, result.sigmaInner, t,
                    null, true, false, "Double_Barrier", volCurve, true, result.pInner);
        }
        result.expectedRate = outRate + (midRate - outRate) * result.pOuter
                + (innerRate - midRate) * result.pInner;
        result.stateLabel = "FULL_3_LAYER";
        return result;
    }

    /**
     * 通用估值：使用 2 个 Double Barrier BarOptUtil 定价 + VV 调整。
     * 用于 Greeks 数值扰动和场景估值。
     *
     * @param notional    名义本金
     * @param accrualYear 计息年限
     * @param histState   历史触碰状态
     * @param sigmaOuter  外层 sigma（基准阶段校准）
     * @param sigmaInner  内层 sigma（基准阶段校准）
     * @param sShift      即期价格冲击
     * @param sigmaShift  sigma 冲击
     * @param tShift      时间冲击
     */
    public double valueUnit(double notional, double accrualYear,
            TouchState histState,
            double sigmaOuter, double sigmaInner,
            double sShift, double sigmaShift, double tShift) {
        double sAdj = Math.max(MIN_SPOT, s + sShift);
        double tAdj = Math.max(MIN_TIME, t + tShift);
        double tsAdj = Math.max(MIN_TIME, ts + tShift);
        double sigmaOuterAdj = Math.max(MIN_SIGMA, sigmaOuter + sigmaShift);
        double sigmaInnerAdj = Math.max(MIN_SIGMA, sigmaInner + sigmaShift);
        double dfSettle = Math.exp(-rebase * tsAdj);
        // 双障碍 VV 统一使用远期价作为参考执行价。
        double fwd = sAdj * Math.exp((rd - rf) * tAdj);

        // 固定腿：outRate 部分始终存在
        double fixedLeg = notional * accrualYear * outRate * dfSettle;

        if (histState == TouchState.OUTER_TOUCHED) {
            return fixedLeg;
        }

        double dOuter = midRate - outRate;
        double dInner = innerRate - midRate;

        // 外层腿：Double Barrier KO，rebate = Δ₁ × notional × accrualYear
        double outerRebate = dOuter * notional * accrualYear;
        BarOptUtil outerBar = new BarOptUtil(sAdj, outerRebate, 0.0,
                outerLower, outerUpper, rd, rf, rebase, sigmaOuterAdj, tAdj,
                null, true, false, "Double_Barrier");
        double outerValue = outerBar.getValue();
        boolean applyNonNegativeFloor = vvFlag && isNonNegativeFloorEnabled();

        // VV：外层 leg，统一使用远期价作为 VV strike
        if (vvFlag) {
            double outerNoTouch = outerBar.noTouchProb();
            outerValue += BarOptUtil.computeScaledVvAdjustment(sAdj, fwd, 0.0, outerLower, outerUpper,
                    outerRebate, rd, rf, rebase, sigmaOuterAdj, tAdj,
                    null, true, false, "Double_Barrier", volCurve, true, outerNoTouch);
        }
        outerValue = applyFloorIfNeeded(outerValue, applyNonNegativeFloor);

        if (histState == TouchState.INNER_TOUCHED) {
            return fixedLeg + outerValue;
        }

        // 内层腿：Double Barrier KO，rebate = Δ₂ × notional × accrualYear
        double innerRebate = dInner * notional * accrualYear;
        BarOptUtil innerBar = new BarOptUtil(sAdj, innerRebate, 0.0,
                innerLower, innerUpper, rd, rf, rebase, sigmaInnerAdj, tAdj,
                null, true, false, "Double_Barrier");
        double innerValue = innerBar.getValue();

        // VV：内层 leg，统一使用远期价作为 VV strike
        if (vvFlag) {
            double innerNoTouch = innerBar.noTouchProb();
            innerValue += BarOptUtil.computeScaledVvAdjustment(sAdj, fwd, 0.0, innerLower, innerUpper,
                    innerRebate, rd, rf, rebase, sigmaInnerAdj, tAdj,
                    null, true, false, "Double_Barrier", volCurve, true, innerNoTouch);
        }
        innerValue = applyFloorIfNeeded(innerValue, applyNonNegativeFloor);

        return fixedLeg + outerValue + innerValue;
    }

    /**
     * VV 开启时统一读取非负兜底开关。
     */
    private static boolean isNonNegativeFloorEnabled() {
        return EngineConfiguration.getInstance()
                .getRequiredBoolean(EngineConstants.CFG.VV_NON_NEGATIVE_FLOOR_ENABLED);
    }

    /**
     * 腿级非负兜底，避免 VV 调整在极端场景下产生负的期权腿价格。
     */
    private static double applyFloorIfNeeded(double value, boolean enabled) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (!enabled) {
            return value;
        }
        return Math.max(0.0, value);
    }

    /** 到期后确定收益率 */
    public double realizedRate(TouchState finalState) {
        if (finalState == TouchState.OUTER_TOUCHED) {
            return outRate;
        }
        if (finalState == TouchState.INNER_TOUCHED) {
            return midRate;
        }
        return innerRate;
    }

    /** 历史触碰检测 */
    public static TouchState detectTouchState(Fixing fixing, Fixing.FixingInfo fixingInfo,
            LocalDate from, LocalDate to,
            double outerLower, double outerUpper,
            double innerLower, double innerUpper) {
        if (fixing == null || from == null || to == null || from.isAfter(to)) {
            return TouchState.NONE;
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        dates.add(from);
        dates.add(to);
        if (fixingInfo != null && fixingInfo.curveData != null) {
            for (LocalDate d : fixingInfo.curveData.keySet()) {
                if (!d.isBefore(from) && !d.isAfter(to)) {
                    dates.add(d);
                }
            }
        }

        boolean innerTouched = false;
        for (LocalDate d : dates) {
            double rate = fixing.getRate(d);
            if (rate <= outerLower || rate >= outerUpper) {
                return TouchState.OUTER_TOUCHED;
            }
            if (rate <= innerLower || rate >= innerUpper) {
                innerTouched = true;
            }
        }
        return innerTouched ? TouchState.INNER_TOUCHED : TouchState.NONE;
    }

    // ========== sigma 校准（直接取 ATM vol） ==========

    /**
     * 直接从波动率曲线按 Delta=0.5 插值取 ATM vol，不进行 Delta 迭代。
     * 双障碍类产品（WeddingCake / DoubleBarrier）统一使用此方式。
     */
    private static double calibratePairSigma(List<Map<String, Object>> volCurve,
            double s, double rd, double rf, double t, double ts,
            double lower, double upper) {
        return Math.max(MIN_SIGMA, interpolateAtmVol(volCurve));
    }

    /**
     * 从波动率曲线按 Delta=0.5 插值取 ATM 波动率。
     */
    private static double interpolateAtmVol(List<Map<String, Object>> volCurve) {
        if (volCurve == null || volCurve.isEmpty()) {
            throw new IllegalArgumentException("WeddingCake 波动率曲线不能为空");
        }
        Double[] deltas = volCurve.stream()
                .map(e -> com.zcyh.mr.support.Convert.toDouble(e.get("DELTA")))
                .toArray(Double[]::new);
        Double[] vols = volCurve.stream()
                .map(e -> com.zcyh.mr.support.Convert.toDouble(e.get("VOLATILITY_RATE")))
                .toArray(Double[]::new);
        double sigma = com.zcyh.mr.math.Interpolation.interpolate(deltas, vols, 0.5,
                VolUtil.requireAxis2InterpolateType(volCurve));
        if (!Double.isFinite(sigma) || sigma <= 0.0) {
            throw new IllegalArgumentException("WeddingCake ATM 波动率插值结果非法: " + sigma);
        }
        return sigma;
    }

    public enum TouchState {
        NONE,
        INNER_TOUCHED,
        OUTER_TOUCHED
    }

    public static class Result {
        public double expectedRate;
        public double pOuter;
        public double pInner;
        public double sigmaOuter;
        public double sigmaInner;
        public double vvAdjOuter;
        public double vvAdjInner;
        public String stateLabel;
    }
}
