package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FX 风险类别敏感性构建器。
 * 内聚 FX 的 dependency 规则、bucket 规则、vega 顶点拆分与 curvature 规则。
 */
public class FxSensitivityBuilder extends AbstractSensitivityBuilder {

    private FxSensitivityBuilder() {
    }

    private static boolean isCnyOrCnh(String currency) {
        return "CNY".equalsIgnoreCase(currency) || "CNH".equalsIgnoreCase(currency);
    }

    public static List<FrtbDependency> buildDeltaDependencies(List<String> riskCurrencies) {
        return buildDeltaDependencies(riskCurrencies, null);
    }

    public static List<FrtbDependency> buildDeltaDependencies(List<String> riskCurrencies, String fxPair) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (riskCurrencies == null || riskCurrencies.isEmpty()) {
            return dependencies;
        }
        for (String currency : riskCurrencies) {
            if (!hasText(currency) || isCnyOrCnh(currency)) {
                continue;
            }
            FrtbDependency dependency = FrtbDependency.of(
                    FrtbDependency.TYPE_FX_DELTA,
                    currency,
                    currency + "/CNY",
                    currency);
            dependency.fxPair = fxPair;
            dependencies.add(dependency);
        }
        return dependencies;
    }

    public static List<FrtbDependency> buildVegaDependencies(String volatilitySurface, String riskFactorId, String bucket) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(volatilitySurface)) {
            return dependencies;
        }
        dependencies.add(FrtbDependency.of(
                FrtbDependency.TYPE_FX_VEGA,
                volatilitySurface,
                riskFactorId,
                bucket));
        return dependencies;
    }

    public static List<FrtbSenes> buildSensitivities(
            MarketData marketData,
            LocalDate dataDate,
            LocalDate settleDate,
            List<FrtbDependency> deltaDependencies,
            List<FrtbDependency> vegaDependencies,
            boolean enableDelta,
            boolean enableCurvature,
            String instrumentId,
            String instrumentCurrency,
            double zeroTolerance,
            MeasureValuation baseValuation,
            RepriceFunction repriceFunction,
            Runnable beforeVegaReprice) {

        List<FrtbSenes> sensitivities = new ArrayList<>();
        if (marketData == null || dataDate == null || baseValuation == null || repriceFunction == null) {
            return sensitivities;
        }
        boolean shockCny = isFxSensitivityShockCny();
        double baseFxRate = shockCny ? 0.0 : resolveBaseFxRate(marketData, instrumentCurrency);

        List<FrtbSenes> deltaSensitivities = new ArrayList<>();
        if (enableDelta && deltaDependencies != null) {
            for (FrtbDependency dependency : deltaDependencies) {
                if (dependency == null || !hasText(dependency.curveOrRiskFactor)) {
                    continue;
                }
                FrtbMarketData shock = MarketData.getFrtbMarketDataListFXDelta(marketData, dependency.curveOrRiskFactor);
                if (shock == null || shock.marketData == null) {
                    continue;
                }
                MeasureValuation shockedValuation = repriceFunction.reprice(shock.marketData);
                if (shockedValuation == null) {
                    continue;
                }
                if (isNoChangeForDeltaCurvature(baseValuation, shockedValuation, zeroTolerance, shockCny)) {
                    continue;
                }
                FrtbSenes sensitivity = buildBaseSensitivity(instrumentId, instrumentCurrency, shock);
                sensitivity.riskFactorId = hasText(dependency.riskFactorId) ? dependency.riskFactorId : shock.riskFactorId;
                sensitivity.riskFactorBucket = hasText(dependency.bucket) ? dependency.bucket : shock.riskFactorBucket;
                sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation) * 100.0;
                sensitivity.sensitivityValInstCurrCny = shockCny
                        ? (shockedValuation.valuationCny - baseValuation.valuationCny) * 100.0
                        : sensitivity.sensitivityValInstCurr * baseFxRate;
                sensitivities.add(sensitivity);
                deltaSensitivities.add(sensitivity);
            }
        }

        if (enableCurvature && deltaDependencies != null) {
            Map<String, Double> deltaByBucket = sumByBucket(deltaSensitivities, false);
            Map<String, Double> deltaByBucketCny = shockCny ? sumByBucket(deltaSensitivities, true) : null;
            for (FrtbDependency dependency : deltaDependencies) {
                if (dependency == null || !hasText(dependency.curveOrRiskFactor)) {
                    continue;
                }
                List<FrtbMarketData> curvatureMarkets = MarketData.getFrtbMarketDataListFXCurvature(
                        marketData,
                        dependency.curveOrRiskFactor);
                for (FrtbMarketData shock : curvatureMarkets) {
                    if (shock == null || shock.marketData == null) {
                        continue;
                    }
                    MeasureValuation shockedValuation = repriceFunction.reprice(shock.marketData);
                    if (shockedValuation == null) {
                        continue;
                    }
                    if (isNoChangeForDeltaCurvature(baseValuation, shockedValuation, zeroTolerance, shockCny)) {
                        continue;
                    }
                    FrtbSenes sensitivity = buildBaseSensitivity(instrumentId, instrumentCurrency, shock);
                    sensitivity.riskFactorId = hasText(dependency.riskFactorId) ? dependency.riskFactorId : shock.riskFactorId;
                    sensitivity.riskFactorBucket = hasText(dependency.bucket) ? dependency.bucket : shock.riskFactorBucket;
                    sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation)
                            + deltaByBucket.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                    if (shockCny) {
                        sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseValuation.valuationCny)
                                + deltaByBucketCny.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                    }
                    if (shouldDivideCurvature(dependency.fxPair) && isCurvatureType(sensitivity.sensitivityType)) {
                        double curvatureScalar = FrtbParamsCache.getFxCurvatureScalar();
                        sensitivity.sensitivityValInstCurr = sensitivity.sensitivityValInstCurr / curvatureScalar;
                        if (shockCny) {
                            sensitivity.sensitivityValInstCurrCny = sensitivity.sensitivityValInstCurrCny / curvatureScalar;
                        }
                    }
                    if (!shockCny) {
                        sensitivity.sensitivityValInstCurrCny = sensitivity.sensitivityValInstCurr * baseFxRate;
                    }
                    sensitivities.add(sensitivity);
                }
            }
        }

        if (vegaDependencies == null || vegaDependencies.isEmpty()) {
            return sensitivities;
        }
        for (FrtbDependency dependency : vegaDependencies) {
            if (dependency == null || !hasText(dependency.curveOrRiskFactor)) {
                continue;
            }
            List<FrtbMarketData> vegaShocks = MarketData.getFrtbMarketDataListVegaTenor(
                    marketData,
                    dataDate,
                    EngineConstants.FRTB.SA.RISK_CLASS.FXR,
                    dependency.curveOrRiskFactor);
            for (FrtbMarketData shock : vegaShocks) {
                if (shock == null || shock.marketData == null) {
                    continue;
                }
                if (beforeVegaReprice != null) {
                    beforeVegaReprice.run();
                }
                MeasureValuation shockedValuation = repriceFunction.reprice(shock.marketData);
                if (shockedValuation == null) {
                    continue;
                }
                if (isNoChangeByCny(baseValuation, shockedValuation, zeroTolerance)) {
                    continue;
                }
                FrtbSenes sensitivity = buildBaseSensitivity(instrumentId, instrumentCurrency, shock);
                sensitivity.riskFactorId = hasText(dependency.riskFactorId) ? dependency.riskFactorId : shock.riskFactorId;
                sensitivity.riskFactorBucket = hasText(dependency.bucket) ? dependency.bucket : shock.riskFactorBucket;
                sensitivity.riskFactorVertex1 = shock.riskFactorVertex1;
                sensitivity.sensitivityValInstCurr = normalizeVega(shockedValuation.valuation, baseValuation.valuation);
                sensitivity.sensitivityValInstCurrCny = normalizeVega(shockedValuation.valuationCny, baseValuation.valuationCny);
                sensitivities.add(sensitivity);
            }
        }
        return sensitivities;
    }

    private static boolean shouldDivideCurvature(String fxPair) {
        if (!hasText(fxPair)) {
            return false;
        }
        String[] currencies = fxPair.split("/");
        if (currencies.length != 2) {
            return false;
        }
        String left = currencies[0] == null ? "" : currencies[0].trim().toUpperCase();
        String right = currencies[1] == null ? "" : currencies[1].trim().toUpperCase();
        if (!hasText(left) || !hasText(right)) {
            return false;
        }
        return !isCnyOrCnh(left) && !isCnyOrCnh(right);
    }

    private static boolean isFxSensitivityShockCny() {
        String value = EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FRTB_FX_SENSITIVITY_SHOCK_CNY);
        return value == null || value.trim().isEmpty() || Boolean.parseBoolean(value.trim());
    }

    private static boolean isNoChangeForDeltaCurvature(
            MeasureValuation baseValuation,
            MeasureValuation shockedValuation,
            double zeroTolerance,
            boolean shockCny) {
        if (shockCny) {
            return isNoChangeByCny(baseValuation, shockedValuation, zeroTolerance);
        }
        if (baseValuation == null || shockedValuation == null) {
            return true;
        }
        return Math.abs(shockedValuation.valuation - baseValuation.valuation) < zeroTolerance;
    }

    private static double resolveBaseFxRate(MarketData marketData, String instrumentCurrency) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        return fxSpot.getFxrate(instrumentCurrency);
    }

    private static boolean isCurvatureType(String sensitivityType) {
        if (!hasText(sensitivityType)) {
            return false;
        }
        // 与明细输出、聚合校验口径保持一致：仅识别标准值
        String normalized = sensitivityType.trim();
        return FrtbConstants.SENS_CURVATURE_UP.equals(normalized)
                || FrtbConstants.SENS_CURVATURE_DOWN.equals(normalized);
    }

    private static FrtbSenes buildBaseSensitivity(String instrumentId, String instrumentCurrency, FrtbMarketData shock) {
        FrtbSenes sensitivity = new FrtbSenes();
        sensitivity.instrumentId = instrumentId;
        sensitivity.instrumentCurrency = instrumentCurrency;
        sensitivity.riskFactorId = shock.riskFactorId;
        sensitivity.riskFactorVertex1 = shock.riskFactorVertex1;
        sensitivity.riskFactorClass = shock.riskFactorClass;
        sensitivity.riskFactorBucket = shock.riskFactorBucket;
        sensitivity.riskFactorType = shock.riskFactorType;
        sensitivity.sensitivityType = shock.sensitivityType;
        return sensitivity;
    }

    @FunctionalInterface
    public interface RepriceFunction {
        MeasureValuation reprice(MarketData shockedMarketData);
    }
}
