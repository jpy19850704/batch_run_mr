package com.zcyh.mr.product.basic.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 交易输入校验错误收集器。
 */
public final class TradeValidationCollector {
    private final List<String> errors = new ArrayList<String>();

    public void add(String field, String message) {
        String fieldName = trimToNull(field);
        String detail = trimToNull(message);
        if (detail == null) {
            return;
        }
        errors.add(fieldName == null ? detail : fieldName + ": " + detail);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public void throwIfInvalid() {
        if (hasErrors()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
