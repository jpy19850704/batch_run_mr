package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.FrtbRuleTrialRequest;
import com.zcyh.mr.springboot.model.VarTrialRequest;
import com.zcyh.mr.var.VarMeasure;

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
        validateKeys(request, "batch_id", "data_date", RULE_DEFINITION_LIST,
                "quantiles", "measure", "include_detail", "request_id");
        List<JSONObject> ruleDefinitions = new ArrayList<JSONObject>();
        JSONArray array = readDefinitionArray(request);
        for (int i = 0; i < array.size(); i++) {
            JSONObject definition = readDefinition(array, i,
                    "build_order", "filterTree", "calc", "enabled", "output_order");
            definition.put("rule_id", "TEMP_VAR_" + (i + 1));
            definition.put("rule_type", "VAR");
            ruleDefinitions.add(definition);
        }
        return new VarTrialRequest(
                readBatchId(request),
                readDataDate(request),
                ruleDefinitions,
                parseQuantiles(request.get("quantiles")),
                parseMeasures(request.get("measure")),
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

    private static List<BigDecimal> parseQuantiles(Object value) {
        List<Object> items = toItems(value);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("quantiles 必填");
        }
        List<BigDecimal> quantiles = new ArrayList<BigDecimal>();
        for (Object item : items) {
            String token = trimToNull(item == null ? null : String.valueOf(item));
            if (token == null) {
                throw new IllegalArgumentException("quantiles 包含空项");
            }
            BigDecimal quantile = new BigDecimal(token);
            if (quantile.compareTo(BigDecimal.ZERO) <= 0 || quantile.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("quantile 必须在 (0,1) 区间: " + token);
            }
            quantiles.add(quantile);
        }
        return quantiles;
    }

    private static List<VarMeasure> parseMeasures(Object value) {
        List<Object> items = toItems(value);
        if (items.isEmpty()) {
            return VarMeasure.defaultMeasures();
        }
        List<VarMeasure> measures = new ArrayList<VarMeasure>();
        for (Object item : items) {
            VarMeasure measure = VarMeasure.parse(String.valueOf(item));
            if (!measures.contains(measure)) {
                measures.add(measure);
            }
        }
        return measures;
    }

    private static List<Object> toItems(Object value) {
        List<Object> items = new ArrayList<Object>();
        if (value instanceof List) {
            items.addAll((List<?>) value);
            return items;
        }
        String text = trimToNull(value == null ? null : String.valueOf(value));
        if (text != null) {
            items.addAll(Arrays.asList(text.split(",", -1)));
        }
        return items;
    }
}
