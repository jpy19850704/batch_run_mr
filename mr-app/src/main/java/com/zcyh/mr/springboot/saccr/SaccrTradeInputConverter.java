package com.zcyh.mr.springboot.saccr;

/**
 * SA-CCR 单产品交易输入转换器。
 */
public interface SaccrTradeInputConverter {
    boolean supports(String productCode);

    SaccrTradeRow convert(SaccrTradeConvertContext context);
}
