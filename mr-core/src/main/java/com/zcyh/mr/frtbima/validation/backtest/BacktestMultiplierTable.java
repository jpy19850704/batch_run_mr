package com.zcyh.mr.frtbima.validation.backtest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 回测乘数附加值查询表。
 * 规则依据：MAR32.4 Table（例外次数→乘数加法项k）
 *
 * 例外次数  区间    乘数附加值 k
 * 0-4      绿区   0
 * 5        黄区   0.40
 * 6        黄区   0.50
 * 7        黄区   0.65
 * 8        黄区   0.75
 * 9        黄区   0.85
 * ≥10      红区   1.00（模型不合格，此值仅为标记）
 */
public class BacktestMultiplierTable {

    private static final Map<Integer, BigDecimal> TABLE = new HashMap<>();

    static {
        TABLE.put(0, BigDecimal.ZERO);
        TABLE.put(1, BigDecimal.ZERO);
        TABLE.put(2, BigDecimal.ZERO);
        TABLE.put(3, BigDecimal.ZERO);
        TABLE.put(4, BigDecimal.ZERO);
        TABLE.put(5, new BigDecimal("0.40"));
        TABLE.put(6, new BigDecimal("0.50"));
        TABLE.put(7, new BigDecimal("0.65"));
        TABLE.put(8, new BigDecimal("0.75"));
        TABLE.put(9, new BigDecimal("0.85"));
    }

    /** 红区附加值标记（模型不合格） */
    public static final BigDecimal RED_ZONE_ADDON = BigDecimal.ONE;

    /**
     * 根据例外次数查询乘数附加值。
     *
     * @param exceptionCount 例外次数
     * @return 乘数附加值
     */
    public BigDecimal lookup(int exceptionCount) {
        if (exceptionCount >= 10) {
            return RED_ZONE_ADDON;
        }
        return TABLE.getOrDefault(exceptionCount, BigDecimal.ZERO);
    }
}
