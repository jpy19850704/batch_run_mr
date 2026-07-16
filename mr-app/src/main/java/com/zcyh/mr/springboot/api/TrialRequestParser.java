package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.FrtbRuleTrialRequest;
import com.zcyh.mr.springboot.model.VarCalculation;
import com.zcyh.mr.springboot.model.VarTrialRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 各类临时规则试算请求解析器。
 */
final class TrialRequestParser {
    private static final String RULE_DEFINITION_LIST = "rule_definition_list";
    private static final String CALCULATIONS = "calculations";

    private TrialRequestParser() {
    }

    static FrtbRuleTrialRequest parseSba(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_DEFINITION_LIST,
                "need_decompose", "thread_count");
        List<AggregationRule> ruleDefinitions = new ArrayList<AggregationRule>();
        JSONArray array = readDefinitionArray(request);
        for (int i = 0; i < array.size(); i++) {
            JSONObject definition = readDefinition(array, i,
                    "build_order", "filterTree", "virtual_selection_mode", "virtual_trade_ids");
            AggregationRule ruleDefinition = JSON.parseObject(
                    definition.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain),
                    AggregationRule.class);
            ruleDefinition.setRuleId("TEMP_SBA_" + (i + 1));
            ruleDefinition.setRuleType("FRTB_SBA");
            ruleDefinitions.add(ruleDefinition);
        }
        return new FrtbRuleTrialRequest(
                readBatchId(request),
                readDataDate(request),
                ruleDefinitions,
                readBoolean(request, "need_decompose", true),
                readNonNegativeInteger(request, "thread_count", 0));
    }

    static FrtbRuleTrialRequest parseDrc(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_DEFINITION_LIST);
        List<AggregationRule> ruleDefinitions = new ArrayList<AggregationRule>();
        JSONArray array = readDefinitionArray(request);
        for (int i = 0; i < array.size(); i++) {
            JSONObject definition = readDefinition(array, i, "build_order", "filterTree");
            AggregationRule ruleDefinition = JSON.parseObject(
                    definition.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain),
                    AggregationRule.class);
            ruleDefinition.setRuleId("TEMP_DRC_" + (i + 1));
            ruleDefinition.setRuleType("FRTB_DRC");
            ruleDefinitions.add(ruleDefinition);
        }
        return new FrtbRuleTrialRequest(
                readBatchId(request),
                readDataDate(request),
                ruleDefinitions,
                false,
                0);
    }

    static VarTrialRequest parseVar(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", CALCULATIONS,
                "include_detail", "request_id");
        List<VarCalculation> calculations = new ArrayList<VarCalculation>();
        Object rawCalculations = request.get(CALCULATIONS);
        if (!(rawCalculations instanceof JSONArray) || ((JSONArray) rawCalculations).isEmpty()) {
            throw new IllegalArgumentException("calculations 必须为非空数组");
        }
        JSONArray array = (JSONArray) rawCalculations;
        Set<String> calculationKeys = new LinkedHashSet<String>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject calculation = array.getJSONObject(i);
            if (calculation == null) {
                throw new IllegalArgumentException("calculations[" + i + "] 必须为对象");
            }
            validateKeys(calculation, "ruleId", "scenarioId", "rule");
            String ruleId = readRequiredString(calculation, "ruleId");
            String scenarioId = readRequiredString(calculation, "scenarioId");
            String calculationKey = ruleId + "\u0000" + scenarioId;
            if (!calculationKeys.add(calculationKey)) {
                throw new IllegalArgumentException("calculations 包含重复计算项: ruleId="
                        + ruleId + ", scenarioId=" + scenarioId);
            }
            JSONObject rule = calculation.getJSONObject("rule");
            if (rule == null) {
                throw new IllegalArgumentException("calculations[" + i + "].rule 必填");
            }
            JSONObject definition = JSON.parseObject(
                    rule.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
            validateKeys(definition, "build_order", "filterTree", "calc", "enabled", "output_order",
                    "quantiles", "measure");
            if (definition.getJSONArray("build_order") == null
                    || definition.getJSONArray("build_order").isEmpty()) {
                throw new IllegalArgumentException("calculations[" + i + "].rule.build_order 不能为空");
            }
            definition.put("rule_id", ruleId);
            definition.put("rule_type", "VAR");
            calculations.add(new VarCalculation(ruleId, scenarioId, definition));
        }
        return new VarTrialRequest(
                readBatchId(request),
                readDataDate(request),
                calculations,
                readBoolean(request, "include_detail", false),
                readOptionalString(request, "request_id"));
    }

    private static JSONArray readDefinitionArray(JSONObject request) {
        Object value = request.get(RULE_DEFINITION_LIST);
        if (!(value instanceof JSONArray) || ((JSONArray) value).isEmpty()) {
            throw new IllegalArgumentException(RULE_DEFINITION_LIST + " 必须为非空数组");
        }
        return (JSONArray) value;
    }

    private static JSONObject readDefinition(JSONArray array, int index, String... allowedKeys) {
        JSONObject definition = array.getJSONObject(index);
        if (definition == null) {
            throw new IllegalArgumentException(RULE_DEFINITION_LIST + "[" + index + "] 必须为对象");
        }
        validateKeys(definition, allowedKeys);
        if (definition.getJSONArray("build_order") == null
                || definition.getJSONArray("build_order").isEmpty()) {
            throw new IllegalArgumentException(RULE_DEFINITION_LIST + "[" + index + "].build_order 不能为空");
        }
        return JSON.parseObject(definition.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private static void validateKeys(JSONObject object, String... allowedKeys) {
        if (object == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        Set<String> allowed = new LinkedHashSet<String>(Arrays.asList(allowedKeys));
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("不支持的请求参数: " + key);
            }
        }
    }

    private static String readBatchId(JSONObject request) {
        return readRequiredString(request, "batch_id");
    }

    private static String readDataDate(JSONObject request) {
        String dataDate = readRequiredString(request, "data_date");
        if (!dataDate.matches("\\d{8}")) {
            throw new IllegalArgumentException("data_date 格式必须为 yyyyMMdd");
        }
        LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
        return dataDate;
    }

    private static String readRequiredString(JSONObject request, String key) {
        String value = readOptionalString(request, key);
        if (value == null) {
            throw new IllegalArgumentException("参数缺失: " + key);
        }
        return value;
    }

    private static String readOptionalString(JSONObject request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("参数必须为字符串: " + key);
        }
        return trimToNull((String) value);
    }

    private static boolean readBoolean(JSONObject request, String key, boolean defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException("参数必须为布尔值: " + key);
        }
        return (Boolean) value;
    }

    private static int readNonNegativeInteger(JSONObject request, String key, int defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("参数必须为整数: " + key);
        }
        int result = ((Number) value).intValue();
        if (result < 0 || new BigDecimal(value.toString()).compareTo(BigDecimal.valueOf(result)) != 0) {
            throw new IllegalArgumentException("参数必须为非负整数: " + key);
        }
        return result;
    }

}
