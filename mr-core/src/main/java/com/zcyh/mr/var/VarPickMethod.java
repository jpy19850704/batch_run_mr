package com.zcyh.mr.var;

import java.util.Locale;

/**
 * VaR 分位点选取方式。
 */
public enum VarPickMethod {
    IN("in"),
    OUT("out"),
    AVERAGE("average");

    private final String code;

    VarPickMethod(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static VarPickMethod parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return AVERAGE;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        if (IN.code.equals(token)) {
            return IN;
        }
        if (OUT.code.equals(token)) {
            return OUT;
        }
        if (AVERAGE.code.equals(token)) {
            return AVERAGE;
        }
        throw new IllegalArgumentException("var_pick 仅支持 in/out/average，实际: " + raw);
    }
}

