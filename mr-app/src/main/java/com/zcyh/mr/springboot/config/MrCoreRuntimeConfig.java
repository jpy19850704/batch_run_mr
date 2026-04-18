package com.zcyh.mr.springboot.config;

import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 将 Spring Boot 系统参数同步到 mr-core 运行时配置。
 */
@Configuration
public class MrCoreRuntimeConfig {

    public MrCoreRuntimeConfig(
            @Value("${mr.fx.base-currency:CNY}") String fxBaseCurrency,
            @Value("${mr.fx-spot.base-currency:USD}") String fxSpotBaseCurrency) {
        Configure configure = Configure.getInstance();
        configure.setValue(Constants.CFG.FX_BASE_CODE, normalizeCurrency(fxBaseCurrency, "CNY"));
        configure.setValue(Constants.CFG.FX_SPOT_BASE_CODE, normalizeCurrency(fxSpotBaseCurrency, "USD"));
    }

    private String normalizeCurrency(String currency, String defaultCurrency) {
        if (currency == null || currency.trim().isEmpty()) {
            return defaultCurrency;
        }
        return currency.trim().toUpperCase();
    }
}
