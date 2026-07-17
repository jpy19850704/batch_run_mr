package com.zcyh.mr.springboot.measurement.var;

final class VarResolvedCalculation {
    final VarRuleConfig ruleConfig;
    final String scenarioId;

    VarResolvedCalculation(VarRuleConfig ruleConfig, String scenarioId) {
        this.ruleConfig = ruleConfig;
        this.scenarioId = scenarioId;
    }
}
