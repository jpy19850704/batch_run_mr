package com.zcyh.mr.product.basic.validation;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 执行交易Info字段上的输入定义。
 */
public final class ProductInputFieldValidator {
    private ProductInputFieldValidator() {
    }

    public static void validate(
            Object tradeInfo,
            String productCode,
            TradeValidationCollector errors) {
        if (tradeInfo == null) {
            errors.add(null, "交易信息为空");
            return;
        }
        for (Map.Entry<String, Field> entry : inputFields(tradeInfo.getClass()).entrySet()) {
            validateField(tradeInfo, productCode, entry.getKey(), entry.getValue(), errors);
        }
    }

    static Map<String, Field> inputFields(Class<?> type) {
        Map<String, Field> fields = new LinkedHashMap<String, Field>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getAnnotation(ProductInputField.class) == null) {
                    continue;
                }
                fields.putIfAbsent(resolveFieldName(field), field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    static Set<String> knownFieldNames(Class<?> type) {
        Set<String> fields = new LinkedHashSet<String>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(resolveFieldName(field));
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private static void validateField(
            Object tradeInfo,
            String productCode,
            String fieldName,
            Field field,
            TradeValidationCollector errors) {
        ProductInputField rule = field.getAnnotation(ProductInputField.class);
        Object value;
        try {
            field.setAccessible(true);
            value = field.get(tradeInfo);
        } catch (IllegalAccessException ex) {
            errors.add(fieldName, "字段不可读取");
            return;
        }

        boolean required = rule.required() || isRequiredFor(rule.requiredFor(), productCode);
        if (required && isEmpty(value)) {
            errors.add(fieldName, "不能为空");
            return;
        }
        if (isEmpty(value)) {
            return;
        }

        validateAllowedValues(fieldName, value, rule, errors);
        validateLength(fieldName, value, rule, errors);
        validateNumber(fieldName, value, rule, errors);
    }

    private static void validateAllowedValues(
            String fieldName,
            Object value,
            ProductInputField rule,
            TradeValidationCollector errors) {
        String[] allowedValues = rule.allowedValues();
        if (allowedValues == null || allowedValues.length == 0) {
            return;
        }
        String actual = String.valueOf(value);
        for (String allowed : allowedValues) {
            boolean matched = rule.ignoreCase() ? allowed.equalsIgnoreCase(actual) : allowed.equals(actual);
            if (matched) {
                return;
            }
        }
        errors.add(fieldName, "值不在允许范围内: " + actual + ", 允许值: " + String.join("|", allowedValues));
    }

    private static void validateLength(
            String fieldName,
            Object value,
            ProductInputField rule,
            TradeValidationCollector errors) {
        if (rule.length() >= 0 && String.valueOf(value).length() != rule.length()) {
            errors.add(fieldName, "长度必须为 " + rule.length());
        }
    }

    private static void validateNumber(
            String fieldName,
            Object value,
            ProductInputField rule,
            TradeValidationCollector errors) {
        if (!rule.finite() && rule.min().isEmpty() && rule.max().isEmpty()) {
            return;
        }
        BigDecimal decimal;
        try {
            if (value instanceof Double && !Double.isFinite((Double) value)) {
                throw new NumberFormatException();
            }
            if (value instanceof Float && !Float.isFinite((Float) value)) {
                throw new NumberFormatException();
            }
            decimal = new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            errors.add(fieldName, "必须为有限数");
            return;
        }
        validateMin(fieldName, decimal, rule, errors);
        validateMax(fieldName, decimal, rule, errors);
    }

    private static void validateMin(
            String fieldName,
            BigDecimal value,
            ProductInputField rule,
            TradeValidationCollector errors) {
        if (rule.min().isEmpty()) {
            return;
        }
        BigDecimal min = new BigDecimal(rule.min());
        int compared = value.compareTo(min);
        if (compared < 0 || (!rule.minInclusive() && compared == 0)) {
            errors.add(fieldName, rule.minInclusive() ? "不能小于 " + min : "必须大于 " + min);
        }
    }

    private static void validateMax(
            String fieldName,
            BigDecimal value,
            ProductInputField rule,
            TradeValidationCollector errors) {
        if (rule.max().isEmpty()) {
            return;
        }
        BigDecimal max = new BigDecimal(rule.max());
        int compared = value.compareTo(max);
        if (compared > 0 || (!rule.maxInclusive() && compared == 0)) {
            errors.add(fieldName, rule.maxInclusive() ? "不能大于 " + max : "必须小于 " + max);
        }
    }

    private static boolean isRequiredFor(String[] productCodes, String productCode) {
        if (productCodes == null || productCodes.length == 0 || productCode == null) {
            return false;
        }
        for (String value : productCodes) {
            if (value.equalsIgnoreCase(productCode)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmpty(Object value) {
        return value == null || (value instanceof CharSequence && value.toString().trim().isEmpty());
    }

    private static String resolveFieldName(Field field) {
        JSONField jsonField = field.getAnnotation(JSONField.class);
        if (jsonField != null && jsonField.name() != null && !jsonField.name().trim().isEmpty()) {
            return jsonField.name().trim();
        }
        return toUpperSnakeCase(field.getName());
    }

    private static String toUpperSnakeCase(String name) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char current = name.charAt(i);
            if (Character.isUpperCase(current) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(current));
        }
        return result.toString().toUpperCase(Locale.ROOT);
    }
}
