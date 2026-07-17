package com.zcyh.mr.springboot.config;

import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 将 Spring Boot 系统参数同步到 mr-core 运行时配置。
 */
@Configuration
public class MrCoreRuntimeConfig {

    public MrCoreRuntimeConfig(
            @Value("${mr.fx.base-currency:CNY}") String fxBaseCurrency,
            @Value("${mr.fx-spot.base-currency:USD}") String fxSpotBaseCurrency,
            @Value("${mr.frtb.fx-sensitivity-shock-cny:true}") String fxSensitivityShockCny,
            @Value("${mr.vv.non-negative-floor-enabled:false}") String vvNonNegativeFloorEnabled) {
        EngineConfiguration configure = EngineConfiguration.getInstance();
        configure.setValue(EngineConstants.CFG.FX_BASE_CODE, normalizeCurrency(fxBaseCurrency, "CNY"));
        configure.setValue(EngineConstants.CFG.FX_SPOT_BASE_CODE, normalizeCurrency(fxSpotBaseCurrency, "USD"));
        configure.setValue(EngineConstants.CFG.FRTB_FX_SENSITIVITY_SHOCK_CNY,
                normalizeBoolean(fxSensitivityShockCny, "mr.frtb.fx-sensitivity-shock-cny"));
        configure.setValue(EngineConstants.CFG.VV_NON_NEGATIVE_FLOOR_ENABLED,
                normalizeBoolean(vvNonNegativeFloorEnabled, "mr.vv.non-negative-floor-enabled"));
    }

    private String normalizeCurrency(String currency, String defaultCurrency) {
        if (currency == null || currency.trim().isEmpty()) {
            return defaultCurrency;
        }
        return currency.trim().toUpperCase();
    }

    private String normalizeBoolean(String value, String propertyName) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if ("true".equals(normalized) || "false".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("布尔配置缺失或非法: " + propertyName);
    }
}
