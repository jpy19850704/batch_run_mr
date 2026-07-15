package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 交易数据字段校验器
 * 根据 data/model/validationRules.json 运行时资源按产品类型校验交易字段
 * 支持 string/number/date/array/domain 类型校验
 */
public class TradeValidator {

    private static final String VALIDATION_RULES_RESOURCE = "data/model/validationRules.json";
    private static final JSONObject rules;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    static {
        rules = loadRules();
    }

    private static JSONObject loadRules() {
        // 校验规则是交易输入契约，资源缺失或解析失败必须中断，不能静默跳过校验。
        try (InputStream is = TradeValidator.class.getClassLoader().getResourceAsStream(VALIDATION_RULES_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("未找到交易校验规则资源: " + VALIDATION_RULES_RESOURCE);
            }
            String data = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (data.trim().isEmpty()) {
                throw new IllegalStateException("交易校验规则资源为空: " + VALIDATION_RULES_RESOURCE);
            }
            JSONObject parsed = JSON.parseObject(data);
            if (parsed == null || parsed.isEmpty()) {
                throw new IllegalStateException("交易校验规则内容为空: " + VALIDATION_RULES_RESOURCE);
            }
            return parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载交易校验规则失败: " + VALIDATION_RULES_RESOURCE, e);
        }
    }

    /**
     * 校验数据对象的字段
     *
     * @param data        待校验的 JSON 对象（交易或底层资产数据）
     * @param productCode 产品类型代码
     * @param node        数据节点名（TRADE / UNDERLYING_DATA）
     * @return 错误信息列表，空列表表示校验通过
     */
    public static List<String> validate(JSONObject data, String productCode, String node) {
        List<String> errors = new ArrayList<>();
        if (data == null) {
            return errors;
        }

        if ("TRADE".equals(node)) {
            validateRequiredText(data, "INSTRUMENT_ID", errors);
            validateRequiredText(data, "PRODUCT_CODE", errors);
        }

        // 产品专属规则：配置中声明的字段均属于输入契约，必须显式传入。
        if (productCode != null) {
            JSONObject productNodeRules = getNodeRules(productCode, node);
            if (productNodeRules != null) {
                applyRules(data, productNodeRules, errors);
            }
        }

        return errors;
    }

    /**
     * 获取指定产品和数据节点的校验规则
     */
    private static JSONObject getNodeRules(String product, String node) {
        JSONObject productRules = rules.getJSONObject(product);
        if (productRules == null)
            return null;
        return productRules.getJSONObject(node);
    }

    private static void validateRequiredText(JSONObject data, String field, List<String> errors) {
        Object val = data.get(field);
        String strVal = (val != null) ? val.toString().trim() : "";
        if (val == null || strVal.isEmpty()) {
            errors.add("缺少必填字段: " + field);
        }
    }

    /**
     * 对数据对象应用校验规则
     *
     * @param data       待校验数据
     * @param fieldRules 字段校验规则
     * @param errors     错误信息列表
     */
    private static void applyRules(JSONObject data, JSONObject fieldRules, List<String> errors) {
        if (fieldRules == null)
            return;

        for (String field : fieldRules.keySet()) {
            String rule = fieldRules.getString(field);
            Object val = data.get(field);

            // 所有配置的字段都必填：null 或空字符串报错
            String strVal = (val != null) ? val.toString().trim() : "";
            if (val == null || strVal.isEmpty()) {
                errors.add("缺少必填字段: " + field);
                continue;
            }

            // 按类型校验值格式
            if ("string".equals(rule)) {
                // 非空即通过，已在上面检查
            } else if ("number".equals(rule)) {
                validateNumber(field, val, strVal, errors);
            } else if ("date".equals(rule)) {
                validateDate(field, strVal, errors);
            } else if ("array".equals(rule)) {
                validateArray(field, val, errors);
            } else if (rule != null && rule.startsWith("domain:")) {
                validateDomain(field, strVal, rule, errors);
            }
        }
    }

    /**
     * 数字类型校验
     */
    private static void validateNumber(String field, Object val, String strVal, List<String> errors) {
        if (val instanceof Number) {
            return;
        }
        try {
            Double.parseDouble(strVal);
        } catch (NumberFormatException e) {
            errors.add(field + " 必须为数字: " + strVal);
        }
    }

    /**
     * 日期类型校验（yyyyMMdd）
     */
    private static void validateDate(String field, String strVal, List<String> errors) {
        try {
            LocalDate.parse(strVal, DATE_FMT);
        } catch (DateTimeParseException e) {
            errors.add(field + " 日期格式错误(应为yyyyMMdd): " + strVal);
        }
    }

    private static void validateArray(String field, Object val, List<String> errors) {
        if (!(val instanceof JSONArray)) {
            errors.add(field + " 必须为数组");
        }
    }

    /**
     * 枚举值域校验
     * 规则格式：domain:值1|值2|值3
     */
    private static void validateDomain(String field, String strVal, String rule, List<String> errors) {
        String domainStr = rule.substring("domain:".length());
        String[] allowedValues = domainStr.split("\\|");
        boolean matched = false;
        for (String allowedValue : allowedValues) {
            if (allowedValue.equalsIgnoreCase(strVal)) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            errors.add(field + " 值不在允许范围内: " + strVal + ", 允许值: " + domainStr);
        }
    }
}
