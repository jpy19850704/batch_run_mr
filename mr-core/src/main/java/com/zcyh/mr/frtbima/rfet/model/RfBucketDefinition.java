package com.zcyh.mr.frtbima.rfet.model;

/**
 * 监管桶定义，由 {@link com.zcyh.mr.imarfet.bucket.RegulatoryBucketTable} 自动生成。
 *
 * <p>桶类型：
 * <ul>
 *   <li><b>一维桶</b>（非波动率）：deltaMin/deltaMax = null，仅按期限天数分桶。
 *   <li><b>二维桶</b>（波动率曲面）：deltaMin/deltaMax 均设定，同时按期限和 delta（ITM概率）分桶。
 * </ul>
 *
 * <p>bucketId 命名规则：
 * <ul>
 *   <li>一维：{curveId}_{tenorMin}_{tenorMax}
 *   <li>二维：{curveId}_{tenorMin}_{tenorMax}_DL{deltaMin}_{deltaMax}
 * </ul>
 */
public class RfBucketDefinition {

    /** 曲线ID（如 CNY_SWAP、ICBC_CDS、CSI300_VOL） */
    private String curveId;

    /**
     * 风险因子类型（rfType），决定监管桶行选择。
     * 合法值：IR_SPOT / FX_SPOT / COMM_SPOT / EQ_SPOT / CREDIT_SPOT
     *        / IR_VOL / EQ_VOL / FX_VOL / COMM_VOL
     */
    private String rfType;

    /** 桶期限下界（天数，含） */
    private int tenorMin;

    /** 桶期限上界（天数，含；无上限用 999999） */
    private int tenorMax;

    /**
     * delta 下界（含，仅波动率曲面桶使用，null 表示一维桶）。
     * δ = 期权到期时 ITM 的概率，范围 [0, 1]（Row D）。
     */
    private Double deltaMin;

    /**
     * delta 上界（仅波动率曲面桶使用，null 表示一维桶）。
     * 区间规则：[deltaMin, deltaMax)，最后一桶（deltaMax=1.0）使用闭区间。
     */
    private Double deltaMax;

    /**
     * 是否属于 Reduced Set（压力期 RFET 通过标志）。
     * true = 该桶在压力期历史窗口内也满足 RFET 条件，可纳入 Reduced Set 情景计算。
     * false = 仅在当前期通过（Full Set only），REDUCED 情景中该桶保留基准值。
     * 由 RF_CONFIG 表的 IS_REDUCED_SET 列维护（0/1），压力期至少每年复审一次。
     */
    private boolean reducedSet;

    public RfBucketDefinition() {
    }

    /** 一维桶构造（非波动率产品） */
    public RfBucketDefinition(String curveId, String rfType, int tenorMin, int tenorMax) {
        this.curveId  = curveId;
        this.rfType   = rfType;
        this.tenorMin = tenorMin;
        this.tenorMax = tenorMax;
    }

    /** 二维桶构造（波动率曲面，Row C × Row D） */
    public RfBucketDefinition(String curveId, String rfType,
                               int tenorMin, int tenorMax,
                               Double deltaMin, Double deltaMax) {
        this.curveId   = curveId;
        this.rfType    = rfType;
        this.tenorMin  = tenorMin;
        this.tenorMax  = tenorMax;
        this.deltaMin  = deltaMin;
        this.deltaMax  = deltaMax;
    }

    /**
     * 返回桶ID（唯一标识）。
     * 一维：{curveId}_{tenorMin}_{tenorMax}
     * 二维：{curveId}_{tenorMin}_{tenorMax}_DL{deltaMin}_{deltaMax}
     */
    public String getBucketId() {
        String base = curveId + "_" + tenorMin + "_" + tenorMax;
        if (deltaMin == null || deltaMax == null) {
            return base;
        }
        return base + "_DL" + formatDouble(deltaMin) + "_" + formatDouble(deltaMax);
    }

    /**
     * 判断给定 (tenorDays, delta) 是否属于本桶（完整二维匹配）。
     *
     * <p>匹配规则：
     * <ul>
     *   <li>期限维度：tenorMin <= tenorDays <= tenorMax
     *   <li>delta 维度（仅二维桶）：[deltaMin, deltaMax) 左闭右开；
     *       最后一桶（deltaMax=1.0）使用闭区间 [deltaMin, 1.0]
     * </ul>
     *
     * @param tenorDays 期限天数
     * @param delta     ITM 概率（非波动率产品传 null）
     * @return true 表示该观测点归属本桶
     */
    public boolean containsPoint(int tenorDays, Double delta) {
        // 期限维度
        if (tenorDays < tenorMin || tenorDays > tenorMax) {
            return false;
        }
        // delta 维度（仅二维桶检查）
        if (deltaMin != null && deltaMax != null) {
            if (delta == null) return false;
            if (delta < deltaMin) return false;
            // 最后一桶（deltaMax=1.0）使用闭区间；其他桶左闭右开
            if (deltaMax < 1.0) {
                if (delta >= deltaMax) return false;
            } else {
                if (delta > deltaMax) return false;
            }
        }
        return true;
    }

    /**
     * 向后兼容方法：仅按期限天数判断（一维桶使用）。
     * 等价于 containsPoint(tenorDays, null)。
     */
    public boolean containsTenor(int tenorDays) {
        return containsPoint(tenorDays, null);
    }

    /** 判断本桶是否为波动率曲面的二维桶（含 delta 维度） */
    public boolean hasDeltaDimension() {
        return deltaMin != null && deltaMax != null;
    }

    // ==================== 访问器方法 ====================

    public String getCurveId() { return curveId; }
    public void setCurveId(String curveId) { this.curveId = curveId; }

    public String getRfType() { return rfType; }
    public void setRfType(String rfType) { this.rfType = rfType; }

    public int getTenorMin() { return tenorMin; }
    public void setTenorMin(int tenorMin) { this.tenorMin = tenorMin; }

    public int getTenorMax() { return tenorMax; }
    public void setTenorMax(int tenorMax) { this.tenorMax = tenorMax; }

    public Double getDeltaMin() { return deltaMin; }
    public void setDeltaMin(Double deltaMin) { this.deltaMin = deltaMin; }

    public Double getDeltaMax() { return deltaMax; }
    public void setDeltaMax(Double deltaMax) { this.deltaMax = deltaMax; }

    public boolean isReducedSet() { return reducedSet; }
    public void setReducedSet(boolean reducedSet) { this.reducedSet = reducedSet; }

    // ==================== 工具方法 ====================

    private static String formatDouble(double val) {
        if (val == Math.floor(val) && !Double.isInfinite(val)) {
            return String.valueOf((long) val);
        }
        return String.valueOf(val);
    }
}
