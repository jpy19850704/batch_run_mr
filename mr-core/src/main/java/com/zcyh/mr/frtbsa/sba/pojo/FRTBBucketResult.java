package com.zcyh.mr.frtbsa.sba.pojo;

import java.math.BigDecimal;

/**
 * FRTB SA 桶级聚合结果
 *
 * 包含每个 bucket 在 Normal/High/Low 三个场景下的 Kb/Sb/Sbb 值。
 *
 * @author system
 */
public class FRTBBucketResult {

    // === 维度标识 ===
    public String ruleId;
    public String groupType;
    public String groupValue;

    // === 桶信息 ===
    public String riskFactorClass;
    public String riskFactorBucket;
    public String sensitivityType;

    // === Normal 场景 ===
    public BigDecimal KbuM;
    public BigDecimal KbdM;
    public BigDecimal KbM;
    public BigDecimal KbMM;
    public BigDecimal SbM;
    public BigDecimal SbbM;

    // === High 场景 ===
    public BigDecimal KbuH;
    public BigDecimal KbdH;
    public BigDecimal KbH;
    public BigDecimal KbHH;
    public BigDecimal SbH;
    public BigDecimal SbbH;

    // === Low 场景 ===
    public BigDecimal KbuL;
    public BigDecimal KbdL;
    public BigDecimal KbL;
    public BigDecimal KbLL;
    public BigDecimal SbL;
    public BigDecimal SbbL;

    // ========== 访问器方法 ==========

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
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

    public void setRiskFactorClass(String c) {
        this.riskFactorClass = c;
    }

    public String getRiskFactorBucket() {
        return riskFactorBucket;
    }

    public void setRiskFactorBucket(String b) {
        this.riskFactorBucket = b;
    }

    public String getSensitivityType() {
        return sensitivityType;
    }

    public void setSensitivityType(String s) {
        this.sensitivityType = s;
    }

    public BigDecimal getKbuM() {
        return KbuM;
    }

    public void setKbuM(BigDecimal v) {
        KbuM = v;
    }

    public BigDecimal getKbdM() {
        return KbdM;
    }

    public void setKbdM(BigDecimal v) {
        KbdM = v;
    }

    public BigDecimal getKbM() {
        return KbM;
    }

    public void setKbM(BigDecimal v) {
        KbM = v;
    }

    public BigDecimal getKbMM() {
        return KbMM;
    }

    public void setKbMM(BigDecimal v) {
        KbMM = v;
    }

    public BigDecimal getSbM() {
        return SbM;
    }

    public void setSbM(BigDecimal v) {
        SbM = v;
    }

    public BigDecimal getSbbM() {
        return SbbM;
    }

    public void setSbbM(BigDecimal v) {
        SbbM = v;
    }

    public BigDecimal getKbuH() {
        return KbuH;
    }

    public void setKbuH(BigDecimal v) {
        KbuH = v;
    }

    public BigDecimal getKbdH() {
        return KbdH;
    }

    public void setKbdH(BigDecimal v) {
        KbdH = v;
    }

    public BigDecimal getKbH() {
        return KbH;
    }

    public void setKbH(BigDecimal v) {
        KbH = v;
    }

    public BigDecimal getKbHH() {
        return KbHH;
    }

    public void setKbHH(BigDecimal v) {
        KbHH = v;
    }

    public BigDecimal getSbH() {
        return SbH;
    }

    public void setSbH(BigDecimal v) {
        SbH = v;
    }

    public BigDecimal getSbbH() {
        return SbbH;
    }

    public void setSbbH(BigDecimal v) {
        SbbH = v;
    }

    public BigDecimal getKbuL() {
        return KbuL;
    }

    public void setKbuL(BigDecimal v) {
        KbuL = v;
    }

    public BigDecimal getKbdL() {
        return KbdL;
    }

    public void setKbdL(BigDecimal v) {
        KbdL = v;
    }

    public BigDecimal getKbL() {
        return KbL;
    }

    public void setKbL(BigDecimal v) {
        KbL = v;
    }

    public BigDecimal getKbLL() {
        return KbLL;
    }

    public void setKbLL(BigDecimal v) {
        KbLL = v;
    }

    public BigDecimal getSbL() {
        return SbL;
    }

    public void setSbL(BigDecimal v) {
        SbL = v;
    }

    public BigDecimal getSbbL() {
        return SbbL;
    }

    public void setSbbL(BigDecimal v) {
        SbbL = v;
    }
}
