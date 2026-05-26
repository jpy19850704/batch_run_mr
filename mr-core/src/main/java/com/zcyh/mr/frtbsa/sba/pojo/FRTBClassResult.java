package com.zcyh.mr.frtbsa.sba.pojo;

import java.math.BigDecimal;

/**
 * FRTB SA 风险类别级汇总结果
 *
 * 包含独立计算资本和基于 TOTAL decompose 的分摊资本
 * （按 Delta/Vega/Curvature × Normal/High/Low 细分）。
 *
 * @author system
 */
public class FRTBClassResult {

    // === 维度标识 ===
    public String treeId;
    public String groupType;
    public String groupValue;

    // === 风险类别 ===
    public String riskFactorClass;

    /** 最终选定场景标识 (normal/high/low) */
    public String maxSign;

    // === 独立计算资本 ===

    /** 最终资本 = max(normal, high, low) */
    public BigDecimal riskCharge;

    public BigDecimal normalDelta, highDelta, lowDelta;
    public BigDecimal normalVega, highVega, lowVega;
    public BigDecimal normalCurvature, highCurvature, lowCurvature;

    /** 各 sensType 的加权敏感度汇总*/
    public BigDecimal deltaWs, vegaWs, curvatureWs;

    // === 分摊资本（基于 TOTAL decompose 聚合，按 sensType × scenario）===

    public BigDecimal allocDeltaNormal, allocDeltaHigh, allocDeltaLow;
    public BigDecimal allocVegaNormal, allocVegaHigh, allocVegaLow;
    public BigDecimal allocCurvatureNormal, allocCurvatureHigh, allocCurvatureLow;

    /** 总分摊资本*/
    public BigDecimal allocatedCapital;

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

    public String getRiskFactorClass() {
        return riskFactorClass;
    }

    public void setRiskFactorClass(String riskFactorClass) {
        this.riskFactorClass = riskFactorClass;
    }

    public String getMaxSign() {
        return maxSign;
    }

    public void setMaxSign(String maxSign) {
        this.maxSign = maxSign;
    }

    public BigDecimal getRiskCharge() {
        return riskCharge;
    }

    public void setRiskCharge(BigDecimal riskCharge) {
        this.riskCharge = riskCharge;
    }

    public BigDecimal getNormalDelta() {
        return normalDelta;
    }

    public void setNormalDelta(BigDecimal v) {
        this.normalDelta = v;
    }

    public BigDecimal getHighDelta() {
        return highDelta;
    }

    public void setHighDelta(BigDecimal v) {
        this.highDelta = v;
    }

    public BigDecimal getLowDelta() {
        return lowDelta;
    }

    public void setLowDelta(BigDecimal v) {
        this.lowDelta = v;
    }

    public BigDecimal getNormalVega() {
        return normalVega;
    }

    public void setNormalVega(BigDecimal v) {
        this.normalVega = v;
    }

    public BigDecimal getHighVega() {
        return highVega;
    }

    public void setHighVega(BigDecimal v) {
        this.highVega = v;
    }

    public BigDecimal getLowVega() {
        return lowVega;
    }

    public void setLowVega(BigDecimal v) {
        this.lowVega = v;
    }

    public BigDecimal getNormalCurvature() {
        return normalCurvature;
    }

    public void setNormalCurvature(BigDecimal v) {
        this.normalCurvature = v;
    }

    public BigDecimal getHighCurvature() {
        return highCurvature;
    }

    public void setHighCurvature(BigDecimal v) {
        this.highCurvature = v;
    }

    public BigDecimal getLowCurvature() {
        return lowCurvature;
    }

    public void setLowCurvature(BigDecimal v) {
        this.lowCurvature = v;
    }

    public BigDecimal getDeltaWs() {
        return deltaWs;
    }

    public void setDeltaWs(BigDecimal v) {
        this.deltaWs = v;
    }

    public BigDecimal getVegaWs() {
        return vegaWs;
    }

    public void setVegaWs(BigDecimal v) {
        this.vegaWs = v;
    }

    public BigDecimal getCurvatureWs() {
        return curvatureWs;
    }

    public void setCurvatureWs(BigDecimal v) {
        this.curvatureWs = v;
    }

    public BigDecimal getAllocDeltaNormal() {
        return allocDeltaNormal;
    }

    public void setAllocDeltaNormal(BigDecimal v) {
        this.allocDeltaNormal = v;
    }

    public BigDecimal getAllocDeltaHigh() {
        return allocDeltaHigh;
    }

    public void setAllocDeltaHigh(BigDecimal v) {
        this.allocDeltaHigh = v;
    }

    public BigDecimal getAllocDeltaLow() {
        return allocDeltaLow;
    }

    public void setAllocDeltaLow(BigDecimal v) {
        this.allocDeltaLow = v;
    }

    public BigDecimal getAllocVegaNormal() {
        return allocVegaNormal;
    }

    public void setAllocVegaNormal(BigDecimal v) {
        this.allocVegaNormal = v;
    }

    public BigDecimal getAllocVegaHigh() {
        return allocVegaHigh;
    }

    public void setAllocVegaHigh(BigDecimal v) {
        this.allocVegaHigh = v;
    }

    public BigDecimal getAllocVegaLow() {
        return allocVegaLow;
    }

    public void setAllocVegaLow(BigDecimal v) {
        this.allocVegaLow = v;
    }

    public BigDecimal getAllocCurvatureNormal() {
        return allocCurvatureNormal;
    }

    public void setAllocCurvatureNormal(BigDecimal v) {
        this.allocCurvatureNormal = v;
    }

    public BigDecimal getAllocCurvatureHigh() {
        return allocCurvatureHigh;
    }

    public void setAllocCurvatureHigh(BigDecimal v) {
        this.allocCurvatureHigh = v;
    }

    public BigDecimal getAllocCurvatureLow() {
        return allocCurvatureLow;
    }

    public void setAllocCurvatureLow(BigDecimal v) {
        this.allocCurvatureLow = v;
    }

    public BigDecimal getAllocatedCapital() {
        return allocatedCapital;
    }

    public void setAllocatedCapital(BigDecimal v) {
        this.allocatedCapital = v;
    }
}
