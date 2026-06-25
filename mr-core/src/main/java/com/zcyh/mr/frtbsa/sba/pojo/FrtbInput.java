package com.zcyh.mr.frtbsa.sba.pojo;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * FRTB SA 输入数据模型
 *
 * sensitivityValRptCurrCny 为原始敏感度（riskWeight 调整前），
 * riskWeight 由引擎 core 层内部计算并应用。
 *
 * @author system
 */
public class FrtbInput implements Serializable {

    /** 规则 ID */
    private String ruleId;

    /** 维度类型，如 "TOTAL", "portfolio", "trader" */
    private String groupType;

    /** 维度值，如 "ALL", "Book_A" */
    private String groupValue;

    /** 风险因子 ID */
    private String riskFactorId;

    /** 期限1 */
    private String riskFactorVertex1;

    /** 期限2 */
    private String riskFactorVertex2;

    /** 风险类别：GIRR, EQ, FX, CSR (non-sec), CSR (non-ctp), CMTY, CSR (ctp) */
    private String riskFactorClass;

    /** 桶编号 */
    private String riskFactorBucket;

    /** 风险因子类型 */
    private String riskFactorType;

    /** 敏感性类型：Delta, Vega, Curvature Up, Curvature Down */
    private String sensitivityType;

    /** 原始敏感度（riskWeight 调整前） */
    private BigDecimal sensitivityValRptCurrCny;

    /** 数据日期 */
    private String dataDate;

    /** 修改人 */
    private String modifier;

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

    public String getDataDate() {
        return dataDate;
    }

    public void setDataDate(String d) {
        this.dataDate = d;
    }

    public String getModifier() {
        return modifier;
    }

    public void setModifier(String m) {
        this.modifier = m;
    }
}
