package com.zcyh.mr.product.basic.mc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MC 参数校验错误收集器。
 */
public final class ValidationCollector {
    private final List<String> errors = new ArrayList<>();

    public void add(String message) {
        if (message != null && !message.trim().isEmpty()) {
            errors.add(message);
        }
    }

    public void requireText(String fieldName, String value) {
        if (value == null || value.trim().isEmpty()) {
            add(fieldName + " 未设置");
        }
    }

    public void requirePositive(String fieldName, Double value) {
        if (value == null || !Double.isFinite(value) || value <= 0.0) {
            add(fieldName + " 必须为正数");
        }
    }

    public void requireNonNegative(String fieldName, Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            add(fieldName + " 必须为非负数");
        }
    }

    public void requireFinite(String fieldName, Double value) {
        if (value == null || !Double.isFinite(value)) {
            add(fieldName + " 必须为有限数");
        }
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> errors() {
        return Collections.unmodifiableList(errors);
    }
}
