package com.zcyh.mr.springboot.saccr;

import org.springframework.stereotype.Service;

/**
 * SA-CCR 交易输入转换服务。
 */
@Service
public class SaccrTradeInputConvertService {
    private final SaccrTradeInputConverterRegistry registry;

    public SaccrTradeInputConvertService(SaccrTradeInputConverterRegistry registry) {
        this.registry = registry;
    }

    public SaccrTradeRow convert(SaccrTradeConvertContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SaccrTradeConvertContext 不能为空");
        }
        return registry.require(context.productCode).convert(context);
    }
}
