package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 交易数据字段校验器
 * 根据 validationRules.json 配置文件按产品类型校验交易字段
 * 支持 string/number/date/domain 四种类型校验
 */
public class TradeValidator {

    private static final JSONObject rules;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    static {
        // 启动时加载校验规则配置文件，加载失败则不校验
        JSONObject loaded = null;
        try {
            java.io.InputStream is = TradeValidator.class.getResourceAsStream("validationRules.json");
            if (is != null) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                String data = sb.toString();
                if (!data.isEmpty()) {
                    loaded = JSON.parseObject(data);
                }
            }
        } catch (Exception e) {
            // 配置文件加载失败，不校验
        }
        rules = (loaded != null) ? loaded : new JSONObject();
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
        if (data == null || rules.isEmpty()) {
            return errors;
        }

        // 通用规则（_common）：字段在数据中不存在则跳过（该产品不涉及此字段）
        JSONObject commonNodeRules = getNodeRules("_common", node);
        if (commonNodeRules != null) {
            applyRules(data, commonNodeRules, errors, true);
        }

        // 产品专属规则：所有字段都必填
        if (productCode != null) {
            JSONObject productNodeRules = getNodeRules(productCode, node);
            if (productNodeRules != null) {
                applyRules(data, productNodeRules, errors, false);
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

    /**
     * 对数据对象应用校验规则
     *
     * @param data       待校验数据
     * @param fieldRules 字段校验规则
     * @param errors     错误信息列表
     * @param isCommon   是否为通用规则（通用规则中字段不存在则跳过）
     */
    private static void applyRules(JSONObject data, JSONObject fieldRules, List<String> errors, boolean isCommon) {
        if (fieldRules == null)
            return;

        for (String field : fieldRules.keySet()) {
            String rule = fieldRules.getString(field);
            Object val = data.get(field);

            // 通用规则：字段在数据中不存在则跳过（该产品不涉及此字段）
            if (isCommon && !data.containsKey(field)) {
                continue;
            }

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

    /**
     * 枚举值域校验
     * 规则格式：domain:值1|值2|值3
     */
    private static void validateDomain(String field, String strVal, String rule, List<String> errors) {
        String domainStr = rule.substring("domain:".length());
        String[] allowedValues = domainStr.split("\\|");
        Set<String> allowed = new HashSet<>(Arrays.asList(allowedValues));
        if (!allowed.contains(strVal)) {
            errors.add(field + " 值不在允许范围内: " + strVal + ", 允许值: " + domainStr);
        }
    }
}
