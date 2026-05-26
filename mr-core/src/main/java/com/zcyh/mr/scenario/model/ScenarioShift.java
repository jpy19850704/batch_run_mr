package com.zcyh.mr.scenario.model;

import java.math.BigDecimal;

/**
 * 场景冲击值。
 */
public class ScenarioShift {
    private BigDecimal shiftValue;
    private String shiftRule;

    public ScenarioShift() {
    }

    public ScenarioShift(BigDecimal shiftValue, String shiftRule) {
        this.shiftValue = shiftValue;
        this.shiftRule = shiftRule;
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
}
