package com.zcyh.mr.frtbsa.sba.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * FRTB SA V2 不可变常量定义
 * 包含风险类别、敏感性类型、场景、相关性调整等所有固定常量
 * 可调参数（RW、rho、gamma、lambda 等）请通过 param.json 读取
 * 参见 {@link FrtbParamsCache}
 *
 * @author system
 * @version 3.0
 */
public class FrtbConstants {

    // ========================================
    // 1. 风险类别 (Risk Classes)
    // ========================================
    public static final String RISK_CLASS_GIRR = "GIRR";
    public static final String RISK_CLASS_CSRNS = "CSR (non-sec)";
    public static final String RISK_CLASS_CSRNC = "CSR (non-ctp)";
    public static final String RISK_CLASS_CSRCTP = "CSR (ctp)";
    public static final String RISK_CLASS_EQ = "EQ";
    public static final String RISK_CLASS_FX = "FX";
    public static final String RISK_CLASS_CMTY = "CMTY";

    // ========================================
    // 2. 敏感性类型 (Sensitivity Types)
    // ========================================
    public static final String SENS_DELTA = "Delta";
    public static final String SENS_VEGA = "Vega";
    public static final String SENS_CURVATURE = "Curvature";
    public static final String SENS_CURVATURE_UP = "Curvature Up";
    public static final String SENS_CURVATURE_DOWN = "Curvature Down";

    // ========================================
    // 3. 场景 (Scenarios)
    // ========================================
    public static final String SCENARIO_M = "M";
    public static final String SCENARIO_H = "H";
    public static final String SCENARIO_L = "L";

    public static final String[] SCENARIOS = { SCENARIO_M, SCENARIO_H, SCENARIO_L };

    public static final Map<String, String> SCENARIO_NAMES;
    static {
        Map<String, String> map = new HashMap<>();
        map.put(SCENARIO_M, "normal");
        map.put(SCENARIO_H, "high");
        map.put(SCENARIO_L, "low");
        SCENARIO_NAMES = Collections.unmodifiableMap(map);
    }

    // ========================================
    // 4. 场景压力系数
    // ========================================
    public static final double SCENARIO_HIGH_MULTIPLIER = 1.25;
    public static final double SCENARIO_LOW_MULTIPLIER = 0.75;
    public static final double CORRELATION_MAX = 1.0;

    // ========================================
    // 5. 工具方法
    // ========================================

    /**
     * 根据场景调整相关性参数（MAR21.6）
     * H: min(rho * 1.25, 1.0)
     * L: max(2 * rho - 1, rho * 0.75)
     *
     * @param scenario  场景标识 (M/H/L)
     * @param baseValue 基础相关性值
     * @return 调整后的相关性值
     */
    public static double applyScenarioStress(String scenario, double baseValue) {
        switch (scenario) {
            case SCENARIO_H:
                return Math.min(baseValue * SCENARIO_HIGH_MULTIPLIER, CORRELATION_MAX);
            case SCENARIO_L:
                return Math.max(2.0 * baseValue - 1.0, baseValue * SCENARIO_LOW_MULTIPLIER);
            case SCENARIO_M:
            default:
                return baseValue;
        }
    }

    /**
     * Curvature 相关性先平方，再按 MAR21.6 做高低相关性场景。
     */
    public static double applyCurvatureScenarioStress(String scenario, double deltaBaseCorrelation) {
        double curvatureBaseCorrelation = deltaBaseCorrelation * deltaBaseCorrelation;
        return applyScenarioStress(scenario, curvatureBaseCorrelation);
    }

    /**
     * Curvature bucket 内 Up/Down 情景选择规则。
     */
    public static boolean selectCurvatureUpScenario(double kbUp, double kbDown, double sumCvrUp, double sumCvrDown) {
        if (kbUp > kbDown) {
            return true;
        }
        if (kbDown > kbUp) {
            return false;
        }
        return sumCvrUp > sumCvrDown;
    }

    /**
     * 验证风险类别是否合法
     */
    public static boolean isValidRiskClass(String riskClass) {
        return RISK_CLASS_GIRR.equals(riskClass) ||
                RISK_CLASS_CSRNS.equals(riskClass) ||
                RISK_CLASS_CSRNC.equals(riskClass) ||
                RISK_CLASS_CSRCTP.equals(riskClass) ||
                RISK_CLASS_EQ.equals(riskClass) ||
                RISK_CLASS_FX.equals(riskClass) ||
                RISK_CLASS_CMTY.equals(riskClass);
    }

    /**
     * 验证敏感性类型是否合法
     */
    public static boolean isValidSensitivityType(String sensType) {
        return SENS_DELTA.equals(sensType) ||
                SENS_VEGA.equals(sensType) ||
                SENS_CURVATURE_UP.equals(sensType) ||
                SENS_CURVATURE_DOWN.equals(sensType);
    }

    /**
     * FX/GIRR 货币桶归一化：CNH 统一映射为 CNY。
     */
    public static String normalizeBucketForRiskClass(String riskClass, String bucket) {
        if (bucket == null) {
            return null;
        }
        String normalized = bucket.trim();
        if (normalized.isEmpty()) {
            return normalized;
        }
        if ((RISK_CLASS_GIRR.equalsIgnoreCase(riskClass) || RISK_CLASS_FX.equalsIgnoreCase(riskClass))
                && "CNH".equalsIgnoreCase(normalized)) {
            return "CNY";
        }
        return normalized;
    }
}
