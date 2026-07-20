package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.all.GenericMc.GenericMcTradeInfo;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 通用 MC FRTB 敏感性构建器。
 */
public final class McFrtbBuilder {
    private static final double ZERO_TOLERANCE = 1e-12;

    private McFrtbBuilder() {
    }

    @FunctionalInterface
    public interface RepriceFunction {
        OptionMeasure reprice(MarketData shockedMarketData);
    }

    public static List<FrtbSenes> build(GenericMcTradeInfo input, McPricingContext ctx, OptionMeasure measure,
            MarketData marketData, LocalDate dataDate, RepriceFunction repriceFunction) {
        if (input == null || ctx == null || measure == null || repriceFunction == null) {
            return new ArrayList<>();
        }
        MeasureValuation baseValuation = toMeasureValuation(measure);
        String type = ctx.underlyingType == null ? "" : ctx.underlyingType.trim().toUpperCase();
        if ("FX".equals(type)) {
            return FxFrtb.build(input, ctx, baseValuation, marketData, dataDate, repriceFunction);
        }
        if ("EQ".equals(type)) {
            return EqFrtb.build(input, ctx, measure, baseValuation, marketData, dataDate, repriceFunction);
        }
        if ("COMM".equals(type)) {
            return CommFrtb.build(input, ctx, measure, baseValuation, marketData, dataDate, repriceFunction);
        }
        if ("IR".equals(type)) {
            return IrFrtb.build(input, ctx, baseValuation, marketData, dataDate, repriceFunction);
        }
        return new ArrayList<>();
    }

