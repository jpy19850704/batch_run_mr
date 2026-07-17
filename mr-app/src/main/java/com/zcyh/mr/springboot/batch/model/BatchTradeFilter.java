package com.zcyh.mr.springboot.batch.model;

import com.zcyh.mr.springboot.input.db.InputFilterExpression;

/**
 * 批次交易过滤请求。
 */
public class BatchTradeFilter {
    private String sourceType;
    private String ruleId;
    private InputFilterExpression filterTree;

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

    public InputFilterExpression getFilterTree() {
        return filterTree;
    }

    public void setFilterTree(InputFilterExpression filterTree) {
        this.filterTree = filterTree;
    }
}
