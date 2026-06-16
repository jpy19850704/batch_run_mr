package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * 单次 ES 计算结果。
 */
public class EsResult {

    /** 情景类型：STRESS_REDUCED / NORMAL_FULL / NORMAL_REDUCED */
    private String scenarioType;

    /** ES 置信水平 */
    private BigDecimal confidenceLevel;

    /** 流动性期限天数（10/20/40/60/120），-1 表示流动性调整后的总 ES */
    private int lhDays;

    /** 风险类别（IR/FX/EQ/COMM），null 表示全类别聚合 */
    private String riskClass;

    /** ES 值（97.5% 置信度，MAR33.4） */
    private BigDecimal esValue;

    public EsResult() {
    }

    public EsResult(String scenarioType, BigDecimal confidenceLevel, int lhDays, String riskClass, BigDecimal esValue) {
        this.scenarioType = scenarioType;
        this.confidenceLevel = confidenceLevel;
        this.lhDays = lhDays;
        this.riskClass = riskClass;
        this.esValue = esValue;
    }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public BigDecimal getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(BigDecimal confidenceLevel) { this.confidenceLevel = confidenceLevel; }

    public int getLhDays() { return lhDays; }
    public void setLhDays(int lhDays) { this.lhDays = lhDays; }

    public String getRiskClass() { return riskClass; }
    public void setRiskClass(String riskClass) { this.riskClass = riskClass; }

    public BigDecimal getEsValue() { return esValue; }
    public void setEsValue(BigDecimal esValue) { this.esValue = esValue; }
}
