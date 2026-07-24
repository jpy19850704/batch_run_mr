package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * IMA ES 中间明细结果。
 */
public class ImaEsResultDetail {

    private String batchId;
    private LocalDate dataDate;
    private String ruleId;
    private String groupType;
    private String groupValue;
    private Integer groupOrder;
    private String scenarioType;
    private BigDecimal confidenceLevel;
    private Integer liquidityHorizonDays;
    private BigDecimal allEs;
    private BigDecimal irEs;
    private BigDecimal csEs;
    private BigDecimal fxEs;
    private BigDecimal eqEs;
    private BigDecimal commEs;

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

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public BigDecimal getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(BigDecimal confidenceLevel) { this.confidenceLevel = confidenceLevel; }

    public Integer getLiquidityHorizonDays() { return liquidityHorizonDays; }
    public void setLiquidityHorizonDays(Integer liquidityHorizonDays) { this.liquidityHorizonDays = liquidityHorizonDays; }

    public BigDecimal getAllEs() { return allEs; }
    public void setAllEs(BigDecimal allEs) { this.allEs = allEs; }

    public BigDecimal getIrEs() { return irEs; }
    public void setIrEs(BigDecimal irEs) { this.irEs = irEs; }

    public BigDecimal getCsEs() { return csEs; }
    public void setCsEs(BigDecimal csEs) { this.csEs = csEs; }

    public BigDecimal getFxEs() { return fxEs; }
    public void setFxEs(BigDecimal fxEs) { this.fxEs = fxEs; }

    public BigDecimal getEqEs() { return eqEs; }
    public void setEqEs(BigDecimal eqEs) { this.eqEs = eqEs; }

    public BigDecimal getCommEs() { return commEs; }
    public void setCommEs(BigDecimal commEs) { this.commEs = commEs; }
}
