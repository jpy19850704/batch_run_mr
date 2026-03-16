package com.zcyh.mr.springboot.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公共维度汇总规则模型。
 */
public class AggregationRule {
    private String ruleId;
    private String ruleType;
    private String ruleName;
    private List<String> buildOrder = new ArrayList<String>();
    private Map<String, String> dimensions = new LinkedHashMap<String, String>();
    private List<String> groupByFields = new ArrayList<String>();
    private List<String> sumFields = new ArrayList<String>();
    private List<FilterCondition> filters = new ArrayList<FilterCondition>();

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

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions == null ? new LinkedHashMap<String, String>() : dimensions;
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

    public List<FilterCondition> getFilters() {
        return filters;
    }

    public void setFilters(List<FilterCondition> filters) {
        this.filters = filters == null ? new ArrayList<FilterCondition>() : filters;
    }

    /**
     * 统一过滤条件定义。
     */
    public static class FilterCondition {
        private String field;
        private String operator;
        private Object value;

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
