package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.FrtbDrcSummaryRequest;
import com.zcyh.mr.springboot.model.FrtbSbaSummaryRequest;
import com.zcyh.mr.springboot.model.RuleSummaryRequest;
import com.zcyh.mr.springboot.model.SummaryCleanupMode;
import com.zcyh.mr.springboot.model.VarCalculation;
import com.zcyh.mr.springboot.model.VarSummaryRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

/**
 * 汇总接口请求解析器。
 */
final class SummaryRequestParser {
    private static final String RULE_ID_LIST = "rule_id_list";
    private static final String CALCULATIONS = "calculations";
    private static final String CLEANUP_MODE = "cleanupMode";

    private SummaryRequestParser() {
    }

    static FrtbSbaSummaryRequest parseSba(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST,
                "persist_result", CLEANUP_MODE, "need_decompose", "thread_count");
        return new FrtbSbaSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                readCleanupMode(request),
                readBoolean(request, "need_decompose", true),
                readNonNegativeInteger(request, "thread_count", 0));
    }

    static FrtbDrcSummaryRequest parseDrc(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST,
                "persist_result", CLEANUP_MODE, "request_id", "job_id");
        return new FrtbDrcSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                readCleanupMode(request),
                readOptionalString(request, "request_id"),
                readOptionalString(request, "job_id"));
    }

    static RuleSummaryRequest parseRrao(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST, "persist_result", CLEANUP_MODE);
        return new RuleSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                readCleanupMode(request));
    }

    static RuleSummaryRequest parseImaCapital(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST, "persist_result", CLEANUP_MODE);
        return new RuleSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                readCleanupMode(request));
    }

    static VarSummaryRequest parseVar(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", CALCULATIONS,
                "persist_result", CLEANUP_MODE);
        return new VarSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseVarCalculations(request),
                readBoolean(request, "persist_result", true),
                readCleanupMode(request));
    }

    private static void validateKeys(JSONObject request, String... allowedKeys) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        Set<String> allowed = new LinkedHashSet<String>(Arrays.asList(allowedKeys));
        for (String key : request.keySet()) {
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
        try {
            LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE);
            return dataDate;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("data_date 不是有效日期: " + dataDate);
        }
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

    private static SummaryCleanupMode readCleanupMode(JSONObject request) {
        String value = readOptionalString(request, CLEANUP_MODE);
        if (value == null) {
            return SummaryCleanupMode.FULL;
        }
        try {
            return SummaryCleanupMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cleanupMode 仅支持 FULL 或 RULE");
        }
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

    private static List<String> parseRequiredIdList(JSONObject request, String key) {
        String text = readRequiredString(request, key);
        String[] items = text.split(",", -1);
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        for (String item : items) {
            String value = trimToNull(item);
            if (value == null) {
                throw new IllegalArgumentException(key + " 包含空项");
            }
            if (!values.add(value)) {
                throw new IllegalArgumentException(key + " 包含重复值: " + value);
            }
        }
        return new ArrayList<String>(values);
    }

    private static List<VarCalculation> parseVarCalculations(JSONObject request) {
        Object raw = request.get(CALCULATIONS);
        if (!(raw instanceof JSONArray) || ((JSONArray) raw).isEmpty()) {
            throw new IllegalArgumentException("calculations 必须为非空数组");
        }
        JSONArray array = (JSONArray) raw;
        List<VarCalculation> calculations = new ArrayList<VarCalculation>();
        Set<String> calculationKeys = new LinkedHashSet<String>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("calculations[" + i + "] 必须为对象");
            }
            validateKeys(item, "ruleId", "scenarioId");
            String ruleId = readRequiredString(item, "ruleId");
            String scenarioId = readRequiredString(item, "scenarioId");
            String calculationKey = ruleId + "\u0000" + scenarioId;
            if (!calculationKeys.add(calculationKey)) {
                throw new IllegalArgumentException("calculations 包含重复计算项: ruleId="
                        + ruleId + ", scenarioId=" + scenarioId);
            }
            calculations.add(new VarCalculation(ruleId, scenarioId, null));
        }
        return calculations;
    }
}
