package com.zcyh.mr.springboot.input.db;

/**
 * 规则定义查询行。
 */
public class RuleDefinitionRow {
    private final String ruleId;
    private final String ruleType;
    private final String ruleName;
    private final String ruleJson;

    public RuleDefinitionRow(String ruleId, String ruleType, String ruleName, String ruleJson) {
        this.ruleId = ruleId;
        this.ruleType = ruleType;
        this.ruleName = ruleName;
        this.ruleJson = ruleJson;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getRuleType() {
        return ruleType;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getRuleJson() {
        return ruleJson;
    }
}
