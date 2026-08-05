package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
import com.zcyh.mr.springboot.input.common.InputJsonSupport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TradeTemplateService {
    public ExcelTemplateFile generate(String productCode) {
        String normalized = normalizeProductCode(productCode);
        if (!ProductCalculatorRegistry.supports(normalized)) {
            throw new IllegalArgumentException("不支持的产品类型: " + normalized);
        }
        LinkedHashMap<String, FieldDescriptor> fields = collectFieldDescriptors(normalized);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeDataSheet(workbook, fields, normalized);
            writeHelpSheet(workbook, fields);
            workbook.write(output);
            return new ExcelTemplateFile("trade_" + normalized + "_template.xlsx", output.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成交易导入模板失败", e);
        }
    }

    public JSONObject definition(String productCode) {
        String normalized = normalizeProductCode(productCode);
        LinkedHashMap<String, FieldDescriptor> fields = collectFieldDescriptors(normalized);
        LinkedHashMap<String, FieldDescriptor> jsonContainers = collectJsonContainerDescriptors(normalized);
        JSONArray fieldArray = new JSONArray();
        for (FieldDescriptor field : fields.values()) {
            fieldArray.add(field.toJson());
        }
        JSONArray containerArray = new JSONArray();
        for (FieldDescriptor container : jsonContainers.values()) {
            containerArray.add(container.toJson());
        }
        JSONObject result = new JSONObject();
        result.put("productCode", normalized);
        result.put("fields", fieldArray);
        result.put("jsonContainers", containerArray);
        return result;
    }

    public List<String> invalidFieldPaths(String productCode, JSONObject tradeData) {
        String normalized = normalizeProductCode(productCode);
        LinkedHashMap<String, FieldDescriptor> fields = collectFieldDescriptors(normalized);
        return InputJsonSupport.invalidFieldPaths(tradeData, fields.keySet());
    }

    public List<String> validateFieldValues(String productCode, JSONObject tradeData) {
        String normalized = normalizeProductCode(productCode);
        LinkedHashMap<String, FieldDescriptor> fields = collectFieldDescriptors(normalized);
        List<String> errors = new ArrayList<>();
        if (tradeData == null) {
            errors.add("tradeData不能为空");
            return errors;
        }
        for (FieldDescriptor field : fields.values()) {
            if (TradeAttributeRegistry.findByField(field.path) != null) {
                continue;
            }
            List<PathValue> values = readPathValues(tradeData, field.path);
            if (values.isEmpty()) {
                if (field.required && !field.path.contains("[0]")) {
                    errors.add(field.path + "不能为空");
                }
                continue;
            }
            for (PathValue value : values) {
                validateValue(value.path, value.value, field, errors);
            }
        }
        return errors;
    }

    private static LinkedHashMap<String, FieldDescriptor> collectFieldDescriptors(String productCode) {
        if (!ProductCalculatorRegistry.supports(productCode)) {
            throw new IllegalArgumentException("不支持的产品类型: " + productCode);
        }
        LinkedHashMap<String, FieldDescriptor> fields = new LinkedHashMap<>();
        addSystemFields(fields, productCode);
        collectFields(ProductCalculatorRegistry.tradeInputType(productCode), "", fields, new ArrayList<>(), productCode);
        return fields;
    }

    private static LinkedHashMap<String, FieldDescriptor> collectJsonContainerDescriptors(String productCode) {
        if (!ProductCalculatorRegistry.supports(productCode)) {
            throw new IllegalArgumentException("不支持的产品类型: " + productCode);
        }
        LinkedHashMap<String, FieldDescriptor> containers = new LinkedHashMap<>();
        collectJsonContainers(ProductCalculatorRegistry.tradeInputType(productCode), "", containers,
                new ArrayList<>(), productCode);
        return containers;
    }

    private static void collectJsonContainers(Class<?> type, String prefix,
            Map<String, FieldDescriptor> result, List<Class<?>> path, String productCode) {
        if (type == null || type == Object.class || path.contains(type)) {
            return;
        }
        path.add(type);
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            hierarchy.add(0, current);
            current = current.getSuperclass();
        }
        for (Class<?> owner : hierarchy) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String name = fieldName(field);
                String pathName = prefix.isEmpty() ? name : prefix + "." + name;
                Class<?> fieldType = field.getType();
                if (Collection.class.isAssignableFrom(fieldType) || fieldType.isArray()) {
                    result.putIfAbsent(pathName, descriptor(pathName, field, fieldType, productCode));
                    Class<?> elementType = elementType(field);
                    if (elementType != null && !isDynamic(elementType) && !isScalar(elementType)) {
                        collectJsonContainers(elementType, pathName + "[0]", result,
                                new ArrayList<>(path), productCode);
                    }
                } else if (!isScalar(fieldType) && !isDynamic(fieldType)) {
                    collectJsonContainers(fieldType, pathName, result, new ArrayList<>(path), productCode);
                }
            }
        }
    }

    private static void addSystemFields(Map<String, FieldDescriptor> fields, String productCode) {
        fields.put("INSTRUMENT_ID", new FieldDescriptor("INSTRUMENT_ID", "String", true, "", "交易编号"));
        fields.put("PRODUCT_CODE", new FieldDescriptor("PRODUCT_CODE", "String", true, productCode, "产品类型"));
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions()) {
            fields.putIfAbsent(definition.getFieldName(), new FieldDescriptor(
                    definition.getFieldName(), definition.getValueType().getSimpleName(), false, "", "交易辅助维度"));
        }
    }

    private static void collectFields(Class<?> type, String prefix, Map<String, FieldDescriptor> result,
            List<Class<?>> path, String productCode) {
        if (type == null || type == Object.class || path.contains(type)) {
            return;
        }
        path.add(type);
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            hierarchy.add(0, current);
            current = current.getSuperclass();
        }
        for (Class<?> owner : hierarchy) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String name = fieldName(field);
                String pathName = prefix.isEmpty() ? name : prefix + "." + name;
                Class<?> fieldType = field.getType();
                if (isScalar(fieldType)) {
                    result.putIfAbsent(pathName, descriptor(pathName, field, fieldType, productCode));
                } else if (Collection.class.isAssignableFrom(fieldType) || fieldType.isArray()) {
                    Class<?> elementType = elementType(field);
                    if (elementType != null && !isDynamic(elementType) && !isScalar(elementType)) {
                        collectFields(elementType, pathName + "[0]", result, new ArrayList<>(path), productCode);
                    } else {
                        result.putIfAbsent(pathName, descriptor(pathName, field, fieldType, productCode));
                    }
                } else if (isDynamic(fieldType) || Object.class == fieldType) {
                    result.putIfAbsent(pathName, descriptor(pathName, field, fieldType, productCode));
                } else if (!isDynamic(fieldType)) {
                    collectFields(fieldType, pathName, result, new ArrayList<>(path), productCode);
                }
            }
        }
    }

    private static FieldDescriptor descriptor(String path, Field field, Class<?> type, String productCode) {
        ProductInputField rule = field.getAnnotation(ProductInputField.class);
        boolean required = rule != null && (rule.required() || requiredFor(rule.requiredFor(), productCode));
        String allowed = rule == null ? "" : String.join("|", rule.allowedValues());
        StringBuilder ruleText = new StringBuilder();
        if (rule != null && !rule.min().isEmpty()) {
            ruleText.append(rule.minInclusive() ? "最小值=" : "大于").append(rule.min());
        }
        if (rule != null && !rule.max().isEmpty()) {
            if (ruleText.length() > 0) ruleText.append("; ");
            ruleText.append(rule.maxInclusive() ? "最大值=" : "小于").append(rule.max());
        }
        String typeName = isDynamic(type) || Object.class == type || Collection.class.isAssignableFrom(type)
                || type.isArray() ? "JSON" : type == boolean.class ? "Boolean" : type.getSimpleName();
        return new FieldDescriptor(path, typeName, required, allowed, ruleText.toString());
    }

    private static void writeDataSheet(XSSFWorkbook workbook, LinkedHashMap<String, FieldDescriptor> fields,
            String productCode) {
        Sheet sheet = workbook.createSheet("DATA");
        Row header = sheet.createRow(0);
        int column = 0;
        for (String name : fields.keySet()) {
            header.createCell(column++).setCellValue(name);
        }
        Row example = sheet.createRow(1);
        example.createCell(new ArrayList<>(fields.keySet()).indexOf("PRODUCT_CODE")).setCellValue(productCode);
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < fields.size(); i++) {
            sheet.setColumnWidth(i, Math.min(40, Math.max(14, new ArrayList<>(fields.keySet()).get(i).length() + 2)) * 256);
        }
    }

    private static void writeHelpSheet(XSSFWorkbook workbook, LinkedHashMap<String, FieldDescriptor> fields) {
        Sheet sheet = workbook.createSheet("FIELD_HELP");
        String[] headers = {"字段路径", "类型", "是否必填", "值域", "规则"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
        int rowIndex = 1;
        for (FieldDescriptor field : fields.values()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(field.path);
            row.createCell(1).setCellValue(field.type);
            row.createCell(2).setCellValue(field.required ? "是" : "否");
            row.createCell(3).setCellValue(field.allowedValues);
            row.createCell(4).setCellValue(field.rule);
        }
        sheet.createFreezePane(0, 1);
        for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, (i == 0 ? 40 : 24) * 256);
    }

    private static boolean requiredFor(String[] values, String productCode) {
        for (String value : values) if (value.equalsIgnoreCase(productCode)) return true;
        return false;
    }

    private static String fieldName(Field field) {
        JSONField jsonField = field.getAnnotation(JSONField.class);
        if (jsonField != null && jsonField.name() != null && !jsonField.name().trim().isEmpty()) {
            return jsonField.name().trim();
        }
        return field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type) || Boolean.class == type || Character.class == type
                || LocalDate.class == type || LocalDateTime.class == type;
    }

    private static boolean isDynamic(Class<?> type) {
        return Map.class.isAssignableFrom(type) || JSONObject.class.isAssignableFrom(type);
    }

    private static List<PathValue> readPathValues(Object root, String path) {
        List<PathValue> current = new ArrayList<>();
        current.add(new PathValue("", root));
        for (String segment : path.split("\\.")) {
            java.util.regex.Matcher matcher = Pattern.compile("^([^\\[]+)(?:\\[0\\])?$").matcher(segment);
            if (!matcher.matches()) {
                return java.util.Collections.emptyList();
            }
            String name = matcher.group(1);
            boolean array = segment.endsWith("[0]");
            List<PathValue> next = new ArrayList<>();
            for (PathValue state : current) {
                if (!(state.value instanceof Map)) {
                    if (!array && !state.path.isEmpty()) {
                        next.add(new PathValue(state.path + "." + name, null));
                    }
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) state.value;
                if (!map.containsKey(name)) {
                    if (!array) {
                        String missingPath = state.path.isEmpty() ? name : state.path + "." + name;
                        next.add(new PathValue(missingPath, null));
                    }
                    continue;
                }
                Object value = map.get(name);
                String valuePath = state.path.isEmpty() ? name : state.path + "." + name;
                if (array) {
                    if (!(value instanceof List)) {
                        next.add(new PathValue(valuePath, value));
                        continue;
                    }
                    List<?> list = (List<?>) value;
                    for (int i = 0; i < list.size(); i++) {
                        next.add(new PathValue(valuePath + "[" + i + "]", list.get(i)));
                    }
                } else {
                    next.add(new PathValue(valuePath, value));
                }
            }
            current = next;
        }
        return current;
    }

    private static void validateValue(String path, Object value, FieldDescriptor field, List<String> errors) {
        if (value == null || value instanceof String && ((String) value).trim().isEmpty()) {
            if (field.required) {
                errors.add(path + "不能为空");
            }
            return;
        }
        boolean valid;
        switch (field.type) {
            case "Integer":
            case "Long":
                valid = value instanceof Byte || value instanceof Short || value instanceof Integer
                        || value instanceof Long;
                break;
            case "Double":
            case "Float":
            case "BigDecimal":
                valid = value instanceof Number;
                break;
            case "Boolean":
                valid = value instanceof Boolean;
                break;
            case "LocalDate":
                valid = value instanceof String && value.toString().matches("\\d{4}-\\d{2}-\\d{2}");
                break;
            case "LocalDateTime":
                valid = value instanceof String;
                break;
            case "JSON":
                valid = value instanceof Map || value instanceof List;
                break;
            default:
                valid = value instanceof String;
                break;
        }
        if (!valid) {
            errors.add(path + "必须为" + field.type + "类型");
            return;
        }
    }

    private static Class<?> elementType(Field field) {
        if (field.getType().isArray()) return field.getType().getComponentType();
        Type type = field.getGenericType();
        if (!(type instanceof ParameterizedType)) return null;
        Type element = ((ParameterizedType) type).getActualTypeArguments()[0];
        if (element instanceof Class<?>) return (Class<?>) element;
        if (element instanceof ParameterizedType && ((ParameterizedType) element).getRawType() instanceof Class<?>) {
            return (Class<?>) ((ParameterizedType) element).getRawType();
        }
        return null;
    }

    private static String normalizeProductCode(String productCode) {
        String value = productCode == null ? "" : productCode.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) throw new IllegalArgumentException("productCode不能为空");
        return value;
    }

    private static final class FieldDescriptor {
        final String path;
        final String type;
        final boolean required;
        final String allowedValues;
        final String rule;

        FieldDescriptor(String path, String type, boolean required, String allowedValues, String rule) {
            this.path = path;
            this.type = type;
            this.required = required;
            this.allowedValues = allowedValues;
            this.rule = rule;
        }

        JSONObject toJson() {
            JSONObject result = new JSONObject();
            result.put("path", path);
            result.put("type", type);
            result.put("required", required);
            result.put("allowedValues", allowedValues);
            result.put("rule", rule);
            return result;
        }
    }

    private static final class PathValue {
        final String path;
        final Object value;

        PathValue(String path, Object value) {
            this.path = path;
            this.value = value;
        }
    }
}
