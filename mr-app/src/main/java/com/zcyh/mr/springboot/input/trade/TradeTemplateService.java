package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.calc.ProductCalculatorRegistry;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
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

@Service
public class TradeTemplateService {
    public ExcelTemplateFile generate(String productCode) {
        String normalized = normalizeProductCode(productCode);
        if (!ProductCalculatorRegistry.supports(normalized)) {
            throw new IllegalArgumentException("不支持的产品类型: " + normalized);
        }
        LinkedHashMap<String, FieldDescriptor> fields = new LinkedHashMap<>();
        addSystemFields(fields, normalized);
        collectFields(ProductCalculatorRegistry.tradeInputType(normalized), "", fields, new ArrayList<>(), normalized);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeDataSheet(workbook, fields, normalized);
            writeHelpSheet(workbook, fields);
            workbook.write(output);
            return new ExcelTemplateFile("trade_" + normalized + "_template.xlsx", output.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成交易导入模板失败", e);
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
                    if (elementType != null && !isDynamic(elementType)) {
                        collectFields(elementType, pathName + "[0]", result, new ArrayList<>(path), productCode);
                    }
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
        return new FieldDescriptor(path, type.getSimpleName(), required, allowed, ruleText.toString());
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
        return Map.class.isAssignableFrom(type) || JSONObject.class.isAssignableFrom(type)
                || type.getName().startsWith("java.");
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
    }
}
