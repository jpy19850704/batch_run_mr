package com.zcyh.mr.product.basic.common;

import com.alibaba.fastjson2.annotation.JSONField;

import java.time.LocalDate;

/**
 * 结构化现金流对象
 * 继承基础现金流公共字段，并扩展结构化产品所需字段
 */
public class ScfCashFlow extends BaseCashFlow {
    @JSONField(name = "RATE", ordinal = 9)
    public Double rate;

    @JSONField(name = "START_NOTIONAL", ordinal = 10)
    public Double startNotional;

    @JSONField(name = "END_NOTIONAL", ordinal = 11)
    public Double endNotional;

    @JSONField(name = "FWDSTART_DATE", format = "yyyyMMdd", ordinal = 12)
    public LocalDate fwdStartDat;

    @JSONField(name = "FWDEND_DATE", format = "yyyyMMdd", ordinal = 13)
    public LocalDate fwdEndDate;

    @JSONField(name = "VOLATILITY_RATE", ordinal = 14)
    public Double volatilityRate;

    @JSONField(name = "THEO_PAYMENT_DATE", format = "yyyyMMdd", ordinal = 15)
    public LocalDate theoPaymentDate;

    @JSONField(name = "PREPAYMENT_DATE", format = "yyyyMMdd", ordinal = 16)
    public LocalDate prepaymentDate;
}
