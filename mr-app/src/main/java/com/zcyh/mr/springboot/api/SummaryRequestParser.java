package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.FrtbDrcSummaryRequest;
import com.zcyh.mr.springboot.model.FrtbSbaSummaryRequest;
import com.zcyh.mr.springboot.model.RuleSummaryRequest;
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
    private static final List<BigDecimal> DEFAULT_VAR_QUANTILES = Arrays.asList(
            new BigDecimal("0.95"), new BigDecimal("0.99"));

    private SummaryRequestParser() {
    }

    static FrtbSbaSummaryRequest parseSba(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST,
                "persist_result", "need_decompose", "thread_count");
        return new FrtbSbaSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                readBoolean(request, "need_decompose", true),
                readNonNegativeInteger(request, "thread_count", 0));
    }

    static FrtbDrcSummaryRequest parseDrc(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST,
                "persist_result", "request_id", "job_id");
        return new FrtbDrcSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                readOptionalString(request, "request_id"),
                readOptionalString(request, "job_id"));
    }

    static RuleSummaryRequest parseRrao(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST, "persist_result");
        return new RuleSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true));
    }

    static RuleSummaryRequest parseImaCapital(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST);
        return new RuleSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                true);
    }

    static VarSummaryRequest parseVar(JSONObject request) {
        validateKeys(request, "batch_id", "data_date", RULE_ID_LIST, "persist_result", "quantiles");
        return new VarSummaryRequest(
                readBatchId(request),
                readDataDate(request),
                parseRequiredIdList(request, RULE_ID_LIST),
                readBoolean(request, "persist_result", true),
                parseQuantiles(request));
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

    private static List<BigDecimal> parseQuantiles(JSONObject request) {
        String text = readOptionalString(request, "quantiles");
        if (text == null) {
            return new ArrayList<BigDecimal>(DEFAULT_VAR_QUANTILES);
        }
        LinkedHashSet<BigDecimal> values = new LinkedHashSet<BigDecimal>();
        for (String item : text.split(",", -1)) {
            String value = trimToNull(item);
            if (value == null) {
                throw new IllegalArgumentException("quantiles 包含空项");
            }
            BigDecimal quantile;
            try {
                quantile = new BigDecimal(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("quantile 格式非法: " + value);
            }
            if (quantile.compareTo(BigDecimal.ZERO) <= 0 || quantile.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("quantile 必须在 (0,1) 区间: " + value);
            }
            BigDecimal normalized = quantile.stripTrailingZeros();
            if (!values.add(normalized)) {
                throw new IllegalArgumentException("quantiles 包含重复值: " + value);
            }
        }
        return new ArrayList<BigDecimal>(values);
    }
}
