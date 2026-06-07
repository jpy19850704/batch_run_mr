package com.zcyh.mr.springboot.model;

/**
 * 批次交易过滤请求。
 */
public class BatchTradeFilter {
    private String sourceType;
    private String ruleId;
    private AggregationRule.FilterExpression filterTree;

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public AggregationRule.FilterExpression getFilterTree() {
        return filterTree;
    }

    public void setFilterTree(AggregationRule.FilterExpression filterTree) {
        this.filterTree = filterTree;
    }
}
