package com.zcyh.mr.springboot.input.common;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class InputDetailSupport {
    private InputDetailSupport() {
    }

    public static JSONObject build(String kind, JSONObject record, String rawContent,
            JSONObject content, JSONObject definition, List<String> invalidPaths, List<String> validationErrors) {
        JSONObject result = new JSONObject();
        result.put("kind", kind);
        result.put("record", record);
        result.put("rawContent", rawContent);
        result.put("content", content);
        JSONObject schema = buildSchema(definition);
        result.put("schema", schema);
        result.put("issues", buildIssues(content, schema, invalidPaths, validationErrors));
        return result;
    }

    public static JSONObject malformed(String kind, JSONObject record, String rawContent,
            JSONObject definition, String message) {
        JSONObject result = build(kind, record, rawContent, null, definition,
                java.util.Collections.emptyList(), java.util.Collections.emptyList());
        JSONArray issues = result.getJSONArray("issues");
        issues.add(issue("", "JSON_PARSE_ERROR", message, false));
        return result;
    }

    private static JSONObject buildSchema(JSONObject definition) {
        JSONObject schema = new JSONObject();
        JSONArray fields = new JSONArray();
        JSONArray sourceFields = definition == null ? null : definition.getJSONArray("fields");
        if (sourceFields != null) {
            for (int i = 0; i < sourceFields.size(); i++) {
                fields.add(field(sourceFields.getJSONObject(i)));
            }
        }
        JSONArray containers = new JSONArray();
        JSONArray sourceContainers = definition == null ? null : definition.getJSONArray("jsonContainers");
        if (sourceContainers != null) {
            for (int i = 0; i < sourceContainers.size(); i++) {
                containers.add(field(sourceContainers.getJSONObject(i)));
            }
        }
        if (containers.isEmpty() && fields.stream().map(JSONObject.class::cast)
                .anyMatch(item -> item.getString("path").startsWith("CURVE_DATA[]."))) {
            JSONObject container = new JSONObject();
            container.put("path", "CURVE_DATA");
            container.put("valueType", "JSON");
            container.put("required", false);
            container.put("allowedValues", new JSONArray());
            containers.add(container);
        }
        schema.put("fields", fields);
        schema.put("containers", containers);
        return schema;
    }

    private static JSONObject field(JSONObject source) {
        JSONObject field = new JSONObject();
        field.put("path", InputJsonSupport.canonicalSchemaPath(source.getString("path")));
        field.put("valueType", valueType(source.getString("type")));
        field.put("required", source.getBooleanValue("required"));
        JSONArray allowedValues = new JSONArray();
        String allowed = source.getString("allowedValues");
        if (allowed != null && !allowed.trim().isEmpty()) {
            for (String value : allowed.split("\\|")) {
                if (!value.trim().isEmpty()) {
                    allowedValues.add(value.trim());
                }
            }
        }
        field.put("allowedValues", allowedValues);
        return field;
    }

    private static JSONArray buildIssues(JSONObject content, JSONObject schema,
            List<String> invalidPaths, List<String> validationErrors) {
        JSONArray issues = new JSONArray();
        Set<String> issueKeys = new LinkedHashSet<>();
        for (String path : invalidPaths) {
            addIssue(issues, issueKeys, issue(path, "UNKNOWN_FIELD", "字段不在当前数据定义中", true));
        }
        for (String error : validationErrors) {
            String code = error.contains("不能为空") ? "REQUIRED_MISSING"
                    : error.contains("必须为") && error.contains("类型") ? "TYPE_MISMATCH"
                    : error.contains("格式必须") ? "TYPE_MISMATCH" : "BUSINESS_VALIDATION";
            addIssue(issues, issueKeys, issue(errorPath(error), code, error, false));
        }
        if (content != null) {
            JSONArray fields = schema.getJSONArray("fields");
            for (int i = 0; i < fields.size(); i++) {
                JSONObject field = fields.getJSONObject(i);
                JSONArray allowedValues = field.getJSONArray("allowedValues");
                if (allowedValues == null || allowedValues.isEmpty()) {
                    continue;
                }
                for (InputJsonSupport.PathValue value : InputJsonSupport.readPathValues(content,
                        field.getString("path"))) {
                    if (value.getValue() == null
                            || containsIgnoreCase(allowedValues, value.getValue().toString())) {
                        continue;
                    }
                    addIssue(issues, issueKeys, issue(value.getPath(), "OUT_OF_DOMAIN",
                            "当前值不在标准值域中", false));
                }
            }
        }
        return issues;
    }

    private static boolean containsIgnoreCase(JSONArray allowedValues, String value) {
        for (Object allowedValue : allowedValues) {
            if (allowedValue != null && allowedValue.toString().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static void addIssue(JSONArray issues, Set<String> issueKeys, JSONObject issue) {
        String key = issue.getString("path") + "|" + issue.getString("code");
        if (issueKeys.add(key)) {
            issues.add(issue);
        }
    }

    private static JSONObject issue(String path, String code, String message, boolean clearable) {
        JSONObject issue = new JSONObject();
        issue.put("path", path == null ? "" : path);
        issue.put("code", code);
        issue.put("message", message);
        issue.put("clearable", clearable);
        return issue;
    }

    private static String errorPath(String error) {
        if (error == null) {
            return "";
        }
        String[] markers = { "不能为空", "必须为", "格式必须", "只能为", "必须大于", "必须小于", "缺少" };
        int end = error.length();
        for (String marker : markers) {
            int index = error.indexOf(marker);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        String path = error.substring(0, end).trim();
        return path.matches("[A-Za-z0-9_\\[\\].]+") ? path : "";
    }

    private static String valueType(String javaType) {
        if (javaType == null) {
            return "STRING";
        }
        switch (javaType) {
            case "Byte":
            case "Short":
            case "Integer":
            case "Long":
                return "INTEGER";
            case "Double":
            case "Float":
            case "BigDecimal":
            case "Number":
                return "DECIMAL";
            case "Boolean":
                return "BOOLEAN";
            case "LocalDate":
                return "DATE";
            case "LocalDateTime":
                return "DATETIME";
            case "JSON":
                return "JSON";
            default:
                return "STRING";
        }
    }
}
