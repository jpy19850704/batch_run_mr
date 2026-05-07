package com.zcyh.mr.springboot.model;

import com.alibaba.fastjson2.annotation.JSONField;

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
    @JSONField(name = "group_by_fields")
    private List<String> groupByFields = new ArrayList<String>();
    @JSONField(name = "sum_fields")
    private List<String> sumFields = new ArrayList<String>();
    @JSONField(name = "filter_tree")
    private FilterExpression filterTree;

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

    public List<String> getGroupByFields() {
        return groupByFields;
    }

    public void setGroupByFields(List<String> groupByFields) {
        this.groupByFields = groupByFields == null ? new ArrayList<String>() : groupByFields;
    }

    public List<String> getSumFields() {
        return sumFields;
    }

    public void setSumFields(List<String> sumFields) {
        this.sumFields = sumFields == null ? new ArrayList<String>() : sumFields;
    }

    public FilterExpression getFilterTree() {
        return filterTree;
    }

    public void setFilterTree(FilterExpression filterTree) {
        this.filterTree = filterTree;
    }

    /**
     * 复杂过滤表达式树节点。
     */
    public static class FilterExpression {
        private String op;
        private List<FilterExpression> children = new ArrayList<FilterExpression>();
        private String field;
        private String operator;
        private Object value;

        public String getOp() {
            return op;
        }

        public void setOp(String op) {
            this.op = op;
        }

        public List<FilterExpression> getChildren() {
            return children;
        }

        public void setChildren(List<FilterExpression> children) {
            this.children = children == null ? new ArrayList<FilterExpression>() : children;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }
    }
}
