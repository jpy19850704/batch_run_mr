package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * IMA 不可建模中间结果。
 */
public class ImaNmrfResult {

    private String batchId;
    private String dataDate;
    private String ruleId;
    private String groupType;
    private String groupValue;
    private Integer groupOrder;
    private String nmrfType;
    private String rfetBucketId;
    private String riskFactorId;
    private BigDecimal upPnl;
    private BigDecimal downPnl;
    private BigDecimal stressLoss;
    private String selectedDirection;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getDataDate() { return dataDate; }
    public void setDataDate(String dataDate) { this.dataDate = dataDate; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getGroupType() { return groupType; }
    public void setGroupType(String groupType) { this.groupType = groupType; }

    public String getGroupValue() { return groupValue; }
    public void setGroupValue(String groupValue) { this.groupValue = groupValue; }

    public Integer getGroupOrder() { return groupOrder; }
    public void setGroupOrder(Integer groupOrder) { this.groupOrder = groupOrder; }

    public String getNmrfType() { return nmrfType; }
    public void setNmrfType(String nmrfType) { this.nmrfType = nmrfType; }

    public String getRfetBucketId() { return rfetBucketId; }
    public void setRfetBucketId(String rfetBucketId) { this.rfetBucketId = rfetBucketId; }

    public String getRiskFactorId() { return riskFactorId; }
    public void setRiskFactorId(String riskFactorId) { this.riskFactorId = riskFactorId; }

    public BigDecimal getUpPnl() { return upPnl; }
    public void setUpPnl(BigDecimal upPnl) { this.upPnl = upPnl; }

    public BigDecimal getDownPnl() { return downPnl; }
    public void setDownPnl(BigDecimal downPnl) { this.downPnl = downPnl; }

    public BigDecimal getStressLoss() { return stressLoss; }
    public void setStressLoss(BigDecimal stressLoss) { this.stressLoss = stressLoss; }

    public String getSelectedDirection() { return selectedDirection; }
    public void setSelectedDirection(String selectedDirection) { this.selectedDirection = selectedDirection; }
}
