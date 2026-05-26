package com.zcyh.mr.frtbima.rfet.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 监管桶聚合统计结果，RFET 判定的统计单元。
 * 粒度为 (curveId, tenorMin, tenorMax[, strikeMin, strikeMax]) 桶。
 *
 * <p>除命中观测数外，还保留所有命中观测的日期列表，
 * 供 RfetChecker 执行 90 天间隔规则检查（MAR31.12）。
 */
public class RiskFactorBucket {

    /** 桶ID（由 RfBucketDefinition.getBucketId() 生成） */
    private String bucketId;

    /** 曲线ID */
    private String curveId;

    /** 桶内已命中的所有期限天数（来自实际观测的 tenorDays） */
    private Set<Integer> tenorDays = new LinkedHashSet<>();

    /** 桶内所有有效实际价格观测的日期列表（保留重复，供间隔检查排序使用） */
    private List<LocalDate> observationDates = new ArrayList<>();

    public RiskFactorBucket() {
    }

    public RiskFactorBucket(String bucketId, String curveId) {
        this.bucketId = bucketId;
        this.curveId = curveId;
    }

    /**
     * 记录一条命中观测。
     *
     * @param tenor           期限天数（加入 tenorDays 集合）
     * @param observationDate 观测日期（加入 observationDates，供间隔检查使用）
     */
    public void addObservation(int tenor, LocalDate observationDate) {
        this.tenorDays.add(tenor);
        if (observationDate != null) {
            this.observationDates.add(observationDate);
        }
    }

    /** 有效观测总数 */
    public int getObservationCount() {
        return observationDates.size();
    }

    /**
     * 返回按日期升序排列的观测日期列表（不可修改副本）。
     * 供 RfetChecker 进行 90 天间隔规则检查。
     */
    public List<LocalDate> getSortedObservationDates() {
        List<LocalDate> sorted = new ArrayList<>(observationDates);
        Collections.sort(sorted);
        return Collections.unmodifiableList(sorted);
    }

    // ==================== 访问器方法 ====================

    public String getBucketId() { return bucketId; }
    public void setBucketId(String bucketId) { this.bucketId = bucketId; }

    public String getCurveId() { return curveId; }
    public void setCurveId(String curveId) { this.curveId = curveId; }

    public Set<Integer> getTenorDays() { return Collections.unmodifiableSet(tenorDays); }
    public void setTenorDays(Set<Integer> tenorDays) { this.tenorDays = tenorDays; }

    public List<LocalDate> getObservationDates() { return Collections.unmodifiableList(observationDates); }
    public void setObservationDates(List<LocalDate> observationDates) {
        this.observationDates = observationDates != null ? new ArrayList<>(observationDates) : new ArrayList<>();
    }
}
