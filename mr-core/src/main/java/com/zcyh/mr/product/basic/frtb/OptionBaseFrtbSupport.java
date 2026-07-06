package com.zcyh.mr.product.basic.frtb;

import com.zcyh.mr.product.basic.common.OptionMeasure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 期权产品 FRTB 模板层公共辅助方法。
 */
public final class OptionBaseFrtbSupport {

    private OptionBaseFrtbSupport() {
    }

    public static MeasureValuation toMeasureValuation(OptionMeasure measure) {
        if (measure == null) {
            return null;
        }
        return MeasureValuation.of(measure.valuation, measure.valuationCny);
    }

    public static List<String> collectFxRiskCurrencies(String... currencies) {
        LinkedHashSet<String> fxRiskCurrencies = new LinkedHashSet<>();
        if (currencies == null) {
            return new ArrayList<>();
        }
        for (String currency : currencies) {
            if (currency == null || currency.trim().isEmpty()) {
                continue;
            }
            fxRiskCurrencies.add(currency);
        }
        return new ArrayList<>(fxRiskCurrencies);
    }

    public static List<FrtbDependency> buildFxVegaDependencies(
            String underlyingCurrencyCode,
            String baseCurrencyCode,
            String volatilitySurface) {
        String undCcy = normalizeCcy(underlyingCurrencyCode);
        String baseCcy = normalizeCcy(baseCurrencyCode);
        String riskFactorId = "FX_" + undCcy + "_" + baseCcy + "_VOL";
        String bucket = undCcy + "/" + baseCcy;
        return FrtbSensitivityBuilder.buildFxVegaDependencies(volatilitySurface, riskFactorId, bucket);
    }

    public static boolean addMissingCmtyDependencyWarnings(
            OptionMeasure measure,
            String instrumentId,
            String cmtyBucket,
            String cmtyRiskFactorId,
            String cmtyRiskFactorIdVega) {
        return FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                measure,
                instrumentId,
                cmtyBucket,
                cmtyRiskFactorId,
                cmtyRiskFactorIdVega);
    }

    private static String normalizeCcy(String ccy) {
        return ccy == null ? "" : ccy.trim().toUpperCase(Locale.ROOT);
    }
}
