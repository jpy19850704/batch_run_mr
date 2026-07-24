package com.zcyh.mr.springboot.measurement.frtb;

import com.zcyh.mr.springboot.measurement.aggregation.DimensionAggregationService;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbBatchCalculator;
import com.zcyh.mr.frtbsa.sba.core.FrtbResultMapper;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBPosResult;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import com.zcyh.mr.springboot.support.ResultDbDateSupport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.addUniqueIgnoreCase;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.normalizeUpperFieldName;
import static com.zcyh.mr.springboot.measurement.aggregation.AggregationRuleSupport.toFilterExpression;

/**
 * FRTB SBA 数据库输入执行服务。
 */
@Service
public class FrtbSbaDbRunnerService {
    private static final String TOTAL = "TOTAL";
    private static final String SBA_RULE_TYPE = "FRTB_SBA";
    private static final String SBA_SUM_FIELD = "SENSITIVITY_VAL_INST_CURR_CNY";
    private static final List<String> RAW_DETAIL_SCHEMA = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
            "riskFactorClass",
            "riskFactorBucket",
            "sensitivityType",
            "riskFactorType",
            "riskFactorId",
            "riskFactorVertex1",
            "riskFactorVertex2",
            "sensitivityValRptCurrCny",
            "riskWeight",
            "ws"
    ));

    private final FrtbSbaInputQueryService inputQueryService;
    private final FrtbBatchCalculator batchCalculator;
    private final FrtbResultMapper resultMapper;
    private final DimensionAggregationService dimensionAggregationService;

    public FrtbSbaDbRunnerService(FrtbSbaInputQueryService inputQueryService,
                                  FrtbBatchCalculator batchCalculator,
                                  FrtbResultMapper resultMapper,
                                  DimensionAggregationService dimensionAggregationService) {
        this.inputQueryService = inputQueryService;
        this.batchCalculator = batchCalculator;
        this.resultMapper = resultMapper;
        this.dimensionAggregationService = dimensionAggregationService;
    }

    /**
     * 读取并补齐实际参与 SBA 计算的规则快照，供结果元数据落库复用。
     */
    public JSONObject loadRuleSnapshot(String ruleId) {
        AggregationRule rule = loadExecutableRule(ruleId);
        return JSON.parseObject(JSON.toJSONString(rule, JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    public AggregationRule loadRuleDefinition(String ruleId) {
        return loadExecutableRule(ruleId);
    }

    public AggregationRule parseRuleDefinition(JSONObject ruleJson, String runtimeRuleId) {
        if (ruleJson == null) {
            throw new IllegalArgumentException("FRTB SBA 规则定义不能为空");
        }
        AggregationRule ruleDefinition = new AggregationRule();
        ruleDefinition.setRuleId(runtimeRuleId);
        ruleDefinition.setRuleType(SBA_RULE_TYPE);
        ruleDefinition.setBuildOrder(toStringList(ruleJson.get("build_order")));
        ruleDefinition.setSumFields(toStringList(ruleJson.get("sum_fields")));
        ruleDefinition.setFilterTree(toFilterExpression(ruleJson.get("filterTree")));
        ruleDefinition.setVirtualSelectionMode(trimToNull(ruleJson.getString("virtual_selection_mode")));
        ruleDefinition.setVirtualTradeIds(toStringList(ruleJson.get("virtual_trade_ids")));
        applySbaRuleDefaults(ruleDefinition);
        dimensionAggregationService.validateRule(ruleDefinition);
        return ruleDefinition;
    }

    public Map<String, Object> calculate(String batchId,
                                         String dataDate,
                                         List<AggregationRule> ruleDefinitions,
                                         boolean needDecompose,
                                         int threadCount) {
        if (ruleDefinitions == null || ruleDefinitions.isEmpty()) {
            throw new IllegalArgumentException("ruleDefinitions 不能为空");
        }
        LocalDate localDataDate = ResultDbDateSupport.localDate(dataDate);
        List<FrtbInput> inputList = new ArrayList<FrtbInput>();
        for (AggregationRule ruleDefinition : ruleDefinitions) {
            applySbaRuleDefaults(ruleDefinition);
            dimensionAggregationService.validateRule(ruleDefinition);
            List<Map<String, Object>> rows = inputQueryService.queryRuleDetailRows(batchId, dataDate, ruleDefinition);
            if (rows == null || rows.isEmpty()) {
                throw new IllegalArgumentException("未查到可用于规则汇总的 FRTB 敏感性明细: ruleId="
                        + ruleDefinition.getRuleId());
            }
            inputList.addAll(buildRuleDrivenInputs(batchId, localDataDate, ruleDefinition,
                    FrtbSbaInputValidator.validateAndNormalizeSbaRows(rows)));
        }
        if (inputList.isEmpty()) {
            throw new IllegalArgumentException("规则汇总后未生成有效的 frtb_sba 输入数据");
        }
        Map<String, List<FrtbInput>> tasks = inputQueryService.groupByRuleIdAndGroupValue(inputList);
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("规则汇总后未生成有效的 frtb_sba 组批任务");
        }
        return buildOutputWithRawDetails(
                tasks,
                batchCalculator.calculateBatch(tasks, needDecompose, threadCount));
    }

    /**
     * 在原有 SBA 汇总树之外，补充头寸级原始结果，供前端下钻复用。
     */
    private Map<String, Object> buildOutputWithRawDetails(Map<String, List<FrtbInput>> tasks,
                                                          Map<String, Map<String, Object>> batchResult) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        if (batchResult != null && !batchResult.isEmpty()) {
            output.putAll(batchResult);
        }
        output.put("__raw_detail_schema", RAW_DETAIL_SCHEMA);
        output.put("__raw_details", buildRawDetails(tasks, batchResult));
        return output;
    }

    /**
     * 基于引擎已生成的 posResults 回填原始下钻结果，使用 schema+rows 降低字段名重复带来的结果体积。
     */
    private Map<String, Object> buildRawDetails(Map<String, List<FrtbInput>> tasks,
                                                Map<String, Map<String, Object>> batchResult) {
        Map<String, Object> rawDetails = new LinkedHashMap<String, Object>();
        if (tasks == null || tasks.isEmpty() || batchResult == null || batchResult.isEmpty()) {
            return rawDetails;
        }

        for (Map.Entry<String, List<FrtbInput>> taskEntry : tasks.entrySet()) {
            String taskKey = taskEntry.getKey();
            Map<String, Object> calcResult = batchResult.get(taskKey);
            List<FrtbInput> taskInputs = taskEntry.getValue();
            if (calcResult == null || taskInputs == null || taskInputs.isEmpty()) {
                continue;
            }

            FrtbInput sample = taskInputs.get(0);
            String ruleId = trimToNull(sample.getRuleId());
            String groupType = normalizeGroupType(sample.getGroupType(), sample.getGroupValue());
            String groupValue = normalizeGroupValue(sample.getGroupValue());

            Map<String, List<?>> pojoResult = resultMapper.buildResults(calcResult, ruleId, groupType, groupValue);
            Map<String, Object> detailEntry = new LinkedHashMap<String, Object>();
            detailEntry.put("ruleId", ruleId);
            detailEntry.put("groupType", groupType);
            detailEntry.put("groupValue", groupValue);
            detailEntry.put("rows", buildRawDetailRows(pojoResult.get("posResults")));
            rawDetails.put(taskKey, detailEntry);
        }
        return rawDetails;
    }

    private List<List<Object>> buildRawDetailRows(List<?> posResults) {
        List<List<Object>> rows = new ArrayList<List<Object>>();
        if (posResults == null || posResults.isEmpty()) {
            return rows;
        }
        for (Object item : posResults) {
            if (!(item instanceof FRTBPosResult)) {
                continue;
            }
            FRTBPosResult pos = (FRTBPosResult) item;
            List<Object> row = new ArrayList<Object>(RAW_DETAIL_SCHEMA.size());
            row.add(pos.getRiskFactorClass());
            row.add(pos.getRiskFactorBucket());
            row.add(pos.getSensitivityType());
            row.add(pos.getRiskFactorType());
            row.add(pos.getRiskFactorId());
            row.add(pos.getRiskFactorVertex1());
            row.add(pos.getRiskFactorVertex2());
            row.add(pos.getSensitivityValRptCurrCny());
            row.add(pos.getRiskWeight());
            row.add(pos.getWs());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 对齐前端树根节点口径，避免空值组被写成 null。
     */
    private static String normalizeGroupValue(String groupValue) {
        String safeGroupValue = trimToNull(groupValue);
        return safeGroupValue == null ? "__EMPTY_GROUP__" : safeGroupValue;
    }

    private static String normalizeGroupType(String groupType, String groupValue) {
        String safeGroupType = trimToNull(groupType);
        if (safeGroupType != null) {
            return safeGroupType;
        }
        throw new IllegalStateException("FRTB SBA 原始明细缺少 groupType: groupValue=" + groupValue);
    }

    private AggregationRule loadExecutableRule(String ruleId) {
        AggregationRule rule = inputQueryService.loadAggregationRule(ruleId);
        applySbaRuleDefaults(rule);
        dimensionAggregationService.validateRule(rule);
        return rule;
    }

    /**
     * 校验并规范 SBA 汇总规则，规则类型必须由输入明确给出。
     */
    private static void applySbaRuleDefaults(AggregationRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("AggregationRule 不能为空");
        }
        String ruleType = trimToNull(rule.getRuleType());
        if (!SBA_RULE_TYPE.equalsIgnoreCase(ruleType)) {
            throw new IllegalArgumentException("FRTB SBA 汇总规则 rule_type 必须为 FRTB_SBA");
        }
        rule.setRuleType(SBA_RULE_TYPE);

        List<String> buildOrder = new ArrayList<String>();
        addUniqueIgnoreCase(buildOrder, TOTAL);
        for (String level : rule.getBuildOrder()) {
            addUniqueIgnoreCase(buildOrder, normalizeUpperFieldName(level));
        }
        rule.setBuildOrder(buildOrder);

        List<String> sumFields = new ArrayList<String>();
        sumFields.add(SBA_SUM_FIELD);
        rule.setSumFields(sumFields);
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

    private static String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        return trimToNull(String.valueOf(value));
    }

    private static String requireTopLevelString(JSONObject obj, String key) {
        String value = trimToNull(obj.getString(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " 必填");
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
                                                  LocalDate dataDate,
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
                    String levelValue = dimensionAggregationService.normalizeDimensionValue(row.get(level));
                    pathValues.add(levelValue);
                    groupType = level;
                    groupValue = dimensionAggregationService.buildGroupValue(pathValues);
                }

                String aggregateKey = buildAggregateKey(rule.getRuleId(), groupType, groupValue, row);
                FrtbInput input = grouped.get(aggregateKey);
                if (input == null) {
                    input = new FrtbInput();
                    input.setRuleId(rule.getRuleId());
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

    private static String buildAggregateKey(String ruleId, String groupType, String groupValue, Map<String, Object> row) {
        StringBuilder builder = new StringBuilder();
        builder.append(nullSafe(ruleId)).append('|')
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
