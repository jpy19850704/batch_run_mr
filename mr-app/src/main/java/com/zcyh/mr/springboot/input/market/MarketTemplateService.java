package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.input.common.ExcelTemplateFile;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class MarketTemplateService {
    public ExcelTemplateFile generate(String marketDataType) {
        String type = normalize(marketDataType);
        String identifierField = "FIXING".equals(type) ? "FIXING_ID" : "CURVE_ID";
        List<String> columns = MarketImportSchema.templateColumns(type);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet data = workbook.createSheet("DATA");
            Row header = data.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i).setCellValue(columns.get(i));
                data.setColumnWidth(i, Math.max(14, columns.get(i).length() + 2) * 256);
            }
            data.createFreezePane(0, 1);
            Sheet help = workbook.createSheet("FIELD_HELP");
            Row helpHeader = help.createRow(0);
            helpHeader.createCell(0).setCellValue("字段");
            helpHeader.createCell(1).setCellValue("说明");
            for (int i = 0; i < columns.size(); i++) {
                Row row = help.createRow(i + 1);
                row.createCell(0).setCellValue(columns.get(i));
                row.createCell(1).setCellValue(identifierField + "为曲线标识；其余字段按所选" + type + "类型填写");
            }
            help.setColumnWidth(0, 28 * 256);
            help.setColumnWidth(1, 60 * 256);
            workbook.write(output);
            return new ExcelTemplateFile("market_" + type + "_template.xlsx", output.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("生成市场数据导入模板失败", e);
        }
    }

    public JSONObject definition(String marketDataType, String conversionType) {
        String normalized = normalizeDefinitionType(marketDataType);
        String normalizedConversionType = normalizeConversionType(normalized, conversionType);
        JSONArray fieldArray = new JSONArray();
        for (FieldDescriptor field : collectFieldDescriptors(normalized, normalizedConversionType).values()) {
            fieldArray.add(field.toJson());
        }
        JSONObject result = new JSONObject();
        result.put("marketDataType", normalized);
        if (normalizedConversionType != null) {
            result.put("conversionType", normalizedConversionType);
        }
        result.put("fields", fieldArray);
        return result;
    }

    public List<String> invalidFieldPaths(String marketDataType, JSONObject marketData) {
        String normalized = normalizeDefinitionType(marketDataType);
        String conversionType = marketData == null ? null : marketData.getString("CONVERSION_TYPE");
        String normalizedConversionType = normalizeConversionType(normalized, conversionType);
        Map<String, FieldDescriptor> fields = collectFieldDescriptors(normalized, normalizedConversionType);
        Set<String> invalidPaths = new LinkedHashSet<>();
        collectInvalidPaths(marketData, "", fields, invalidPaths);
        return new ArrayList<>(invalidPaths);
    }

    public List<String> validateFieldValues(String marketDataType, JSONObject marketData) {
        String normalized = normalizeDefinitionType(marketDataType);
        String conversionType = marketData == null ? null : marketData.getString("CONVERSION_TYPE");
        String normalizedConversionType = normalizeConversionType(normalized, conversionType);
        Map<String, FieldDescriptor> fields = collectFieldDescriptors(normalized, normalizedConversionType);
        List<String> errors = new ArrayList<>();
        if (marketData == null) {
            errors.add("marketData不能为空");
            return errors;
        }
        for (FieldDescriptor field : fields.values()) {
            validateFieldValue(marketData, field, errors);
        }
        if (normalizedConversionType != null) {
            validateRawSemantics(normalizedConversionType, marketData, errors);
        }
        return errors;
    }

    private static LinkedHashMap<String, FieldDescriptor> collectFieldDescriptors(String marketDataType,
            String conversionType) {
        if ("CURVE_GENERATION".equals(marketDataType)) {
            return collectRawFieldDescriptors(conversionType);
        }
        List<String> columns = MarketImportSchema.columns(marketDataType);
        LinkedHashMap<String, FieldDescriptor> fields = new LinkedHashMap<>();
        String identifierField = "FIXING".equals(marketDataType) ? "FIXING_ID" : "CURVE_ID";
        addField(fields, identifierField, "String", true, "不能为空");
        addCommonStoredFields(fields);
        boolean pointField = false;
        for (String column : columns) {
            if ("CURVE_DATA_START".equals(column)) {
                pointField = true;
                continue;
            }
            String path = pointField ? "CURVE_DATA[0]." + column : column;
            fields.put(path, new FieldDescriptor(path, resolveType(column), identifierField.equals(column),
                    identifierField.equals(column) ? "不能为空" : ""));
        }
        return fields;
    }

    private static LinkedHashMap<String, FieldDescriptor> collectRawFieldDescriptors(String conversionType) {
        LinkedHashMap<String, FieldDescriptor> fields = new LinkedHashMap<>();
        addField(fields, "CURVE_ID", "String", true, "不能为空");
        addField(fields, "DATA_DATE", "String", true, "不能为空");
        addField(fields, "CONVERSION_TYPE", "String", true, "不能为空");
        addField(fields, "INTERPOLATE_TYPE", "String", false, "");
        switch (conversionType) {
            case "ZeroCurveBootstrap":
                addRawFields(fields,
                        new String[] { "CURVE_DAYCOUNT", "CURVE_FREQ", "CALENDAR", "OUTPUT_TERM_DAYS" },
                        new String[] { "TERM_CODE", "TERM_TYPE", "TERM_VALUE", "TERM_DAYCOUNT", "TERM_FRQ",
                                "DAY_OFF", "CALENDAR", "START_TERM" },
                        Set.of("TERM_CODE", "TERM_TYPE", "TERM_VALUE"));
                break;
            case "FxImpliedCurveConstruct":
                addRawFields(fields,
                        new String[] { "BASE_DISCOUNT_CURVE", "BASE_CURRENCY_CODE", "BASE_TERM_CODE",
                                "DAY_OFF", "CALENDAR", "CURVE_DAYCOUNT", "CURVE_FREQ", "OUTPUT_TERM_DAYS" },
                        new String[] { "TERM_CODE", "FWD_RATE" }, Set.of("BASE_DISCOUNT_CURVE", "BASE_TERM_CODE",
                                "TERM_CODE", "FWD_RATE"));
                break;
            case "ZeroCurveSubtract":
                addRawFields(fields,
                        new String[] { "YC_CURVE_CODE", "RF_CURVE_CODE", "CURVE_DAYCOUNT", "CURVE_FREQ" },
                        new String[0], Set.of("YC_CURVE_CODE", "RF_CURVE_CODE"));
                break;
            case "VolRrbf2Delta":
                addRawFields(fields,
                        new String[] { "BASE_DISCOUNT_CURVE", "BASE_CURRENCY_CODE", "UNDERLYING_CURRENCY_CODE",
                                "UNDERLYING_DISCOUNT_CURVE", "FX_SPOT", "CALENDAR" },
                        new String[] { "TERM_CODE", "ATM_VOL", "RR_VOL", "BF_VOL" },
                        Set.of("BASE_DISCOUNT_CURVE", "UNDERLYING_DISCOUNT_CURVE", "FX_SPOT", "TERM_CODE",
                                "ATM_VOL", "RR_VOL", "BF_VOL"));
                break;
            default:
                throw new IllegalArgumentException("不支持的曲线生成转换类型: " + conversionType);
        }
        return fields;
    }

    private static void addRawFields(Map<String, FieldDescriptor> fields, String[] topFields,
            String[] pointFields, Set<String> requiredFields) {
        for (String field : topFields) {
            boolean required = requiredFields.contains(field);
            addField(fields, field, resolveType(field), required, required ? "不能为空" : "");
        }
        for (String field : pointFields) {
            boolean required = requiredFields.contains(field);
            addField(fields, "CURVE_DATA[0]." + field, resolveType(field), required,
                    required ? "每个期限点均不能为空" : "");
        }
    }

    private static void addCommonStoredFields(Map<String, FieldDescriptor> fields) {
        addField(fields, "DATA_DATE", "String", false, "");
        addField(fields, "CURVE_TYPE", "String", false, "");
    }

    private static void addField(Map<String, FieldDescriptor> fields, String path, String type,
            boolean required, String rule) {
        fields.putIfAbsent(path, new FieldDescriptor(path, type, required, rule));
    }

    private static String resolveType(String field) {
        if ("RATE".equals(field) || "EQ_PRICE".equals(field) || "COMM_PRICE".equals(field)
                || "FIXING_VALUE".equals(field) || "DELTA".equals(field) || "MONEYNESS".equals(field)
                || "STRIKE".equals(field) || "VOLATILITY_RATE".equals(field) || "TERM_VALUE".equals(field)
                || "BF_VOL".equals(field) || "RR_VOL".equals(field) || "ATM_VOL".equals(field)
                || "FWD_RATE".equals(field) || "FX_SPOT".equals(field) || "TERM".equals(field)
                || "OPTION_TERM".equals(field) || "UNDERLYING_TERM".equals(field)) {
            return "BigDecimal";
        }
        if ("DAY_OFF".equals(field)) {
            return "Integer";
        }
        return "String";
    }

    private static void validateFieldValue(JSONObject marketData, FieldDescriptor field, List<String> errors) {
        if (field.path.startsWith("CURVE_DATA[0].")) {
            String fieldName = field.path.substring("CURVE_DATA[0].".length());
            Object curveDataValue = marketData.get("CURVE_DATA");
            if (!(curveDataValue instanceof JSONArray)) {
                if (field.required && !errors.contains("CURVE_DATA必须为非空数组")) {
                    errors.add("CURVE_DATA必须为非空数组");
                }
                return;
            }
            JSONArray points = (JSONArray) curveDataValue;
            if (field.required && points.isEmpty() && !errors.contains("CURVE_DATA必须为非空数组")) {
                errors.add("CURVE_DATA必须为非空数组");
            }
            for (int i = 0; i < points.size(); i++) {
                Object pointValue = points.get(i);
                if (!(pointValue instanceof JSONObject)) {
                    errors.add("CURVE_DATA[" + i + "]必须为JSON对象");
                    continue;
                }
                validateScalar(((JSONObject) pointValue).get(fieldName), "CURVE_DATA[" + i + "]." + fieldName,
                        field, errors);
            }
            return;
        }
        validateScalar(marketData.get(field.path), field.path, field, errors);
    }

    private static void validateScalar(Object value, String path, FieldDescriptor field, List<String> errors) {
        if (value == null || value instanceof String && ((String) value).trim().isEmpty()) {
            if (field.required) {
                errors.add(path + "不能为空");
            }
            return;
        }
        boolean valid;
        switch (field.type) {
            case "Integer":
                valid = value instanceof Byte || value instanceof Short || value instanceof Integer
                        || value instanceof Long;
                break;
            case "BigDecimal":
                valid = value instanceof Number;
                break;
            case "String":
                valid = value instanceof String;
                break;
            default:
                valid = true;
                break;
        }
        if (!valid) {
            errors.add(path + "必须为" + field.type + "类型");
        }
    }

    private static void validateRawSemantics(String conversionType, JSONObject marketData, List<String> errors) {
        String dataDate = marketData.getString("DATA_DATE");
        if (dataDate != null && !dataDate.matches("\\d{8}")) {
            errors.add("DATA_DATE格式必须为yyyyMMdd");
        }
        JSONArray points = marketData.getJSONArray("CURVE_DATA");
        if ("ZeroCurveBootstrap".equals(conversionType) && points != null) {
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                if (point == null) continue;
                String termType = point.getString("TERM_TYPE");
                if (termType != null && !"ZERO".equals(termType) && !"SWAP".equals(termType)) {
                    errors.add("CURVE_DATA[" + i + "].TERM_TYPE只能为ZERO或SWAP");
                }
                if ("SWAP".equals(termType) && isBlank(point.get("TERM_FRQ"))) {
                    errors.add("CURVE_DATA[" + i + "].TERM_FRQ在TERM_TYPE为SWAP时不能为空");
                }
            }
        }
        if ("FxImpliedCurveConstruct".equals(conversionType) && points != null) {
            String baseTermCode = marketData.getString("BASE_TERM_CODE");
            boolean foundBaseTerm = false;
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.getJSONObject(i);
                if (point == null) continue;
                Object fwdRateValue = point.get("FWD_RATE");
                if (fwdRateValue instanceof Number && ((Number) fwdRateValue).doubleValue() <= 0) {
                    errors.add("CURVE_DATA[" + i + "].FWD_RATE必须大于0");
                }
                if (baseTermCode != null && baseTermCode.equalsIgnoreCase(point.getString("TERM_CODE"))) {
                    foundBaseTerm = true;
                }
            }
            if (baseTermCode != null && !baseTermCode.trim().isEmpty() && !foundBaseTerm) {
                errors.add("CURVE_DATA缺少BASE_TERM_CODE对应的期限点: " + baseTermCode);
            }
        }
        if ("VolRrbf2Delta".equals(conversionType)) {
            Object fxSpotValue = marketData.get("FX_SPOT");
            if (fxSpotValue instanceof Number && ((Number) fxSpotValue).doubleValue() <= 0) {
                errors.add("FX_SPOT必须大于0");
            }
            if (points != null) {
                for (int i = 0; i < points.size(); i++) {
                    JSONObject point = points.getJSONObject(i);
                    if (point == null) continue;
                    Object atmVolValue = point.get("ATM_VOL");
                    if (atmVolValue instanceof Number && ((Number) atmVolValue).doubleValue() <= 0) {
                        errors.add("CURVE_DATA[" + i + "].ATM_VOL必须大于0");
                    }
                }
            }
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    private static void collectInvalidPaths(Object value, String path,
            Map<String, FieldDescriptor> fields, Set<String> invalidPaths) {
        if (value == null) {
            if (!path.isEmpty() && !isKnownPath(path, fields)) {
                invalidPaths.add(path);
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.isEmpty()) {
                if (!path.isEmpty() && !isKnownPath(path, fields)) {
                    invalidPaths.add(path);
                }
                return;
            }
            if (!path.isEmpty() && !hasKnownDescendant(path, fields)) {
                invalidPaths.add(path);
                return;
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                collectInvalidPaths(entry.getValue(), childPath, fields, invalidPaths);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.isEmpty()) {
                if (!path.isEmpty() && !hasKnownDescendant(path, fields) && !isKnownPath(path, fields)) {
                    invalidPaths.add(path);
                }
                return;
            }
            for (int i = 0; i < array.size(); i++) {
                collectInvalidPaths(array.get(i), path + "[" + i + "]", fields, invalidPaths);
            }
            return;
        }
        if (!path.isEmpty() && !isKnownPath(path, fields)) {
            invalidPaths.add(path);
        }
    }

    private static boolean isKnownPath(String path, Map<String, FieldDescriptor> fields) {
        return findField(path, fields) != null;
    }

    private static FieldDescriptor findField(String path, Map<String, FieldDescriptor> fields) {
        FieldDescriptor exact = fields.get(path);
        return exact != null ? exact : fields.get(normalizeArrayPath(path));
    }

    private static boolean hasKnownDescendant(String path, Map<String, FieldDescriptor> fields) {
        String normalizedPath = normalizeArrayPath(path);
        String prefix = normalizedPath + ".";
        for (String fieldPath : fields.keySet()) {
            if (normalizeArrayPath(fieldPath).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeArrayPath(String path) {
        return Pattern.compile("\\[\\d+\\]").matcher(path).replaceAll("[0]");
    }

    private static String normalize(String marketDataType) {
        String value = marketDataType == null ? "" : marketDataType.trim().toUpperCase(Locale.ROOT);
        if (!MarketImportSchema.supportedTypes().contains(value)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return value;
    }

    private static String normalizeDefinitionType(String marketDataType) {
        String value = marketDataType == null ? "" : marketDataType.trim().toUpperCase(Locale.ROOT);
        if (!"CURVE_GENERATION".equals(value) && !MarketImportSchema.supportedTypes().contains(value)) {
            throw new IllegalArgumentException("不支持的市场数据类型: " + marketDataType);
        }
        return value;
    }

    private static String normalizeConversionType(String marketDataType, String conversionType) {
        if (!"CURVE_GENERATION".equals(marketDataType)) {
            return null;
        }
        String value = conversionType == null ? "" : conversionType.trim();
        switch (value) {
            case "ZeroCurveBootstrap":
            case "FxImpliedCurveConstruct":
            case "ZeroCurveSubtract":
            case "VolRrbf2Delta":
                return value;
            default:
                throw new IllegalArgumentException("CURVE_GENERATION必须提供受支持的CONVERSION_TYPE: " + conversionType);
        }
    }

    private static final class FieldDescriptor {
        final String path;
        final String type;
        final boolean required;
        final String rule;

        FieldDescriptor(String path, String type, boolean required, String rule) {
            this.path = path;
            this.type = type;
            this.required = required;
            this.rule = rule;
        }

        JSONObject toJson() {
            JSONObject result = new JSONObject();
            result.put("path", path);
            result.put("type", type);
            result.put("required", required);
            result.put("allowedValues", "");
            result.put("rule", rule);
            return result;
        }
    }
}
