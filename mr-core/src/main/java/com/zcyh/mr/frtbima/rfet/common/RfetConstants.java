package com.zcyh.mr.frtbima.rfet.common;

/**
 * RFET（实际价格资格测试）常量定义。
 * 规则依据：MAR31.12-31.16
 */
public final class RfetConstants {

    private RfetConstants() {
    }

    /** 监管桶内最少实际价格观测条数（MAR31.12：12个月内至少24笔）*/
    public static final int MIN_REAL_PRICES = 24;

    /** 实际价格观测有效期（天），12个月窗口 */
    public static final int OBSERVATION_WINDOW_DAYS = 365;

    /**
     * 90天分段周期（MAR31.12）。
     * 将12个月观测窗口按此长度划分为若干段，每段内至少需要 MIN_OBSERVATIONS_PER_PERIOD 条观测。
     */
    public static final int OBSERVATION_PERIOD_DAYS = 90;

    /**
     * 每个90天段内最少观测条数（MAR31.12）。
     * 若任意一段内的观测数 < 4，则规则2失败（需通过 Own Bucket 补救）。
     */
    public static final int MIN_OBSERVATIONS_PER_PERIOD = 4;

    /**
     * Own Bucket 最低观测数（MAR31.15）。
     * 当监管桶判定失败时，若同 curveId 下所有桶的观测总数 >= 100，可通过 Own Bucket 补救为 MODELLABLE。
     */
    public static final int OWN_BUCKET_MIN_OBSERVATIONS = 100;

    /** 价格来源：成交价格 */
    public static final String SOURCE_TRADE = "TRADE";

    /** 价格来源：供应商数据 */
    public static final String SOURCE_VENDOR = "VENDOR";

    /** 价格来源：字符串数据 */
    public static final String SOURCE_STRING = "STRING";
}
