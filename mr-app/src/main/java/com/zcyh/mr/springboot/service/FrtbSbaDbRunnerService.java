package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.springboot.model.AggregationRule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FRTB SBA 数据库输入执行服务。
 */
@Service
public class FrtbSbaDbRunnerService {
    private static final String TOTAL = "TOTAL";
    private static final String DEFAULT_RULE_TYPE = "FRTB";
    private static final String SBA_SUM_FIELD = "SENSITIVITY_VAL_INST_CURR_CNY";
    private static final String[] SBA_GROUP_BY_FIELDS = new String[]{
            "RISK_FACTOR_ID",
            "RISK_FACTOR_VERTEX_1",
            "RISK_FACTOR_VERTEX_2",
            "RISK_FACTOR_CLASS",
            "RISK_FACTOR_BUCKET",
            "RISK_FACTOR_TYPE",
            "SENSITIVITY_TYPE"
    };

    private final FrtbSbaInputQueryService inputQueryService;
    private final FrtbAggregator aggregator;
    private final DimensionAggregationService dimensionAggregationService;

    public FrtbSbaDbRunnerService(FrtbSbaInputQueryService inputQueryService,
                                  FrtbAggregator aggregator,
                                  DimensionAggregationService dimensionAggregationService) {
        this.inputQueryService = inputQueryService;
        this.aggregator = aggregator;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    /**
     * 按 rule_id 执行 FRTB SBA 全流程：规则读取、底层过滤、维度汇总、SBA 计量。
     */
    public String calculateByRule(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }
        boolean needDecompose = parseNeedDecompose(req);
        int threadCount = parseThreadCount(req);
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");
        String ruleId = requireTopLevelString(req, "rule_id");

        AggregationRule rule = inputQueryService.loadAggregationRule(ruleId);
        applySbaRuleDefaults(rule);
        dimensionAggregationService.validateRule(rule);

        List<Map<String, Object>> rows = inputQueryService.queryRuleDetailRows(batchId, dataDate, rule);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("未查到可用于规则汇总的 FRTB 敏感性明细");
        }

