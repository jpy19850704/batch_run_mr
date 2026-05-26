package com.zcyh.mr.frtbsa.sba.common;

/**
 * 商品风险因子 ID 解析工具。
 */
public final class CmtyRiskFactorId {

    private CmtyRiskFactorId() {
    }

    public static Parsed parse(Object riskFactorId) {
        if (riskFactorId == null) {
            return null;
        }
        String text = String.valueOf(riskFactorId).trim();
        String[] parts = text.split("&", -1);
        if (parts.length != 2) {
            return null;
        }
        String type = trimToNull(parts[0]);
        String location = trimToNull(parts[1]);
        if (type == null || location == null) {
            return null;
        }
        return new Parsed(type, location);
    }

    public static Parsed parseRequired(Object riskFactorId) {
        Parsed parsed = parse(riskFactorId);
        if (parsed == null) {
            throw new IllegalArgumentException("CMTY RISK_FACTOR_ID 必须为 type&location 格式: " + riskFactorId);
        }
        return parsed;
    }

    public static String parseVegaTypeRequired(Object riskFactorId) {
        if (riskFactorId == null) {
            throw new IllegalArgumentException("CMTY Vega RISK_FACTOR_ID 不能为空");
        }
        String text = String.valueOf(riskFactorId).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("CMTY Vega RISK_FACTOR_ID 不能为空");
        }
        Parsed parsed = parse(text);
        return parsed == null ? text : parsed.getType();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Parsed {
        private final String type;
        private final String location;

        private Parsed(String type, String location) {
            this.type = type;
            this.location = location;
        }

        public String getType() {
            return type;
        }

        public String getLocation() {
            return location;
        }
    }
}
