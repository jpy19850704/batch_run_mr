package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * IMA 不可建模中间结果。
 */
public class ImaNmrfResult {

    private String batchId;
    private LocalDate dataDate;
    private String ruleId;
    private String groupType;
    private String groupValue;
    private Integer groupOrder;
    private BigDecimal ses;
    private BigDecimal idioCreditSumSq;
    private BigDecimal idioEquitySumSq;
    private BigDecimal otherCorrTerm;
    private BigDecimal otherIdioTerm;
    private Integer nmrfCount;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public LocalDate getDataDate() { return dataDate; }
    public void setDataDate(LocalDate dataDate) { this.dataDate = dataDate; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getGroupType() { return groupType; }
    public void setGroupType(String groupType) { this.groupType = groupType; }

    public String getGroupValue() { return groupValue; }
    public void setGroupValue(String groupValue) { this.groupValue = groupValue; }

    public Integer getGroupOrder() { return groupOrder; }
    public void setGroupOrder(Integer groupOrder) { this.groupOrder = groupOrder; }

    public BigDecimal getSes() { return ses; }
    public void setSes(BigDecimal ses) { this.ses = ses; }

    public BigDecimal getIdioCreditSumSq() { return idioCreditSumSq; }
    public void setIdioCreditSumSq(BigDecimal idioCreditSumSq) { this.idioCreditSumSq = idioCreditSumSq; }

    public BigDecimal getIdioEquitySumSq() { return idioEquitySumSq; }
    public void setIdioEquitySumSq(BigDecimal idioEquitySumSq) { this.idioEquitySumSq = idioEquitySumSq; }

    public BigDecimal getOtherCorrTerm() { return otherCorrTerm; }
    public void setOtherCorrTerm(BigDecimal otherCorrTerm) { this.otherCorrTerm = otherCorrTerm; }

    public BigDecimal getOtherIdioTerm() { return otherIdioTerm; }
    public void setOtherIdioTerm(BigDecimal otherIdioTerm) { this.otherIdioTerm = otherIdioTerm; }

    public Integer getNmrfCount() { return nmrfCount; }
    public void setNmrfCount(Integer nmrfCount) { this.nmrfCount = nmrfCount; }
}
