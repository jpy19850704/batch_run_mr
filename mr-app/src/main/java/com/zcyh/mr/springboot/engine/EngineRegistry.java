package com.zcyh.mr.springboot.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 引擎注册中心。
 */
public class EngineRegistry {
    private final Map<String, EngineAdapter> adapters;

    public EngineRegistry(List<EngineAdapter> engineAdapters) {
        LinkedHashMap<String, EngineAdapter> map = new LinkedHashMap<>();
        for (EngineAdapter adapter : engineAdapters) {
            if (adapter == null) {
                continue;
            }
            String code = normalizeCode(adapter.code());
            if (code == null) {
                throw new IllegalStateException("引擎编码不能为空，adapter=" + adapter.getClass().getName());
            }
            EngineAdapter existed = map.get(code);
            if (existed != null) {
                throw new IllegalStateException(
                        "引擎编码重复: code=" + code
                                + ", adapter1=" + existed.getClass().getName()
                                + ", adapter2=" + adapter.getClass().getName());
            }
            map.put(code, adapter);
        }
        this.adapters = Collections.unmodifiableMap(map);
    }

    public EngineAdapter get(String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return null;
        }
        return adapters.get(normalized);
    }

    public List<EngineAdapter> list() {
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

