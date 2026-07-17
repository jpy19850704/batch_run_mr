package com.zcyh.mr.springboot.measurement.aggregation;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.springboot.input.db.InputFilterExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * 公共维度汇总规则模型。
 */
public class AggregationRule {
    @JSONField(name = "rule_id")
    private String ruleId;
    @JSONField(name = "rule_type")
    private String ruleType;
    @JSONField(name = "rule_name")
    private String ruleName;
    @JSONField(name = "build_order")
    private List<String> buildOrder = new ArrayList<String>();
    @JSONField(name = "sum_fields")
    private List<String> sumFields = new ArrayList<String>();
    @JSONField(name = "virtual_selection_mode")
    private String virtualSelectionMode;
    @JSONField(name = "virtual_trade_ids")
    private List<String> virtualTradeIds = new ArrayList<String>();
    private InputFilterExpression filterTree;

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public List<String> getBuildOrder() {
        return buildOrder;
    }

    public void setBuildOrder(List<String> buildOrder) {
        this.buildOrder = buildOrder == null ? new ArrayList<String>() : buildOrder;
    }

    public List<String> getSumFields() {
        return sumFields;
    }

    public void setSumFields(List<String> sumFields) {
        this.sumFields = sumFields == null ? new ArrayList<String>() : sumFields;
    }

    public String getVirtualSelectionMode() {
        return virtualSelectionMode;
    }

    public void setVirtualSelectionMode(String virtualSelectionMode) {
        this.virtualSelectionMode = virtualSelectionMode;
    }

    public List<String> getVirtualTradeIds() {
        return virtualTradeIds;
    }

    public void setVirtualTradeIds(List<String> virtualTradeIds) {
        this.virtualTradeIds = virtualTradeIds == null ? new ArrayList<String>() : virtualTradeIds;
    }

    public InputFilterExpression getFilterTree() {
        return filterTree;
    }

    public void setFilterTree(InputFilterExpression filterTree) {
        this.filterTree = filterTree;
    }
}
