package com.zcyh.mr.scenario.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 单条场景生成结果。
 */
public class ScenarioGeneratedRecord {
    private String scenarioId;
    private String subScenarioId;
    private String scenarioName;
    private String scenarioType;
    private String riskGroupId;
    private String curveType;
    private String curveCode;
    private LocalDate dataDate;
    private String termCode;
    private Integer termDays;
    private String dimension2;
    private BigDecimal originalValue;
    private BigDecimal changedValue;
    private BigDecimal shiftValue;
    private String shiftRule;
    private String modifier;

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public String getSubScenarioId() {
        return subScenarioId;
    }

    public void setSubScenarioId(String subScenarioId) {
        this.subScenarioId = subScenarioId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getScenarioType() {
        return scenarioType;
    }

    public void setScenarioType(String scenarioType) {
        this.scenarioType = scenarioType;
    }

    public String getRiskGroupId() {
        return riskGroupId;
    }

    public void setRiskGroupId(String riskGroupId) {
        this.riskGroupId = riskGroupId;
    }

    public String getCurveType() {
        return curveType;
    }

    public void setCurveType(String curveType) {
        this.curveType = curveType;
    }

    public String getCurveCode() {
        return curveCode;
    }

    public void setCurveCode(String curveCode) {
        this.curveCode = curveCode;
    }

    public LocalDate getDataDate() {
        return dataDate;
    }

    public void setDataDate(LocalDate dataDate) {
        this.dataDate = dataDate;
    }

    public String getTermCode() {
        return termCode;
    }

    public void setTermCode(String termCode) {
        this.termCode = termCode;
    }

    public Integer getTermDays() {
        return termDays;
    }

    public void setTermDays(Integer termDays) {
        this.termDays = termDays;
    }

    public String getDimension2() {
        return dimension2;
    }

    public void setDimension2(String dimension2) {
        this.dimension2 = dimension2;
    }

    public BigDecimal getOriginalValue() {
        return originalValue;
    }

    public void setOriginalValue(BigDecimal originalValue) {
        this.originalValue = originalValue;
    }

    public BigDecimal getChangedValue() {
        return changedValue;
    }

    public void setChangedValue(BigDecimal changedValue) {
        this.changedValue = changedValue;
    }

    public BigDecimal getShiftValue() {
        return shiftValue;
    }

    public void setShiftValue(BigDecimal shiftValue) {
        this.shiftValue = shiftValue;
    }

    public String getShiftRule() {
        return shiftRule;
    }

    public void setShiftRule(String shiftRule) {
        this.shiftRule = shiftRule;
    }

    public String getModifier() {
        return modifier;
    }

    public void setModifier(String modifier) {
        this.modifier = modifier;
    }
}
