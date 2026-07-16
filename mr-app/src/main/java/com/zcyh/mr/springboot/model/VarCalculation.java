package com.zcyh.mr.springboot.model;

import com.alibaba.fastjson2.JSONObject;

/**
 * VaR 单次规则与情景计算项。
 */
public class VarCalculation {
    private final String ruleId;
    private final String scenarioId;
    private final JSONObject ruleDefinition;

    public VarCalculation(String ruleId, String scenarioId, JSONObject ruleDefinition) {
        this.ruleId = ruleId;
        this.scenarioId = scenarioId;
        this.ruleDefinition = ruleDefinition;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public JSONObject getRuleDefinition() {
        return ruleDefinition;
    }
}
