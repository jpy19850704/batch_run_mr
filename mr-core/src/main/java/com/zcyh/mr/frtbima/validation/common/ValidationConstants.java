package com.zcyh.mr.frtbima.validation.common;

import java.math.BigDecimal;

/**
 * IMA 验证模块常量定义。
 * 规则依据：MAR32.4-32.9（回测），MAR32.20-32.42（PLA），MAR33.45（Amber 附加系数）
 */
public final class ValidationConstants {

    private ValidationConstants() {
    }

    // ===================== 回测相关常量 =====================

    /** 回测观测窗口天数（MAR32.4：250个交易日） */
    public static final int BACKTEST_WINDOW_DAYS = 250;

    /** 绿区最大例外数（MAR32.4：0-4次，绿区） */
    public static final int GREEN_ZONE_MAX_EXCEPTIONS = 4;

    /** 红区最小例外数（MAR32.4：10次及以上，红区） */
    public static final int RED_ZONE_MIN_EXCEPTIONS = 10;

    /** 回测置信水平 99%（MAR32.4） */
    public static final BigDecimal BACKTEST_CONFIDENCE = new BigDecimal("0.99");

    // ===================== PLA 相关常量 =====================

    /** PLA 测试窗口天数（MAR32.20：至少250天） */
    public static final int PLA_WINDOW_DAYS = 250;

    /**
     * Spearman 相关系数绿区阈值（MAR32.38：> 0.80 为绿区，严格大于）
     * 低于此值且 >= 黄区阈值为黄区，低于黄区阈值为红区
     */
    public static final BigDecimal PLA_SPEARMAN_GREEN_THRESHOLD = new BigDecimal("0.80");

    /** Spearman 相关系数黄区下限（MAR32.38：0.70-0.79 为黄区） */
    public static final BigDecimal PLA_SPEARMAN_AMBER_THRESHOLD = new BigDecimal("0.70");

    /**
     * KS 检验绿区临界值（MAR32.40：< 0.09 为绿区，严格小于）
     * 实际临界值取决于样本量，此处为基准参考值
     */
    public static final BigDecimal PLA_KS_GREEN_THRESHOLD = new BigDecimal("0.09");

    /** KS 检验红区临界值 */
    public static final BigDecimal PLA_KS_RED_THRESHOLD = new BigDecimal("0.12");

    // ===================== 附加资本系数 =====================

    /** Amber 附加系数分子系数（MAR33.45：k = 0.5 × Σ_amber(SA) / Σ_{G,A}(SA)） */
    public static final BigDecimal AMBER_SURCHARGE_COEFFICIENT = new BigDecimal("0.5");
}
