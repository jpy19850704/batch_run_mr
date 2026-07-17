package com.zcyh.mr.calc;

import com.zcyh.mr.support.EngineConstants;

/**
 * 统一 calc_mode 模式控制：
 * PRICING  -> 估值主流程
 * CURVE_GENERATION -> 仅曲线生成
 */
public final class OperModeControl {
    private enum Mode {
        PRICING,
        CURVE_GENERATION
    }

    private static final ThreadLocal<Mode> MODE_HOLDER = ThreadLocal.withInitial(() -> Mode.PRICING);

    private OperModeControl() {
    }

    public static void init(String calcMode) {
        MODE_HOLDER.set(resolve(calcMode));
    }

    public static void clear() {
        MODE_HOLDER.remove();
    }

    public static boolean isCurveGenerationOnly() {
        return MODE_HOLDER.get() == Mode.CURVE_GENERATION;
    }

    public static String executionMode() {
        return EngineConstants.CALC_MODE.PRICING;
    }

    private static Mode resolve(String calcMode) {
        if (calcMode == null) {
            return Mode.PRICING;
        }
        String mode = calcMode.trim();
        if (EngineConstants.CALC_MODE.CURVE_GENERATION.equalsIgnoreCase(mode)) {
            return Mode.CURVE_GENERATION;
        }
        if (mode.isEmpty() || EngineConstants.CALC_MODE.PRICING.equalsIgnoreCase(mode)) {
            return Mode.PRICING;
        }
        throw new IllegalArgumentException("calc_mode 仅支持 PRICING 或 CURVE_GENERATION: " + calcMode);
    }
}
