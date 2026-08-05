package com.zcyh.mr.springboot.input.common;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InputJsonSupport {
    private static final Pattern ARRAY_INDEX_PATTERN = Pattern.compile("\\[\\d+\\]");
    private static final Pattern PATH_SEGMENT_PATTERN = Pattern.compile("^([^\\[]+)(?:\\[(?:0)?\\])?$");

    private InputJsonSupport() {
    }

    public static List<String> invalidFieldPaths(Object content, Collection<String> fieldPaths) {
        Set<String> invalidPaths = new LinkedHashSet<>();
        collectInvalidPaths(content, "", new LinkedHashSet<>(fieldPaths), invalidPaths);
        return new ArrayList<>(invalidPaths);
    }

    public static List<PathValue> readPathValues(Object root, String schemaPath) {
        if (root == null || schemaPath == null || schemaPath.isEmpty()) {
            return Collections.emptyList();
        }
        List<PathValue> current = new ArrayList<>();
        current.add(new PathValue("", root));
        for (String segment : schemaPath.split("\\.")) {
            Matcher matcher = PATH_SEGMENT_PATTERN.matcher(segment);
            if (!matcher.matches()) {
                return Collections.emptyList();
            }
            String name = matcher.group(1);
            boolean array = segment.endsWith("[]") || segment.endsWith("[0]");
            List<PathValue> next = new ArrayList<>();
            for (PathValue state : current) {
                if (!(state.getValue() instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) state.getValue();
                if (!map.containsKey(name)) {
                    continue;
                }
                Object value = map.get(name);
                String valuePath = state.getPath().isEmpty() ? name : state.getPath() + "." + name;
                if (!array) {
                    next.add(new PathValue(valuePath, value));
                    continue;
                }
                if (!(value instanceof List)) {
                    next.add(new PathValue(valuePath, value));
                    continue;
                }
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    next.add(new PathValue(valuePath + "[" + i + "]", list.get(i)));
                }
            }
            current = next;
        }
        return current;
    }

    public static String canonicalSchemaPath(String path) {
        return path == null ? null : ARRAY_INDEX_PATTERN.matcher(path).replaceAll("[]");
    }

    public static String normalizedSchemaPath(String path) {
        return path == null ? null : ARRAY_INDEX_PATTERN.matcher(path).replaceAll("[0]").replace("[]", "[0]");
    }

    public static boolean deepEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        if (left instanceof Map && right instanceof Map) {
            Map<?, ?> leftMap = (Map<?, ?>) left;
            Map<?, ?> rightMap = (Map<?, ?>) right;
            if (!leftMap.keySet().equals(rightMap.keySet())) {
                return false;
            }
            for (Object key : leftMap.keySet()) {
                if (!deepEquals(leftMap.get(key), rightMap.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<?> leftList = (List<?>) left;
            List<?> rightList = (List<?>) right;
            if (leftList.size() != rightList.size()) {
                return false;
            }
            for (int i = 0; i < leftList.size(); i++) {
                if (!deepEquals(leftList.get(i), rightList.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static void collectInvalidPaths(Object value, String path,
            Set<String> fieldPaths, Set<String> invalidPaths) {
        if (value == null) {
            if (!path.isEmpty() && !isKnownPath(path, fieldPaths)) {
                invalidPaths.add(path);
            }
            return;
        }
        if (value instanceof JSONObject || value instanceof Map) {
            Map<?, ?> object = (Map<?, ?>) value;
            if (!path.isEmpty() && isKnownPath(path, fieldPaths) && !hasKnownDescendant(path, fieldPaths)) {
                return;
            }
            if (object.isEmpty()) {
                if (!path.isEmpty() && !isKnownPath(path, fieldPaths) && !hasKnownDescendant(path, fieldPaths)) {
                    invalidPaths.add(path);
                }
                return;
            }
            if (!path.isEmpty() && !isKnownPath(path, fieldPaths) && !hasKnownDescendant(path, fieldPaths)) {
                invalidPaths.add(path);
                return;
            }
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String name = Objects.toString(entry.getKey(), "");
                String childPath = path.isEmpty() ? name : path + "." + name;
                collectInvalidPaths(entry.getValue(), childPath, fieldPaths, invalidPaths);
            }
            return;
        }
        if (value instanceof JSONArray || value instanceof List) {
            List<?> array = (List<?>) value;
            if (!path.isEmpty() && isKnownPath(path, fieldPaths) && !hasKnownDescendant(path, fieldPaths)) {
                return;
            }
            if (array.isEmpty()) {
                if (!path.isEmpty() && !isKnownPath(path, fieldPaths) && !hasKnownDescendant(path, fieldPaths)) {
                    invalidPaths.add(path);
                }
                return;
            }
            for (int i = 0; i < array.size(); i++) {
                collectInvalidPaths(array.get(i), path + "[" + i + "]", fieldPaths, invalidPaths);
            }
            return;
        }
        if (!path.isEmpty() && !isKnownPath(path, fieldPaths)) {
            invalidPaths.add(path);
        }
    }

    private static boolean isKnownPath(String path, Set<String> fieldPaths) {
        String normalized = normalizedSchemaPath(path);
        return fieldPaths.stream().map(InputJsonSupport::normalizedSchemaPath).anyMatch(normalized::equals);
    }

    private static boolean hasKnownDescendant(String path, Set<String> fieldPaths) {
        String prefix = normalizedSchemaPath(path) + ".";
        return fieldPaths.stream().map(InputJsonSupport::normalizedSchemaPath).anyMatch(item -> item.startsWith(prefix));
    }

    public static final class PathValue {
        private final String path;
        private final Object value;

        public PathValue(String path, Object value) {
            this.path = path;
            this.value = value;
        }

        public String getPath() {
            return path;
        }

        public Object getValue() {
            return value;
        }
    }
}
