package com.zcyh.mr.frtbima.common;

import java.util.HashMap;
import java.util.Map;

/**
 * MAR33.6 流动性期限 hardcoded 映射表。
 * riskClass → LH 天数（固定值，不可外部覆盖）。
 *
 * <p>构造时传入 RF_CONFIG 的 curveId→riskClass 映射，
 * getLhDays(curveId) 先查 riskClass，再查 hardcoded 表。
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

    /**
     * @param riskClassByCurveId RF_CONFIG 中 curveId → riskClass 的映射
     */
    public LiquidityHorizonTable(Map<String, String> riskClassByCurveId) {
        this.riskClassByCurveId = riskClassByCurveId != null ? riskClassByCurveId : new HashMap<>();
    }

    /**
     * 获取指定曲线对应的流动性期限天数。
     *
     * @param curveId 曲线ID
     * @return LH 天数；若 curveId 或 riskClass 未知，返回默认值 10
     */
    public int getLhDays(String curveId) {
        String riskClass = riskClassByCurveId.get(curveId);
        if (riskClass == null) {
            return ImaConstants.LH_BASE; // 未配置曲线，保守取最小 LH
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
}
