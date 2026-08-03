package com.zcyh.mr.product.basic.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * validationRules.json交易附加校验。
 */
public final class TradeValidationRuleEnhancer {
    private static final Logger log = LoggerFactory.getLogger(TradeValidationRuleEnhancer.class);
    private static final String RESOURCE = "data/model/validationRules.json";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final JSONObject RULES = loadRules();

    private TradeValidationRuleEnhancer() {
    }

    public static void validate(
            Object tradeInfo,
            JSONObject source,
            String productCode,
            String node,
            TradeValidationCollector errors) {
        if (tradeInfo == null || source == null || productCode == null || node == null || RULES.isEmpty()) {
            return;
        }
        JSONObject productRules = RULES.getJSONObject(productCode);
        JSONObject fieldRules = productRules == null ? null : productRules.getJSONObject(node);
        if (fieldRules == null || fieldRules.isEmpty()) {
            return;
        }
        Set<String> knownFields = ProductInputFieldValidator.knownFieldNames(tradeInfo.getClass());
        for (String field : fieldRules.keySet()) {
            if (!knownFields.contains(field) || !source.containsKey(field)) {
                continue;
            }
            Object value = source.get(field);
            if (value == null || value.toString().trim().isEmpty()) {
                continue;
            }
            applyRule(field, value, fieldRules.getString(field), errors);
        }
    }

    public static void validatePresentFields(
            JSONObject source,
            String productCode,
            String node,
            Set<String> knownFields,
            TradeValidationCollector errors) {
        if (source == null || productCode == null || node == null || knownFields == null || RULES.isEmpty()) {
            return;
        }
        JSONObject productRules = RULES.getJSONObject(productCode);
        JSONObject fieldRules = productRules == null ? null : productRules.getJSONObject(node);
        if (fieldRules == null) {
            return;
        }
        for (String field : fieldRules.keySet()) {
            if (!knownFields.contains(field) || !source.containsKey(field)) {
                continue;
            }
            Object value = source.get(field);
            if (value == null || value.toString().trim().isEmpty()) {
                continue;
            }
            applyRule(field, value, fieldRules.getString(field), errors);
        }
    }

    private static void applyRule(
            String field,
            Object value,
            String rule,
            TradeValidationCollector errors) {
        if ("string".equals(rule)) {
            return;
        }
        if ("number".equals(rule)) {
            try {
                double number = Double.parseDouble(value.toString());
                if (!Double.isFinite(number)) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                errors.add(field, "必须为有限数: " + value);
            }
            return;
        }
        if ("date".equals(rule)) {
            try {
                LocalDate.parse(value.toString(), DATE_FORMAT);
            } catch (DateTimeParseException ex) {
                errors.add(field, "日期格式错误，应为yyyy-MM-dd: " + value);
            }
            return;
        }
        if ("array".equals(rule)) {
            if (!(value instanceof JSONArray)) {
                errors.add(field, "必须为数组");
            }
            return;
        }
        if (rule != null && rule.startsWith("domain:")) {
            String domain = rule.substring("domain:".length());
            for (String allowed : domain.split("\\|")) {
                if (allowed.equalsIgnoreCase(value.toString())) {
                    return;
                }
            }
            errors.add(field, "值不在附加允许范围内: " + value + ", 允许值: " + domain);
        }
    }

    private static JSONObject loadRules() {
        try (InputStream input = TradeValidationRuleEnhancer.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                log.warn("交易附加校验配置不存在，已忽略: {}", RESOURCE);
                return new JSONObject();
            }
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject parsed = JSON.parseObject(text);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception ex) {
            log.warn("交易附加校验配置无效，已忽略: {}", RESOURCE, ex);
            return new JSONObject();
        }
    }
}
