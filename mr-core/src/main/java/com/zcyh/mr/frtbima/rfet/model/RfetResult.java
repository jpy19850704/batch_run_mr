package com.zcyh.mr.frtbima.rfet.model;

import java.util.Collections;
import java.util.Set;

/**
 * 单个监管桶的 RFET 判定结果。
 *
 * <p>包含两类信息：
 * <ul>
 *   <li><b>桶范围</b>（{@code tenorMin/tenorMax/deltaMin/deltaMax}）：来自桶定义，
 *       用于 {@link com.zcyh.mr.imarfet.bucket.RfetModellableIndex} 做范围匹配。
 *       可建模桶内 [tenorMin, tenorMax]（及 delta 范围）的<b>所有</b>期限点均视为可建模，
 *       不局限于 RFET 实际观测到的样本点。
 *   <li><b>观测样本</b>（{@code tenorDays}）：桶内实际出现的 tenor 集合，仅供诊断/审计用。
 * </ul>
 *
 * <p>调用方（mr-app）从结果构建：
 * <ul>
 *   <li>modellable=true 的桶 → 加入 {@code RfetModellableIndex}，供 SubsetScenarioRunner 范围过滤
 *   <li>modellable=false 的桶 → 构建 NmrfBucketMeta，进入 NmrfScenarioRunner
 * </ul>
 */
public class RfetResult {

    /** 桶ID */
    private String bucketId;

    /** 曲线ID */
    private String curveId;

    /** 风险因子类型（来自 RfBucketDefinition.rfType，如 IR_SPOT / CREDIT_SPOT / EQ_VOL） */
    private String rfType;

    /** 观测数据组ID */
    private String groupId;

    /** 观测数据组类型 */
    private String groupType;

    // ---- 桶的期限/delta 范围（来自桶定义，用于范围匹配） ----

    /** 桶期限下界（天，含） */
    private int tenorMin;

    /** 桶期限上界（天，含；无上限=999999） */
    private int tenorMax;

    /**
     * delta 下界（null=一维桶）。
     * 波动率曲面桶的 delta 范围（[deltaMin, deltaMax)，最后桶闭区间）。
     */
    private Double deltaMin;

    /** delta 上界（null=一维桶） */
    private Double deltaMax;

    /**
     * 是否属于 Reduced Set（来自 RfBucketDefinition.reducedSet，透传自 RF_CONFIG）。
     * true = 压力期 RFET 也通过，可纳入 REDUCED 情景计算。
     * false = 仅 Full Set，REDUCED 情景中该桶保留基准值。
     */
    private boolean reducedSet;

    // ---- 观测样本信息（仅供诊断/审计，不用于可建模范围判定） ----

    /** 桶内实际观测到的期限天数集合（样本） */
    private Set<Integer> tenorDays;

    /** 是否可建模（true=MODELLABLE，false=NMRF） */
    private boolean modellable;

    /**
     * 是否通过 Own Bucket 补救（MAR31.15）。
     * true 表示该桶未通过标准监管桶判定（24条+90天间隔），
     * 但因同 curveId 下全部桶观测总数 >= 100 而补救为 MODELLABLE。
     */
    private boolean passedViaOwnBucket;

    /** 桶内有效观测条数 */
    private int observationCount;

    /**
     * 12个月窗口中各90天分段内的最小观测条数（MAR31.12 周期覆盖规则检查结果）。
     * -1 表示观测数为0（无法计算）。
     * 若此值 < 4 则规则2失败。
     */
    private int minObservationsInAnyPeriod;

    /** 判定依据说明（含失败原因） */
    private String reason;

    public RfetResult() {
    }

    public RfetResult(String bucketId, String curveId, String rfType,
                      Set<Integer> tenorDays, boolean modellable, boolean passedViaOwnBucket,
                      int observationCount, int minObservationsInAnyPeriod, String reason) {
        this.bucketId = bucketId;
        this.curveId = curveId;
        this.rfType = rfType;
        this.tenorDays = tenorDays;
        this.modellable = modellable;
        this.passedViaOwnBucket = passedViaOwnBucket;
        this.observationCount = observationCount;
        this.minObservationsInAnyPeriod = minObservationsInAnyPeriod;
        this.reason = reason;
    }

    public String getBucketId() { return bucketId; }
    public void setBucketId(String bucketId) { this.bucketId = bucketId; }

    public String getCurveId() { return curveId; }
    public void setCurveId(String curveId) { this.curveId = curveId; }

    public String getRfType() { return rfType; }
    public void setRfType(String rfType) { this.rfType = rfType; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupType() { return groupType; }
    public void setGroupType(String groupType) { this.groupType = groupType; }

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

    public Set<Integer> getTenorDays() {
        return tenorDays == null ? Collections.emptySet() : Collections.unmodifiableSet(tenorDays);
    }
    public void setTenorDays(Set<Integer> tenorDays) { this.tenorDays = tenorDays; }

    public boolean isModellable() { return modellable; }
    public void setModellable(boolean modellable) { this.modellable = modellable; }

    public boolean isPassedViaOwnBucket() { return passedViaOwnBucket; }
    public void setPassedViaOwnBucket(boolean passedViaOwnBucket) { this.passedViaOwnBucket = passedViaOwnBucket; }

    public int getObservationCount() { return observationCount; }
    public void setObservationCount(int observationCount) { this.observationCount = observationCount; }

    public int getMinObservationsInAnyPeriod() { return minObservationsInAnyPeriod; }
    public void setMinObservationsInAnyPeriod(int minObservationsInAnyPeriod) { this.minObservationsInAnyPeriod = minObservationsInAnyPeriod; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
