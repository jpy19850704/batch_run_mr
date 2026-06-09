package com.zcyh.mr.scenario.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 场景控制定义。
 */
public class ScenarioDefinition {
    private String scenarioId;
    private String scenarioName;
    private String scenarioType;
    private Boolean reducedSetFlag;
    private String curveType;
    private String curveCode;
    private String riskGroupId;
    private String termCode;
    private Integer termDays;
    private BigDecimal shockValue;
    private String scenarioShiftRule;
    private Integer scenarioNo;
    private Integer holdingPeriod;
    private Integer jumpDayNo;
    private Integer increaseDays;
    private String holidayCalendarCode;
    private LocalDate startDate;
    private LocalDate endDate;

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
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

    public Boolean getReducedSetFlag() {
        return reducedSetFlag;
    }

    public void setReducedSetFlag(Boolean reducedSetFlag) {
        this.reducedSetFlag = reducedSetFlag;
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

    public String getRiskGroupId() {
        return riskGroupId;
    }

    public void setRiskGroupId(String riskGroupId) {
        this.riskGroupId = riskGroupId;
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

    public BigDecimal getShockValue() {
        return shockValue;
    }

    public void setShockValue(BigDecimal shockValue) {
        this.shockValue = shockValue;
    }

    public String getScenarioShiftRule() {
        return scenarioShiftRule;
    }

    public void setScenarioShiftRule(String scenarioShiftRule) {
        this.scenarioShiftRule = scenarioShiftRule;
    }

    public Integer getScenarioNo() {
        return scenarioNo;
    }

    public void setScenarioNo(Integer scenarioNo) {
        this.scenarioNo = scenarioNo;
    }

    public Integer getHoldingPeriod() {
        return holdingPeriod;
    }

    public void setHoldingPeriod(Integer holdingPeriod) {
        this.holdingPeriod = holdingPeriod;
    }

    public Integer getJumpDayNo() {
        return jumpDayNo;
    }

    public void setJumpDayNo(Integer jumpDayNo) {
        this.jumpDayNo = jumpDayNo;
    }

    public Integer getIncreaseDays() {
        return increaseDays;
    }

    public void setIncreaseDays(Integer increaseDays) {
        this.increaseDays = increaseDays;
    }

    public String getHolidayCalendarCode() {
        return holidayCalendarCode;
    }

    public void setHolidayCalendarCode(String holidayCalendarCode) {
        this.holidayCalendarCode = holidayCalendarCode;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

}