        List<FrtbInput> inputList = buildRuleDrivenInputs(batchId, dataDate, rule, rows);
        if (inputList.isEmpty()) {
            throw new IllegalArgumentException("规则汇总后未生成有效的 frtb_sba 输入数据");
        }
        Map<String, List<FrtbInput>> tasks = inputQueryService.groupByTreeIdAndGroupValue(inputList);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("规则汇总后未生成有效的 frtb_sba 组批任务");
        }
        return JSON.toJSONString(
                aggregator.calculateBatch(tasks, needDecompose, threadCount),
                JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 前端直接传入完整 rule 定义的 FRTB SBA 计量。
     * 跳过 MR_AGG_RULE 表查询，由前端构造过滤条件和维度排序。
     */
    public String calculateByInlineRule(String payloadJson) {
        JSONObject req = JSON.parseObject(payloadJson);
        if (req == null) {
            throw new IllegalArgumentException("payload must be a json object");
        }
        boolean needDecompose = parseNeedDecompose(req);
        int threadCount = parseThreadCount(req);
        String batchId = requireTopLevelString(req, "batch_id");
        String dataDate = requireTopLevelString(req, "data_date");

        JSONObject ruleJson = req.getJSONObject("rule");
        if (ruleJson == null) {
            throw new IllegalArgumentException("rule 不能为空，需要包含 buildOrder/dimensions/filters 等");
        }
        AggregationRule rule = parseInlineRule(ruleJson);
        if (rule == null) {
            throw new IllegalArgumentException("rule 解析失败");
        }
        // 设置内联规则的默认值
        if (rule.getRuleId() == null || rule.getRuleId().isEmpty()) {
            rule.setRuleId("INLINE_" + System.currentTimeMillis());
        }

        applySbaRuleDefaults(rule);
        dimensionAggregationService.validateRule(rule);
        List<Map<String, Object>> rows = inputQueryService.queryRuleDetailRows(batchId, dataDate, rule);
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("未查到可用于规则汇总的 FRTB 敏感性明细");
        }

        List<FrtbInput> inputList = buildRuleDrivenInputs(batchId, dataDate, rule, rows);
        if (inputList.isEmpty()) {
            throw new IllegalArgumentException("规则汇总后未生成有效的 frtb_sba 输入数据");
        }
        Map<String, List<FrtbInput>> tasks = inputQueryService.groupByTreeIdAndGroupValue(inputList);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("规则汇总后未生成有效的 frtb_sba 组批任务");
        }
        Object output = aggregator.calculateBatch(tasks, needDecompose, threadCount);
        return JSON.toJSONString(output, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private static boolean parseNeedDecompose(JSONObject req) {
        Boolean needDecompose = req.getBoolean("need_decompose");
        return needDecompose == null ? Boolean.TRUE : needDecompose;
    }

    private static int parseThreadCount(JSONObject req) {
        Integer threadCount = req.getInteger("thread_count");
        if (threadCount == null) {
            return 0;
        }
        return Math.max(1, threadCount);
    }

    /**
     * 手工解析内联 rule，避免 fastjson2 在 Object/数组场景下反序列化异常。
     */
    private static AggregationRule parseInlineRule(JSONObject ruleJson) {
        if (ruleJson == null) {
            return null;
        }
        AggregationRule rule = new AggregationRule();
        rule.setRuleId(trimToNull(ruleJson.getString("ruleId")));
        rule.setRuleType(trimToNull(ruleJson.getString("ruleType")));
        rule.setRuleName(trimToNull(ruleJson.getString("ruleName")));
        rule.setBuildOrder(toStringList(ruleJson.get("buildOrder")));
        rule.setGroupByFields(toStringList(ruleJson.get("groupByFields")));
        rule.setSumFields(toStringList(ruleJson.get("sumFields")));
        rule.setDimensions(toStringMap(ruleJson.get("dimensions")));
        rule.setFilters(toFilterConditions(ruleJson.get("filters")));
        rule.setFilterTree(toFilterExpression(firstNonNull(ruleJson.get("filter_tree"), ruleJson.get("filterTree"))));
        return rule;
    }

    /**
     * 补齐 SBA 汇总规则默认项，接口只需要表达业务层级和过滤条件。
     */
    private static void applySbaRuleDefaults(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("AggregationRule 不能为空");
        }
        if (trimToNull(rule.getRuleType()) == null) {
            rule.setRuleType(DEFAULT_RULE_TYPE);
        }

        List<String> buildOrder = new ArrayList<String>();
        addUniqueIgnoreCase(buildOrder, TOTAL);
        for (String level : rule.getBuildOrder()) {
            addUniqueIgnoreCase(buildOrder, normalizeFieldName(level));
        }
        rule.setBuildOrder(buildOrder);

        Map<String, String> dimensions = normalizeDimensionMap(rule.getDimensions());
        if (dimensions.isEmpty()) {
            for (String level : buildOrder) {
                if (!TOTAL.equalsIgnoreCase(level)) {
                    dimensions.put(level, level);
                }
            }
        } else {
            for (String level : buildOrder) {
                if (!TOTAL.equalsIgnoreCase(level) && !containsKeyIgnoreCase(dimensions, level)) {
                    dimensions.put(level, level);
                }
            }
        }
        rule.setDimensions(dimensions);

        List<String> groupByFields = normalizeFieldList(rule.getGroupByFields());
        for (String mappedField : dimensions.values()) {
            addUniqueIgnoreCase(groupByFields, normalizeFieldName(mappedField));
        }
        for (String riskFactorField : SBA_GROUP_BY_FIELDS) {
            addUniqueIgnoreCase(groupByFields, riskFactorField);
        }
        rule.setGroupByFields(groupByFields);

        List<String> sumFields = new ArrayList<String>();
        sumFields.add(SBA_SUM_FIELD);
        rule.setSumFields(sumFields);
    }

    private static List<AggregationRule.FilterCondition> toFilterConditions(Object rawFilters) {
        List<AggregationRule.FilterCondition> result = new ArrayList<AggregationRule.FilterCondition>();
        if (!(rawFilters instanceof List)) {
            return result;
        }
        List<?> rows = (List<?>) rawFilters;
        for (Object item : rows) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) item;
            AggregationRule.FilterCondition condition = new AggregationRule.FilterCondition();
            String operator = asTrimmedString(row.get("operator"));
            condition.setField(asTrimmedString(row.get("field")));
            condition.setOperator(operator);
            Object rawValue = row.containsKey("value") ? row.get("value") : row.get("values");
            condition.setValue(normalizeFilterValue(operator, rawValue));
            result.add(condition);
        }
        return result;
    }

    private static AggregationRule.FilterExpression toFilterExpression(Object rawExpression) {
        if (!(rawExpression instanceof Map)) {
            return null;
        }
        Map<?, ?> row = (Map<?, ?>) rawExpression;
        AggregationRule.FilterExpression expression = new AggregationRule.FilterExpression();
        expression.setOp(asTrimmedString(firstNonNull(row.get("op"), row.get("logic"))));
        expression.setField(asTrimmedString(row.get("field")));
        String operator = asTrimmedString(row.get("operator"));
        expression.setOperator(operator);
        Object rawValue = row.containsKey("value") ? row.get("value") : row.get("values");
        expression.setValue(normalizeFilterValue(operator, rawValue));

        Object rawChildren = row.get("children");
        if (rawChildren instanceof List) {
            List<AggregationRule.FilterExpression> children = new ArrayList<AggregationRule.FilterExpression>();
            for (Object child : (List<?>) rawChildren) {
                AggregationRule.FilterExpression childExpression = toFilterExpression(child);
                if (childExpression != null) {
                    children.add(childExpression);
                }
            }
            expression.setChildren(children);
        }
        return expression;
    }

    /**
     * 过滤值归一化：in/not_in 保留数组，其它操作符按单值处理。
     */
    private static Object normalizeFilterValue(String operator, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof List) {
            List<?> values = (List<?>) rawValue;
            if ("in".equalsIgnoreCase(operator) || "not_in".equalsIgnoreCase(operator)) {
                return new ArrayList<Object>(values);
            }
            for (Object item : values) {
                if (item != null) {
                    return item;
                }
            }
            return null;
        }
        return rawValue;
    }

    private static Map<String, String> normalizeDimensionMap(Map<String, String> rawMap) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (rawMap == null || rawMap.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, String> entry : rawMap.entrySet()) {
            String key = normalizeFieldName(entry.getKey());
            String value = normalizeFieldName(entry.getValue());
            if (key != null && value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static List<String> normalizeFieldList(List<String> rawList) {
        List<String> result = new ArrayList<String>();
        if (rawList == null || rawList.isEmpty()) {
            return result;
        }
        for (String item : rawList) {
            addUniqueIgnoreCase(result, normalizeFieldName(item));
        }
        return result;
    }

    private static String normalizeFieldName(String value) {
        String safe = trimToNull(value);
        if (safe == null) {
            return null;
        }
        return safe.toUpperCase(Locale.ROOT);
    }

    private static boolean containsKeyIgnoreCase(Map<String, String> values, String target) {
        String safeTarget = trimToNull(target);
        if (values == null || values.isEmpty() || safeTarget == null) {
            return false;
        }
        for (String key : values.keySet()) {
            if (safeTarget.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static void addUniqueIgnoreCase(List<String> values, String value) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            return;
        }
        for (String item : values) {
            if (safeValue.equalsIgnoreCase(item)) {
                return;
            }
        }
        values.add(safeValue);
    }

    private static List<String> toStringList(Object rawList) {
        List<String> result = new ArrayList<String>();
        if (!(rawList instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) rawList) {
            String value = asTrimmedString(item);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private static Map<String, String> toStringMap(Object rawMap) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (!(rawMap instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawMap).entrySet()) {
            String key = asTrimmedString(entry.getKey());
            String value = asTrimmedString(entry.getValue());
            if (key != null && value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        return trimToNull(String.valueOf(value));
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<FrtbInput> buildRuleDrivenInputs(String batchId,
                                                  String dataDate,
                                                  AggregationRule rule,
                                                  List<Map<String, Object>> rows) {
        String sumField = resolveSumField(rule);
        Map<String, FrtbInput> grouped = new LinkedHashMap<String, FrtbInput>();
        for (Map<String, Object> row : rows) {
            List<String> pathValues = new ArrayList<String>();
            for (String level : rule.getBuildOrder()) {
                String groupType;
                String groupValue;
                if ("TOTAL".equalsIgnoreCase(level)) {
                    groupType = "TOTAL";
                    groupValue = "TOTAL";
                } else {
                    String fieldName = rule.getDimensions().get(level);
                    String levelValue = dimensionAggregationService.normalizeDimensionValue(row.get(fieldName));
                    pathValues.add(levelValue);
                    groupType = level;
                    groupValue = dimensionAggregationService.buildGroupValue(pathValues);
                }

                String aggregateKey = buildAggregateKey(rule.getRuleId(), groupType, groupValue, row);
                FrtbInput input = grouped.get(aggregateKey);
                if (input == null) {
                    input = new FrtbInput();
                    input.setTreeId(rule.getRuleId());
                    input.setGroupType(groupType);
                    input.setGroupValue(groupValue);
                    input.setRiskFactorId(trimToNull(stringValue(row.get("RISK_FACTOR_ID"))));
                    input.setRiskFactorVertex1(trimToNull(stringValue(row.get("RISK_FACTOR_VERTEX_1"))));
                    input.setRiskFactorVertex2(trimToNull(stringValue(row.get("RISK_FACTOR_VERTEX_2"))));
                    input.setRiskFactorClass(trimToNull(stringValue(row.get("RISK_FACTOR_CLASS"))));
                    input.setRiskFactorBucket(trimToNull(stringValue(row.get("RISK_FACTOR_BUCKET"))));
                    input.setRiskFactorType(trimToNull(stringValue(row.get("RISK_FACTOR_TYPE"))));
                    input.setSensitivityType(trimToNull(stringValue(row.get("SENSITIVITY_TYPE"))));
                    input.setSensitivityValRptCurrCny(BigDecimal.ZERO);
                    input.setDataDate(dataDate);
                    input.setModifier(batchId);
                    grouped.put(aggregateKey, input);
                }
                input.setSensitivityValRptCurrCny(
                        safeBigDecimal(input.getSensitivityValRptCurrCny()).add(safeBigDecimal(row.get(sumField))));
            }
        }
        return new ArrayList<FrtbInput>(grouped.values());
    }

    private static String resolveSumField(AggregationRule rule) {
        if (rule.getSumFields() == null || rule.getSumFields().isEmpty()) {
            throw new IllegalArgumentException("AggregationRule.sumFields 不能为空");
        }
        String sumField = trimToNull(rule.getSumFields().get(0));
        if (sumField == null) {
            throw new IllegalArgumentException("AggregationRule.sumFields[0] 不能为空");
        }
        if (!"SENSITIVITY_VAL_INST_CURR_CNY".equalsIgnoreCase(sumField)) {
            throw new IllegalArgumentException("当前 FRTB SBA 规则仅支持 sumFields[0]=SENSITIVITY_VAL_INST_CURR_CNY");
        }
        return sumField;
    }

    private static String buildAggregateKey(String treeId, String groupType, String groupValue, Map<String, Object> row) {
        StringBuilder builder = new StringBuilder();
        builder.append(nullSafe(treeId)).append('|')
                .append(nullSafe(groupType)).append('|')
                .append(nullSafe(groupValue)).append('|')
                .append(nullSafe(row.get("RISK_FACTOR_ID"))).append('|')
                .append(nullSafe(row.get("RISK_FACTOR_VERTEX_1"))).append('|')
                .append(nullSafe(row.get("RISK_FACTOR_VERTEX_2"))).append('|')
                .append(nullSafe(row.get("RISK_FACTOR_CLASS"))).append('|')
                .append(nullSafe(row.get("RISK_FACTOR_BUCKET"))).append('|')
                .append(nullSafe(row.get("RISK_FACTOR_TYPE"))).append('|')
                .append(nullSafe(row.get("SENSITIVITY_TYPE")));
        return builder.toString();
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static BigDecimal safeBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
