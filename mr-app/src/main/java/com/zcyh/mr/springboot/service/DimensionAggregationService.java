package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.model.AggregationRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公共维度汇总服务。
 *
 * <p>当前先提供规则校验和通用工具方法，后续再承接 FRTB / VaR / 损益等模块的规则驱动汇总逻辑。</p>
 */
@Service
public class DimensionAggregationService {
    private static final String TOTAL = "TOTAL";
    private static final String NULL_DIMENSION_VALUE = "NULL";
    private static final String OP_EQ = "=";
    private static final String OP_NE = "!=";
    private static final String OP_GT = ">";
    private static final String OP_GE = ">=";
    private static final String OP_LT = "<";
    private static final String OP_LE = "<=";
    private static final String OP_IN = "in";
    private static final String OP_NOT_IN = "not_in";
    private static final String OP_CONTAINS = "contains";
    private static final String OP_NOT_CONTAINS = "not_contains";

    /**
     * 校验汇总规则是否合法。
     */
    public void validateRule(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("AggregationRule 不能为空");
        }
        if (trimToNull(rule.getRuleId()) == null) {
            throw new IllegalArgumentException("AggregationRule.ruleId 不能为空");
        }

        List<String> normalizedOrder = normalizeBuildOrder(rule.getBuildOrder());
        if (normalizedOrder.isEmpty()) {
            throw new IllegalArgumentException("AggregationRule.buildOrder 不能为空");
        }
        List<String> normalizedGroupByFields = normalizeFieldList(rule.getGroupByFields());
        if (normalizedGroupByFields.isEmpty()) {
            throw new IllegalArgumentException("AggregationRule.groupByFields 不能为空");
        }
        List<String> normalizedSumFields = normalizeFieldList(rule.getSumFields());
        if (normalizedSumFields.isEmpty()) {
            throw new IllegalArgumentException("AggregationRule.sumFields 不能为空");
        }
        Map<String, String> normalizedDimensions = normalizeDimensions(rule.getDimensions());
        for (String level : normalizedOrder) {
            if (TOTAL.equalsIgnoreCase(level)) {
                continue;
            }
            String mappedField = normalizedDimensions.get(level);
            if (mappedField == null) {
                throw new IllegalArgumentException("AggregationRule.dimensions 缺少 buildOrder 层级映射: " + level);
            }
            if (!containsIgnoreCase(normalizedGroupByFields, mappedField)) {
                throw new IllegalArgumentException("AggregationRule.dimensions[" + level + "]=" + mappedField + " 不在 groupByFields 中");
            }
        }
        rule.setBuildOrder(normalizedOrder);
        rule.setGroupByFields(normalizedGroupByFields);
        rule.setSumFields(normalizedSumFields);
        rule.setDimensions(normalizedDimensions);

        List<AggregationRule.FilterCondition> filters = rule.getFilters();
        if (filters == null) {
            return;
        }
        for (int i = 0; i < filters.size(); i++) {
            AggregationRule.FilterCondition condition = filters.get(i);
            if (condition == null) {
                throw new IllegalArgumentException("AggregationRule.filters[" + i + "] 不能为空");
            }
            if (trimToNull(condition.getField()) == null) {
                throw new IllegalArgumentException("AggregationRule.filters[" + i + "].field 不能为空");
            }
            condition.setField(trimToNull(condition.getField()).toUpperCase());
            String operator = normalizeOperator(condition.getOperator());
            if (operator == null) {
                throw new IllegalArgumentException("AggregationRule.filters[" + i + "].operator 不支持: " + condition.getOperator());
            }
            condition.setOperator(operator);
        }
    }

    /**
     * 规范化汇总顺序，去空、去重并保持顺序。
     */
    public List<String> normalizeBuildOrder(List<String> buildOrder) {
        List<String> normalized = new ArrayList<String>();
        if (buildOrder == null || buildOrder.isEmpty()) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (String item : buildOrder) {
            String safe = trimToNull(item);
            if (safe == null) {
                continue;
            }
            safe = safe.toUpperCase();
            if (seen.add(safe)) {
                normalized.add(safe);
            }
        }
        return normalized;
    }

    /**
     * 将维度路径值按固定分隔符拼成 groupValue，便于后续从结果中还原树路径。
     */
    public String buildGroupValue(List<String> pathValues) {
        if (pathValues == null || pathValues.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<String>();
        for (String item : pathValues) {
            values.add(normalizeDimensionValue(item));
        }
        if (values.isEmpty()) {
            return null;
        }
        return String.join("&", values);
    }

    private static String normalizeOperator(String operator) {
        String safe = trimToNull(operator);
        if (safe == null) {
            return null;
        }
        if (OP_EQ.equals(safe)
                || OP_NE.equals(safe)
                || OP_GT.equals(safe)
                || OP_GE.equals(safe)
                || OP_LT.equals(safe)
                || OP_LE.equals(safe)
                || OP_IN.equalsIgnoreCase(safe)
                || OP_NOT_IN.equalsIgnoreCase(safe)
                || OP_CONTAINS.equalsIgnoreCase(safe)
                || OP_NOT_CONTAINS.equalsIgnoreCase(safe)) {
            return safe.toLowerCase();
        }
        return null;
    }

    /**
     * 统一维度值标准化规则：空值一律按 NULL 处理。
     */
    public String normalizeDimensionValue(Object value) {
        if (value == null) {
            return NULL_DIMENSION_VALUE;
        }
        String safe = trimToNull(String.valueOf(value));
        if (safe == null) {
            return NULL_DIMENSION_VALUE;
        }
        return safe;
    }

    private static Map<String, String> normalizeDimensions(Map<String, String> dimensions) {
        Map<String, String> normalized = new LinkedHashMap<String, String>();
        if (dimensions == null || dimensions.isEmpty()) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : dimensions.entrySet()) {
            String key = trimToNull(entry.getKey());
            String value = trimToNull(entry.getValue());
            if (key == null || value == null) {
                continue;
            }
            normalized.put(key, value.toUpperCase());
        }
        return normalized;
    }

    private static List<String> normalizeFieldList(List<String> fields) {
        List<String> normalized = new ArrayList<String>();
        if (fields == null || fields.isEmpty()) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<String>();
        for (String item : fields) {
            String safe = trimToNull(item);
            if (safe == null) {
                continue;
            }
            if (seen.add(safe)) {
                normalized.add(safe);
            }
        }
        return normalized;
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || values.isEmpty() || trimToNull(target) == null) {
            return false;
        }
        for (String value : values) {
            if (target.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