    private static final class FxFrtb {
        private static List<FrtbSenes> build(GenericMcTradeInfo input, McPricingContext ctx, MeasureValuation baseValuation,
                MarketData marketData, LocalDate dataDate, RepriceFunction repriceFunction) {
            List<FrtbSenes> list = new ArrayList<>();
            GenericMcTradeInfo c = input;
            List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                    collectFxRiskCurrencies(c.underlyingCurrencyCode, c.baseCurrencyCode, c.currencyCode),
                    FrtbSensitivityBuilder.buildFxPair(c.underlyingCurrencyCode, c.baseCurrencyCode));
            List<FrtbDependency> fxVegaDependencies = FrtbSensitivityBuilder.buildFxVegaDependencies(
                    c.volatilitySurface,
                    "FX_" + upper(c.underlyingCurrencyCode) + "_" + upper(c.baseCurrencyCode) + "_VOL",
                    upper(c.underlyingCurrencyCode) + "/" + upper(c.baseCurrencyCode));
            list.addAll(FrtbSensitivityBuilder.buildFxSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    fxDeltaDependencies,
                    fxVegaDependencies,
                    true,
                    true,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData))));

            Map<String, String> curveMap = new HashMap<>();
            putIfHasText(curveMap, c.baseDiscountCurve, c.baseCurrencyCode);
            putIfHasText(curveMap, c.underlyingDiscountCurve, c.underlyingCurrencyCode);
            putIfHasText(curveMap, c.discountCurve, c.currencyCode);
            list.addAll(FrtbSensitivityBuilder.buildGirrSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                    Collections.emptyList(),
                    true,
                    false,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData)),
                    null,
                    null));
            return list;
        }
    }

    private static final class EqFrtb {
        private static List<FrtbSenes> build(GenericMcTradeInfo input, McPricingContext ctx, OptionMeasure measure,
                MeasureValuation baseValuation, MarketData marketData, LocalDate dataDate,
                RepriceFunction repriceFunction) {
            List<FrtbSenes> list = new ArrayList<>();
            GenericMcTradeInfo c = input;
            list.addAll(FrtbSensitivityBuilder.buildFxSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildFxDeltaDependencies(collectFxRiskCurrencies(c.currencyCode)),
                    Collections.emptyList(),
                    true,
                    false,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData))));

            Map<String, String> curveMap = new HashMap<>();
            putIfHasText(curveMap, c.discountCurve, c.currencyCode);
            list.addAll(FrtbSensitivityBuilder.buildGirrSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                    Collections.emptyList(),
                    true,
                    false,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData)),
                    null,
                    null));

            String bucket = c.frtbEqBucket;
            if (FrtbSensitivityBuilder.warnMissingEqSensitivityInputs(measure, bucket)) {
                return list;
            }
            list.addAll(FrtbSensitivityBuilder.buildEqSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildEqDeltaDependencies(c.referenceCurve, bucket),
                    FrtbSensitivityBuilder.buildEqVegaDependencies(c.volatilitySurface, c.referenceCurve, bucket),
                    true,
                    true,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData)),
                    null));
            return list;
        }
    }

    private static final class CommFrtb {
        private static List<FrtbSenes> build(GenericMcTradeInfo input, McPricingContext ctx, OptionMeasure measure,
                MeasureValuation baseValuation, MarketData marketData, LocalDate dataDate,
                RepriceFunction repriceFunction) {
            List<FrtbSenes> list = new ArrayList<>();
            GenericMcTradeInfo c = input;
            list.addAll(FrtbSensitivityBuilder.buildFxSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildFxDeltaDependencies(collectFxRiskCurrencies(c.currencyCode)),
                    Collections.emptyList(),
                    true,
                    false,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData))));

            Map<String, String> curveMap = new HashMap<>();
            putIfHasText(curveMap, c.discountCurve, c.currencyCode);
            list.addAll(FrtbSensitivityBuilder.buildGirrSensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                    Collections.emptyList(),
                    true,
                    false,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData)),
                    null,
                    null));

            String commBucket = hasText(c.frtbCommBucket) ? c.frtbCommBucket.trim() : null;
            String riskFactorIdVega = resolveCmtyRiskFactorIdBase(c);
            if (FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                    measure,
                    c.instrumentId,
                    commBucket,
                    riskFactorIdVega)) {
                return list;
            }
            String riskFactorId = resolveCmtyRiskFactorId(c);
            list.addAll(FrtbSensitivityBuilder.buildCmtySensitivities(
                    marketData,
                    dataDate,
                    c.settleDate,
                    FrtbSensitivityBuilder.buildCmtyDeltaDependencies(c.referenceCurve, riskFactorId, commBucket),
                    FrtbSensitivityBuilder.buildCmtyVegaDependencies(c.volatilitySurface, riskFactorIdVega,
                            commBucket),
                    true,
                    true,
                    c.instrumentId,
                    c.currencyCode,
                    ZERO_TOLERANCE,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(repriceFunction.reprice(shockedMarketData)),
                    null));
            return list;
        }

        private static String resolveCmtyRiskFactorId(GenericMcTradeInfo c) {
            String base = resolveCmtyRiskFactorIdBase(c);
            if (!hasText(base)) {
                return null;
            }
            if (!hasText(c.frtbCommLocation)) {
                return base;
            }
            return base + "&" + c.frtbCommLocation.trim();
        }

        private static String resolveCmtyRiskFactorIdBase(GenericMcTradeInfo c) {
            if (hasText(c.frtbCommAsset)) {
                return c.frtbCommAsset.trim();
            }
            return null;
        }
    }

    private static final class IrFrtb {
        private static List<FrtbSenes> build(GenericMcTradeInfo input, McPricingContext ctx, MeasureValuation baseValuation,
                MarketData marketData, LocalDate dataDate, RepriceFunction repriceFunction) {
            return new ArrayList<>();
        }
    }

    private static MeasureValuation toMeasureValuation(OptionMeasure measure) {
        if (measure == null) {
            return null;
        }
        return MeasureValuation.of(measure.valuation, measure.valuationCny);
    }

    private static List<String> collectFxRiskCurrencies(String... currencies) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (currencies != null) {
            for (String currency : currencies) {
                if (hasText(currency)) {
                    set.add(currency.trim());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private static void putIfHasText(Map<String, String> map, String key, String value) {
        if (map != null && hasText(key) && hasText(value)) {
            map.put(key.trim(), value.trim());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
