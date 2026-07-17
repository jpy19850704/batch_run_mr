package com.zcyh.mr.springboot.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 计量执行适配器注册中心。
 */
public class ExecutionRegistry {
    private final Map<String, ExecutionAdapter> adapters;

    public ExecutionRegistry(List<ExecutionAdapter> executionAdapters) {
        LinkedHashMap<String, ExecutionAdapter> map = new LinkedHashMap<>();
        for (ExecutionAdapter adapter : executionAdapters) {
            if (adapter == null) {
                continue;
            }
            String code = normalizeCode(adapter.code());
            if (code == null) {
                throw new IllegalStateException("执行类型编码不能为空，adapter=" + adapter.getClass().getName());
            }
            ExecutionAdapter existed = map.get(code);
            if (existed != null) {
                throw new IllegalStateException(
                        "执行类型编码重复: code=" + code
                                + ", adapter1=" + existed.getClass().getName()
                                + ", adapter2=" + adapter.getClass().getName());
            }
            map.put(code, adapter);
        }
        this.adapters = Collections.unmodifiableMap(map);
    }

    public ExecutionAdapter get(String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return null;
        }
        return adapters.get(normalized);
    }

    public List<ExecutionAdapter> list() {
        return new ArrayList<>(adapters.values());
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String value = code.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
