package com.zcyh.mr.scenario.model;

import java.math.BigDecimal;

/**
 * 标准化后的市场数据点。
 */
public class ScenarioMarketSeries {
    private String curveType;
    private String curveCode;
    private java.time.LocalDate dataDate;
    private String termCode;
    private Integer termDays;
    private String dimension2;
    private BigDecimal value;

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

    public java.time.LocalDate getDataDate() {
        return dataDate;
    }

    public void setDataDate(java.time.LocalDate dataDate) {
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

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }
}
