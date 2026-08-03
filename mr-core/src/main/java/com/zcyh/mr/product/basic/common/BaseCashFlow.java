package com.zcyh.mr.product.basic.common;

import com.alibaba.fastjson2.annotation.JSONField;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 现金流公共基类
 * 包含所有产品现金流的通用字段，子类按需扩展
 * 使用包装类 Double/String，未赋值时 JSON 不输出
 */
public class BaseCashFlow {
    /** 付款日期 */
    @JSONField(name = "PAYMENT_DATE", format = "yyyy-MM-dd", ordinal = 1)
    public LocalDate paymentDate;

    /** 币种 */
    @JSONField(name = "CURRENCY_CODE", ordinal = 2)
    public String currencyCode;

    /** 现金流类型：FIXED_INTEREST / FLOAT_INTEREST / PRINCIPAL 等 */
    @JSONField(name = "CASHFLOW_TYPE", ordinal = 3)
    public String cashFlowType;

    /** 现金流金额 */
    @JSONField(name = "CASHFLOW", ordinal = 4)
    public Double cashflow;

    /** 折现利率 */
    @JSONField(name = "DISCOUNT_RATE", ordinal = 5)
    public Double discountRate;

    /** 距付款日剩余天数，由 paymentDate - dataDate 自动计算 */
    @JSONField(name = "PAYMENT_DAYS", ordinal = 6)
    public Long getPaymentDays() {
        if (dataDate != null && paymentDate != null) {
            return ChronoUnit.DAYS.between(dataDate, paymentDate);
        }
        return null;
    }

    /** 折现因子 */
    @JSONField(name = "DISCOUNT_FACTOR", ordinal = 7)
    public Double discountFactor;

    /** 现金流现值，由 cashflow × discountFactor 自动计算 */
    @JSONField(name = "CASHFLOW_PV", ordinal = 8)
    public Double getCashflowPv() {
        if (cashflow != null && discountFactor != null) {
            return cashflow * discountFactor;
        }
        return null;
    }

    /** 数据日期，用于计算剩余天数 */
    @JSONField(name = "DATA_DATE", format = "yyyy-MM-dd", ordinal = 99)
    public LocalDate dataDate;

}
