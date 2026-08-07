package com.zcyh.mr.cva;

public final class CvaSupervisoryParams {
    public static final double ALPHA = 1.4;
    public static final double RHO = 0.5;
    public static final double DISCOUNT_RATE = 0.05;
    public static final double DISCOUNT_SCALAR = 0.65;
    public static final double FULL_REDUCED_WEIGHT = 0.25;
    public static final double FULL_HEDGED_WEIGHT = 0.75;
    public static final double INDEX_DIVERSIFICATION_FACTOR = 0.7;
    public static final double SIMPLIFIED_NOTIONAL_THRESHOLD_CNY = 800_000_000_000.0;

    private CvaSupervisoryParams() {
    }

    public static double riskWeight(String industry, String creditQuality) {
        String normalizedIndustry = requireText(industry, "交易对手行业").toUpperCase();
        String normalizedQuality = requireText(creditQuality, "交易对手信用水平").toUpperCase();
        boolean investmentGrade;
        if ("IG".equals(normalizedQuality)) {
            investmentGrade = true;
        } else if ("HY_NR".equals(normalizedQuality)) {
            investmentGrade = false;
        } else {
            throw new IllegalArgumentException("CVA 信用水平仅支持 IG 或 HY_NR: " + creditQuality);
        }
        switch (normalizedIndustry) {
            case "GOVERNMENT":
                return investmentGrade ? 0.005 : 0.020;
            case "LOCAL_PUBLIC":
                return investmentGrade ? 0.010 : 0.040;
            case "FINANCIAL":
                return investmentGrade ? 0.050 : 0.120;
            case "BASIC_RESOURCE":
                return investmentGrade ? 0.030 : 0.070;
            case "CONSUMER_SERVICE":
                return investmentGrade ? 0.030 : 0.085;
            case "TECHNOLOGY":
                return investmentGrade ? 0.020 : 0.055;
            case "HEALTH_PUBLIC":
                return investmentGrade ? 0.015 : 0.050;
            case "OTHER":
                return investmentGrade ? 0.050 : 0.120;
            default:
                throw new IllegalArgumentException("CVA 行业分类不支持: " + industry);
        }
    }

    public static double hedgeCorrelation(String relationType) {
        String value = requireText(relationType, "单名对冲关联类型").toUpperCase();
        switch (value) {
            case "DIRECT":
                return 1.0;
            case "LEGAL_RELATION":
                return 0.8;
            case "SAME_SECTOR_REGION":
                return 0.5;
            default:
                throw new IllegalArgumentException("CVA 单名对冲关联类型不支持: " + relationType);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }
}
