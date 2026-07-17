package com.zcyh.mr.springboot.measurement.saccr;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SA-CCR 交易转换器注册表。
 */
@Service
public class SaccrTradeInputConverterRegistry {
    private final Map<String, SaccrTradeInputConverter> converters = new LinkedHashMap<>();

    public SaccrTradeInputConverterRegistry() {
        register(new StandardSaccrTradeConverter("IRSCCS", "IR", false));
        register(new StandardSaccrTradeConverter("STD_IRS", "IR", false));
        register(new StandardSaccrTradeConverter("BOND_FUTURE", "IR", false));
        register(new StandardSaccrTradeConverter("CAPFLOOR", "IR", true));
        register(new StandardSaccrTradeConverter("SWAPTION", "IR", true));
        register(new StandardSaccrTradeConverter("FXFWD", "FX", false));
        register(new StandardSaccrTradeConverter("FXSWAP", "FX", false));
        register(new StandardSaccrTradeConverter("FXOPT", "FX", true));
        register(new StandardSaccrTradeConverter("COMMFWD", "COMMODITY", false));
        register(new StandardSaccrTradeConverter("COMMSWAP", "COMMODITY", false));
        register(new StandardSaccrTradeConverter("COMMOPT", "COMMODITY", true));
        register(new StandardSaccrTradeConverter("CDS", "CREDIT", false));
    }

    public SaccrTradeInputConverter require(String productCode) {
        String key = normalize(productCode);
        SaccrTradeInputConverter converter = converters.get(key);
        if (converter == null) {
            throw new IllegalArgumentException("SA-CCR 暂不支持产品: " + productCode);
        }
        return converter;
    }

    private void register(SaccrTradeInputConverter converter) {
        if (converter instanceof StandardSaccrTradeConverter) {
            StandardSaccrTradeConverter standard = (StandardSaccrTradeConverter) converter;
            converters.put(standard.productCode(), converter);
            return;
        }
        throw new IllegalArgumentException("转换器必须提供唯一 PRODUCT_CODE");
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PRODUCT_CODE 必填");
        }
        return value.trim().toUpperCase();
    }
}
