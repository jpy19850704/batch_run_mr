package com.zcyh.mr.calc;

import com.zcyh.mr.core.Constants;

/**
 * 统一 oper_code 模式控制：
 * PRICING  -> 仅估值
 * FRTB     -> 估值 + FRTB
 * SCENARIO -> 估值 + 场景
 * CURVE_GENERATION -> 仅曲线生成
 */
public final class OperModeControl {
    private enum Mode {
        PRICING,
        FRTB,
        SCENARIO,
        CURVE_GENERATION
    }

    private static final ThreadLocal<Mode> MODE_HOLDER = ThreadLocal.withInitial(() -> Mode.PRICING);

    private OperModeControl() {
    }

    public static void init(String operCode) {
        MODE_HOLDER.set(resolve(operCode));
    }

    public static void clear() {
        MODE_HOLDER.remove();
    }

    public static boolean isFrtbEnabled() {
        return MODE_HOLDER.get() == Mode.FRTB;
    }

    public static boolean isScenarioEnabled() {
        return MODE_HOLDER.get() == Mode.SCENARIO;
    }

    public static boolean isCurveGenerationOnly() {
        return MODE_HOLDER.get() == Mode.CURVE_GENERATION;
    }

    public static String normalizeForExecution(String operCode) {
        if (operCode == null) {
            return null;
        }
        String oc = operCode.trim();
        if (oc.isEmpty()) {
            return oc;
        }
        if (Constants.OPER_CODE.PRICING.equalsIgnoreCase(oc)
                || Constants.OPER_CODE.FRTB.equalsIgnoreCase(oc)
                || Constants.OPER_CODE.SCENARIO.equalsIgnoreCase(oc)
                || Constants.OPER_CODE.CURVE_GENERATION.equalsIgnoreCase(oc)) {
            return Constants.OPER_CODE.PRICING;
        }
        return oc;
    }

    private static Mode resolve(String operCode) {
        if (operCode == null) {
            return Mode.PRICING;
        }
        String oc = operCode.trim();
        if (Constants.OPER_CODE.FRTB.equalsIgnoreCase(oc)) {
            return Mode.FRTB;
        }
        if (Constants.OPER_CODE.SCENARIO.equalsIgnoreCase(oc)) {
            return Mode.SCENARIO;
        }
        if (Constants.OPER_CODE.CURVE_GENERATION.equalsIgnoreCase(oc)) {
            return Mode.CURVE_GENERATION;
        }
        return Mode.PRICING;
    }
}
