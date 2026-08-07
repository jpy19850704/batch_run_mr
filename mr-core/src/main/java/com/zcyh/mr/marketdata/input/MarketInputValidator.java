package com.zcyh.mr.marketdata.input;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MarketInputValidator {
    private MarketInputValidator() {
    }

    public static List<String> validate(JSONObject input) {
        return validate(input, true);
    }

    public static List<String> validateFieldValues(JSONObject input) {
        return validate(input, false);
    }

    public static List<String> validateFieldValues(JSONObject input, MarketDataType expectedType) {
        return validate(input, expectedType, false);
    }

    public static LoadValidationResult validateForLoading(JSONObject input) {
        List<String> outerErrors = new ArrayList<String>();
        if (input == null) {
            outerErrors.add("市场数据不能为空");
            return new LoadValidationResult(input, outerErrors, Collections.<String>emptyList());
        }

        MarketDataType marketDataType;
        try {
            marketDataType = MarketDataType.parse(input.getString("CURVE_TYPE"));
        } catch (IllegalArgumentException ex) {
            outerErrors.add(ex.getMessage());
            return new LoadValidationResult(input, outerErrors, Collections.<String>emptyList());
        }

        MarketInputDefinition definition = MarketInputDefinitionRegistry.get(marketDataType);
        validateObject(input, definition.getFields(), "", false, outerErrors);
        validateCurveType(input, marketDataType, outerErrors);
        if (!outerErrors.isEmpty()) {
            return new LoadValidationResult(input, outerErrors, Collections.<String>emptyList());
        }

        JSONArray sourcePoints = input.getJSONArray("CURVE_DATA");
        JSONArray validPoints = new JSONArray();
        List<String> pointErrors = new ArrayList<String>();
        Set<String> dimensions = new HashSet<String>();
        for (int index = 0; index < sourcePoints.size(); index++) {
            Object rawPoint = sourcePoints.get(index);
            String path = "CURVE_DATA[" + index + "]";
            List<String> currentErrors = new ArrayList<String>();
            if (!(rawPoint instanceof JSONObject)) {
                currentErrors.add(path + "必须为JSON对象");
            } else {
                JSONObject point = (JSONObject) rawPoint;
                validateObject(point, definition.getPointFields(), path, false, currentErrors);
                if (currentErrors.isEmpty()) {
                    if (isVolType(marketDataType)) {
                        validateVolPoint(point, marketDataType, path, dimensions, currentErrors);
                    } else {
                        validatePointDimension(point, marketDataType, path, dimensions, currentErrors);
                    }
                }
                if (currentErrors.isEmpty()) {
                    validPoints.add(point);
                }
            }
            pointErrors.addAll(currentErrors);
        }

        JSONObject validInput = new JSONObject(input);
        validInput.put("CURVE_DATA", validPoints);
        return new LoadValidationResult(validInput, outerErrors, pointErrors);
    }

    private static List<String> validate(JSONObject input, boolean rejectUnknownFields) {
        List<String> errors = new ArrayList<String>();
        if (input == null) {
            errors.add("市场数据不能为空");
            return errors;
        }
        MarketDataType marketDataType;
        try {
            marketDataType = MarketDataType.parse(input.getString("CURVE_TYPE"));
        } catch (IllegalArgumentException ex) {
            errors.add(ex.getMessage());
            return errors;
        }
        return validate(input, marketDataType, rejectUnknownFields);
    }

    private static List<String> validate(
            JSONObject input,
            MarketDataType marketDataType,
            boolean rejectUnknownFields) {
        List<String> errors = new ArrayList<String>();
        if (input == null) {
            errors.add("市场数据不能为空");
            return errors;
        }
        MarketInputDefinition definition = MarketInputDefinitionRegistry.get(marketDataType);
        validateObject(input, definition.getFields(), "", rejectUnknownFields, errors);
        validateCurveType(input, marketDataType, errors);
        validatePoints(input, definition, marketDataType, rejectUnknownFields, errors);
        return errors;
    }

    private static void validateCurveType(
            JSONObject input,
            MarketDataType expectedType,
            List<String> errors) {
        String actualType = input.getString("CURVE_TYPE");
        if (!expectedType.name().equals(actualType)) {
            errors.add("CURVE_TYPE必须为" + expectedType.name());
        }
    }

    private static void validatePoints(
            JSONObject input,
            MarketInputDefinition definition,
            MarketDataType marketDataType,
            boolean rejectUnknownFields,
            List<String> errors) {
        Object rawCurveData = input.get("CURVE_DATA");
        if (!(rawCurveData instanceof JSONArray)) {
            return;
        }
        JSONArray curveData = (JSONArray) rawCurveData;
        Set<String> dimensions = new HashSet<String>();
        for (int index = 0; index < curveData.size(); index++) {
            Object rawPoint = curveData.get(index);
            String path = "CURVE_DATA[" + index + "]";
            if (!(rawPoint instanceof JSONObject)) {
                errors.add(path + "必须为JSON对象");
                continue;
            }
            JSONObject point = (JSONObject) rawPoint;
            validateObject(point, definition.getPointFields(), path, rejectUnknownFields, errors);
            if (isVolType(marketDataType)) {
                validateVolPoint(point, marketDataType, path, dimensions, errors);
            } else {
                validatePointDimension(point, marketDataType, path, dimensions, errors);
            }
        }
    }

    private static void validatePointDimension(
            JSONObject point,
            MarketDataType marketDataType,
            String path,
            Set<String> dimensions,
            List<String> errors) {
        String dimensionField;
        switch (marketDataType) {
            case IR_SPOT:
            case CREDIT_SPOT:
            case EQ_SPOT:
            case COMM_SPOT:
                dimensionField = "TERM";
                break;
            case FX_SPOT:
                dimensionField = "CURRENCY";
                break;
            case FIXING:
                dimensionField = "TRADE_DATE";
                break;
            default:
                return;
        }
        Object dimensionValue = point.get(dimensionField);
        if (dimensionValue != null && !dimensions.add(dimensionField + "=" + dimensionValue)) {
            errors.add(path + "期限点维度重复: " + dimensionField + "=" + dimensionValue);
        }
    }

    private static void validateVolPoint(
            JSONObject point,
            MarketDataType marketDataType,
            String path,
            Set<String> dimensions,
            List<String> errors) {
        String axis2Type = marketDataType == MarketDataType.IR_VOL ? "UNDERLYING_TERM" : "DELTA";
        Object optionTerm = point.get("OPTION_TERM");
        Object axis2 = point.get(axis2Type);
        if (optionTerm != null && axis2 != null) {
            String dimension = optionTerm + "|" + axis2;
            if (!dimensions.add(dimension)) {
                errors.add(path + "曲面维度重复: " + dimension);
            }
        }
    }

    private static void validateObject(
            JSONObject value,
            List<MarketFieldDefinition> fields,
            String path,
            boolean rejectUnknownFields,
            List<String> errors) {
        Set<String> fieldNames = new HashSet<String>();
        for (MarketFieldDefinition field : fields) {
            fieldNames.add(field.getName());
            Object fieldValue = value.get(field.getName());
            String fieldPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
            if (field.isRequired() && isEmpty(fieldValue)) {
                errors.add(fieldPath + "不能为空");
                continue;
            }
            if (fieldValue == null) {
                continue;
            }
            validateType(field, fieldValue, fieldPath, errors);
            validateAllowedValues(field, fieldValue, fieldPath, errors);
        }
        if (rejectUnknownFields) {
            for (String actualField : value.keySet()) {
                if (!fieldNames.contains(actualField)) {
                    String fieldPath = path.isEmpty() ? actualField : path + "." + actualField;
                    errors.add(fieldPath + "不是" + (path.isEmpty() ? "标准字段" : "当前市场数据类型的期限点字段"));
                }
            }
        }
    }

    private static void validateType(
            MarketFieldDefinition field,
            Object value,
            String path,
            List<String> errors) {
        boolean valid;
        switch (field.getType()) {
            case TEXT:
                valid = value instanceof String;
                break;
            case INTEGER:
                valid = isInteger(value);
                break;
            case NUMBER:
                valid = value instanceof Number && Double.isFinite(((Number) value).doubleValue());
                break;
            case DATE:
                valid = isDate(value);
                break;
            case JSON:
                valid = value instanceof JSONArray;
                break;
            default:
                valid = false;
        }
        if (!valid) {
            errors.add(path + "必须为" + typeName(field.getType()) + "类型");
        }
    }

    private static void validateAllowedValues(
            MarketFieldDefinition field,
            Object value,
            String path,
            List<String> errors) {
        if (field.getAllowedValues().isEmpty() || !(value instanceof String)) {
            return;
        }
        String text = ((String) value).trim();
        for (String allowedValue : field.getAllowedValues()) {
            if (allowedValue.equalsIgnoreCase(text)) {
                return;
            }
        }
        errors.add(path + "不在标准值域" + field.getAllowedValues() + "中");
    }

    private static boolean isInteger(Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && Math.rint(number) == number;
    }

    private static boolean isDate(Object value) {
        if (value instanceof LocalDate) {
            return true;
        }
        if (!(value instanceof String)) {
            return false;
        }
        try {
            LocalDate.parse((String) value);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).isEmpty();
        }
        return false;
    }

    private static boolean isVolType(MarketDataType marketDataType) {
        return marketDataType == MarketDataType.IR_VOL
                || marketDataType == MarketDataType.FX_VOL
                || marketDataType == MarketDataType.EQ_VOL
                || marketDataType == MarketDataType.COMM_VOL;
    }

    private static String typeName(MarketFieldType fieldType) {
        switch (fieldType) {
            case TEXT:
                return "文本";
            case INTEGER:
                return "整数";
            case NUMBER:
                return "数值";
            case DATE:
                return "日期";
            case JSON:
                return "JSON数组";
            default:
                return fieldType.name();
        }
    }

    public static final class LoadValidationResult {
        private final JSONObject validInput;
        private final List<String> outerErrors;
        private final List<String> pointErrors;

        private LoadValidationResult(
                JSONObject validInput,
                List<String> outerErrors,
                List<String> pointErrors) {
            this.validInput = validInput;
            this.outerErrors = Collections.unmodifiableList(new ArrayList<String>(outerErrors));
            this.pointErrors = Collections.unmodifiableList(new ArrayList<String>(pointErrors));
        }

        public JSONObject getValidInput() {
            return validInput;
        }

        public List<String> getOuterErrors() {
            return outerErrors;
        }

        public List<String> getPointErrors() {
            return pointErrors;
        }
    }
}
