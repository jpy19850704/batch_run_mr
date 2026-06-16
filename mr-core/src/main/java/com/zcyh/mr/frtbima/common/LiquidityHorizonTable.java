package com.zcyh.mr.frtbima.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * MAR33.6 流动性期限映射表。
 *
 * <p>IMA批量主流程使用风险因子树快照中的曲线级显式配置。
 * riskClass映射仅保留给历史单元测试和非主流程调用。
 */
public class LiquidityHorizonTable {

    /** MAR33.6 riskClass → LH 天数 */
    private static final Map<String, Integer> LH_BY_RISK_CLASS = new HashMap<>();

    static {
        // 即期/现货类
        LH_BY_RISK_CLASS.put("IR",           10);
        LH_BY_RISK_CLASS.put("EQ_LARGE",     10);
        LH_BY_RISK_CLASS.put("EQ_SMALL",     20);
        LH_BY_RISK_CLASS.put("FX_MAJOR",     10);
        LH_BY_RISK_CLASS.put("FX_OTHER",     20);
        LH_BY_RISK_CLASS.put("CREDIT_NS",    40);
        LH_BY_RISK_CLASS.put("CREDIT_SEC",   60);
        LH_BY_RISK_CLASS.put("COMM_ENERGY",  20);
        LH_BY_RISK_CLASS.put("COMM_METAL",   40);
        LH_BY_RISK_CLASS.put("COMM_OTHER",   60);
        // 波动率类统一 60（MAR33.6），COMM_VOL 为 120
        LH_BY_RISK_CLASS.put("IR_VOL",       60);
        LH_BY_RISK_CLASS.put("EQ_VOL",       60);
        LH_BY_RISK_CLASS.put("FX_VOL",       60);
        LH_BY_RISK_CLASS.put("COMM_VOL",    120);
    }

    /** curveId → riskClass，来自 RF_CONFIG */
    private final Map<String, String> riskClassByCurveId;
    private final Map<String, Integer> lhDaysByCurveId;
    private final boolean explicitMode;

    /**
     * @param riskClassByCurveId RF_CONFIG 中 curveId → riskClass 的映射
     */
    public LiquidityHorizonTable(Map<String, String> riskClassByCurveId) {
        this.riskClassByCurveId = normalizeTextMap(riskClassByCurveId);
        this.lhDaysByCurveId = new HashMap<>();
        this.explicitMode = false;
    }

    private LiquidityHorizonTable(Map<String, Integer> lhDaysByCurveId, boolean explicitMode) {
        this.riskClassByCurveId = new HashMap<>();
        this.lhDaysByCurveId = normalizeLhMap(lhDaysByCurveId);
        this.explicitMode = explicitMode;
    }

    public static LiquidityHorizonTable fromCurveLiquidityHorizonDays(Map<String, Integer> lhDaysByCurveId) {
        return new LiquidityHorizonTable(lhDaysByCurveId, true);
    }

    /**
     * 获取指定曲线对应的流动性期限天数。
     *
     * @param curveId 曲线ID
     * @return LH 天数
     */
    public int getLhDays(String curveId) {
        String normalizedCurveId = normalizeKey(curveId);
        if (explicitMode) {
            Integer lhDays = lhDaysByCurveId.get(normalizedCurveId);
            if (lhDays == null) {
                throw new IllegalStateException("IMA风险因子流动性期限未配置: curveId=" + curveId);
            }
            return lhDays;
        }
        String riskClass = riskClassByCurveId.get(normalizedCurveId);
        if (riskClass == null) {
            return ImaConstants.LH_BASE;
        }
        return LH_BY_RISK_CLASS.getOrDefault(riskClass, ImaConstants.LH_BASE);
    }

    /**
     * 获取指定风险类别的 LH 天数（直接查 hardcoded 表）。
     *
     * @param riskClass 风险类别字符串
     * @return LH 天数；未知则返回 10
     */
    public static int getLhDaysByRiskClass(String riskClass) {
        return LH_BY_RISK_CLASS.getOrDefault(riskClass, ImaConstants.LH_BASE);
    }

    private static Map<String, String> normalizeTextMap(Map<String, String> raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (key != null && entry.getValue() != null) {
                result.put(key, entry.getValue().trim().toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static Map<String, Integer> normalizeLhMap(Map<String, Integer> raw) {
        Map<String, Integer> result = new HashMap<>();
        if (raw == null) {
            return result;
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String key = normalizeKey(entry.getKey());
            Integer value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            validateLhDays(value, key);
            result.put(key, value);
        }
        return result;
    }

    private static void validateLhDays(int value, String curveId) {
        if (!(value == 10 || value == 20 || value == 40 || value == 60 || value == 120)) {
            throw new IllegalArgumentException("流动性期限仅支持10/20/40/60/120: curveId=" + curveId);
        }
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }
}
