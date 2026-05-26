package com.zcyh.mr.frtbsa.sba.pojo;

import java.math.BigDecimal;

/**
 * FRTB SA 头寸级结果
 *
 * 包含原始敏感度、引擎计算的 riskWeight、加权后 ws，
 * 以及基于 decompose 的资本贡献度和单位贡献度。
 *
 * @author system
 */
public class FRTBPosResult {

    // === 维度标识 ===
    public String treeId;
    public String groupType;
    public String groupValue;

    // === 头寸信息 ===
    public String riskFactorId;
    public String riskFactorVertex1;
    public String riskFactorVertex2;
    public String riskFactorClass;
    public String riskFactorBucket;
    public String riskFactorType;
    public String sensitivityType;

    // === 数值（riskWeight 从引擎 core 结果获取）===

    /** 原始敏感度（riskWeight 调整前） */
    public BigDecimal sensitivityValRptCurrCny;

    /** 风险权重（引擎内部使用的 rw，保证与计算一致） */
    public BigDecimal riskWeight;

    /** 加权敏感度 = sensitivityValRptCurrCny × riskWeight */
    public BigDecimal ws;

    // === 贡献度（TOTAL 维度 decompose 计算）===

    /** 分配的资本 */
    public BigDecimal contribution;

    /** 单位贡献度 = contribution / sensitivityValRptCurrCny */
    public BigDecimal unitContribution;

    // ========== 访问器方法 ==========

    public String getTreeId() {
        return treeId;
    }

    public void setTreeId(String treeId) {
        this.treeId = treeId;
    }

    public String getGroupType() {
        return groupType;
    }

    public void setGroupType(String groupType) {
        this.groupType = groupType;
    }

    public String getGroupValue() {
        return groupValue;
    }

    public void setGroupValue(String groupValue) {
        this.groupValue = groupValue;
    }

    public String getRiskFactorId() {
        return riskFactorId;
    }

    public void setRiskFactorId(String riskFactorId) {
        this.riskFactorId = riskFactorId;
    }

    public String getRiskFactorVertex1() {
        return riskFactorVertex1;
    }

    public void setRiskFactorVertex1(String v) {
        this.riskFactorVertex1 = v;
    }

    public String getRiskFactorVertex2() {
        return riskFactorVertex2;
    }

    public void setRiskFactorVertex2(String v) {
        this.riskFactorVertex2 = v;
    }

    public String getRiskFactorClass() {
        return riskFactorClass;
    }

    public void setRiskFactorClass(String c) {
        this.riskFactorClass = c;
    }

    public String getRiskFactorBucket() {
        return riskFactorBucket;
    }

    public void setRiskFactorBucket(String b) {
        this.riskFactorBucket = b;
    }

    public String getRiskFactorType() {
        return riskFactorType;
    }

    public void setRiskFactorType(String t) {
        this.riskFactorType = t;
    }

    public String getSensitivityType() {
        return sensitivityType;
    }

    public void setSensitivityType(String s) {
        this.sensitivityType = s;
    }

    public BigDecimal getSensitivityValRptCurrCny() {
        return sensitivityValRptCurrCny;
    }

    public void setSensitivityValRptCurrCny(BigDecimal v) {
        this.sensitivityValRptCurrCny = v;
    }

    public BigDecimal getRiskWeight() {
        return riskWeight;
    }

    public void setRiskWeight(BigDecimal riskWeight) {
        this.riskWeight = riskWeight;
    }

    public BigDecimal getWs() {
        return ws;
    }

    public void setWs(BigDecimal ws) {
        this.ws = ws;
    }

    public BigDecimal getContribution() {
        return contribution;
    }

    public void setContribution(BigDecimal contribution) {
        this.contribution = contribution;
    }

    public BigDecimal getUnitContribution() {
        return unitContribution;
    }

    public void setUnitContribution(BigDecimal unitContribution) {
        this.unitContribution = unitContribution;
    }
}
