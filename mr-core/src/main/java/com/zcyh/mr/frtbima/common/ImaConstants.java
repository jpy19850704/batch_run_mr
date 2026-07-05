package com.zcyh.mr.frtbima.common;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * FRTB IMA 核心常量。
 * 规则依据：MAR33
 */
public final class ImaConstants {

    private ImaConstants() {
    }

    // ===================== ES 计算参数 =====================

    /** ES 置信水平 97.5%（MAR33.4） */
    public static final BigDecimal ES_CONFIDENCE = new BigDecimal("0.975");

    /** IMCC 聚合相关系数 ρ=0.5（MAR33.15） */
    public static final BigDecimal RHO_IMCC = new BigDecimal("0.5");

    /** SES 聚合相关系数 ρ=0.6（MAR33.17） */
    public static final BigDecimal RHO_SES = new BigDecimal("0.6");

    // ===================== 流动性期限子集 =====================

    /**
     * 5 个流动性期限子集天数（MAR33.4）。
     * j=1: 10天，j=2: 20天，j=3: 40天，j=4: 60天，j=5: 120天
     */
    public static final int[] LH_DAYS_ARRAY = {10, 20, 40, 60, 120};

    /**
     * 流动性期限基础值 LH_1=10（MAR33.4，ES 公式权重分母）。
     */
    public static final int LH_BASE = 10;

    /**
     * FX 10天流动性期限币种集合。
     * 来源为 Basel SA 指定外汇货币对及其一阶交叉对应的币种范围，IMA 单独维护。
     */
    public static final Set<String> FX_LH_10_CURRENCIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "AUD", "BRL", "CAD", "CHF", "CNY",
            "EUR", "GBP", "HKD", "INR", "JPY",
            "KRW", "MXN", "NOK", "NZD", "RUB",
            "SEK", "SGD", "TRY", "USD", "ZAR")));

    /**
     * subScenarioId 中 LH 编码的分隔符。
     * 格式：{原始subScenId}{LH_SUFFIX_SEP}{lhDays}，如 "HIST001_LH10"
     */
    public static final String LH_SUFFIX_SEP = "_LH";

    // ===================== 情景类型 =====================

    /**
     * 压力期缩减因子集情景（MAR33.5，用于计算 ES_S_reduced）。
     * 情景 PnL 落库时 scenario_type 字段应使用此值（缩减集批次）。
     */
    public static final String SCENARIO_TYPE_STRESS_REDUCED = "STRESS_REDUCED";

    /**
     * 当前期全量因子集情景（MAR33.4，用于计算 ES_C_full）。
     * 情景 PnL 落库时 scenario_type 字段应使用此值。
     */
    public static final String SCENARIO_TYPE_NORMAL_FULL = "NORMAL_FULL";

    /**
     * 当前期缩减因子集情景（MAR33.5，用于计算 ES_C_reduced）。
     * 情景 PnL 落库时 scenario_type 字段应使用此值（缩减集批次）。
     */
    public static final String SCENARIO_TYPE_NORMAL_REDUCED = "NORMAL_REDUCED";

    // ===================== 风险因子类型（与 MarketData 字段对应） =====================

    /** 利率即期曲线（irSpot 字段） */
    public static final String RF_TYPE_IR_SPOT = "IR_SPOT";

    /** 信用利差即期曲线（irSpot 字段） */
    public static final String RF_TYPE_CREDIT_SPOT = "CREDIT_SPOT";

    /** 利率波动率（irVol 字段） */
    public static final String RF_TYPE_IR_VOL = "IR_VOL";

    /** 权益即期价格（eqSpot 字段） */
    public static final String RF_TYPE_EQ_SPOT = "EQ_SPOT";

    /** 权益波动率（eqVol 字段） */
    public static final String RF_TYPE_EQ_VOL = "EQ_VOL";

    /** 外汇即期价格（fxSpot 字段） */
    public static final String RF_TYPE_FX_SPOT = "FX_SPOT";

    /** 外汇波动率（fxVol 字段） */
    public static final String RF_TYPE_FX_VOL = "FX_VOL";

    /** 大宗商品即期价格（commSpot 字段） */
    public static final String RF_TYPE_COMM_SPOT = "COMM_SPOT";

    /** 大宗商品波动率（commVol 字段） */
    public static final String RF_TYPE_COMM_VOL = "COMM_VOL";

    // ===================== NMRF 分类 =====================

    /** 特异性信用 NMRF（MAR33.17） */
    public static final String NMRF_TYPE_IDIO_CREDIT = "IDIO_CREDIT";

    /** 特异性权益 NMRF（MAR33.17） */
    public static final String NMRF_TYPE_IDIO_EQUITY = "IDIO_EQUITY";

    /** 其他系统性 NMRF（MAR33.17） */
    public static final String NMRF_TYPE_OTHER = "OTHER";
}
