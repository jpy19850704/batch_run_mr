package com.zcyh.mr.var;

import java.util.Locale;

/**
 * VaR 风险大类与损益列映射。
 */
public final class VarPnlColumns {
    public static final String ALL_PNL = "ALL_PNL";
    public static final String IR_PNL = "IR_PNL";
    public static final String FX_PNL = "FX_PNL";
    public static final String EQ_PNL = "EQ_PNL";
    public static final String COMM_PNL = "COMM_PNL";

    private VarPnlColumns() {
    }

    public static String riskClassToPnlColumn(String riskClass) {
        String safe = trimToNull(riskClass);
        if (safe == null) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        String upper = safe.toUpperCase(Locale.ROOT);
        if ("IR".equals(upper)) {
            return IR_PNL;
        }
        if ("FX".equals(upper)) {
            return FX_PNL;
        }
        if ("EQ".equals(upper)) {
            return EQ_PNL;
        }
        if ("COMM".equals(upper) || "CMTY".equals(upper)) {
            return COMM_PNL;
        }
        if ("ALL".equals(upper)) {
            return ALL_PNL;
        }
        throw new IllegalArgumentException("不支持的 risk_class: " + riskClass + "，仅支持 IR/FX/EQ/COMM/ALL");
    }

    public static String normalizeRiskClassToken(String token) {
        String safe = trimToNull(token);
        if (safe == null) {
            throw new IllegalArgumentException("risk_class 不能为空");
        }
        String upper = safe.toUpperCase(Locale.ROOT);
        if ("CMTY".equals(upper)) {
            return "COMM";
        }
        riskClassToPnlColumn(upper);
        return upper;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
