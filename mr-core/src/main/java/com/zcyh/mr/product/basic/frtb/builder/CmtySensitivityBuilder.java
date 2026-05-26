package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品风险类别敏感性构建器。
 * 内聚商品类的 dependency 规则、bucket 规则、顶点拆分与 curvature 规则。
 */
public class CmtySensitivityBuilder extends AbstractSensitivityBuilder {

    private CmtySensitivityBuilder() {
    }

    public static List<FrtbDependency> buildDeltaDependencies(String priceCurve, String riskFactorId, String bucket) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(bucket)) {
            return dependencies;
        }
        dependencies.add(FrtbDependency.of(
                FrtbDependency.TYPE_CMTY_DELTA,
                hasText(priceCurve) ? priceCurve : (hasText(riskFactorId) ? riskFactorId : "CMTY"),
                riskFactorId,
                bucket));
        return dependencies;
    }

    public static List<FrtbDependency> buildVegaDependencies(String volatilitySurface, String riskFactorId, String bucket) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(volatilitySurface)) {
            return dependencies;
        }
        dependencies.add(FrtbDependency.of(
                FrtbDependency.TYPE_CMTY_VEGA,
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

        FrtbDependency deltaDependency = firstDependency(deltaDependencies);
        List<FrtbSenes> deltaSensitivities = new ArrayList<>();
        if (enableDelta && deltaDependency != null) {
            List<FrtbMarketData> deltaMarkets = MarketData.getFrtbMarketDataListCMTYDelta(
                    marketData,
                    deltaDependency.curveOrRiskFactor);
            for (FrtbMarketData shock : deltaMarkets) {
                if (shock == null || shock.marketData == null) {
                    continue;
                }
                MeasureValuation shockedValuation = repriceFunction.reprice(shock.marketData);
                if (shockedValuation == null) {
                    continue;
                }
                if (isNoChangeByCny(baseValuation, shockedValuation, zeroTolerance)) {
                    continue;
                }
                FrtbSenes sensitivity = buildBaseSensitivity(instrumentId, instrumentCurrency, shock);
                sensitivity.riskFactorId = hasText(deltaDependency.riskFactorId) ? deltaDependency.riskFactorId : shock.riskFactorId;
                sensitivity.riskFactorBucket = hasText(deltaDependency.bucket) ? deltaDependency.bucket : shock.riskFactorBucket;
                sensitivity.riskFactorVertex1 = shock.riskFactorVertex1;
                sensitivity.riskFactorType = "";
                sensitivity.sensitivityType = "Delta";
                sensitivity.sensitivityValInstCurr = normalizeDelta(shockedValuation.valuation, baseValuation.valuation);
                sensitivity.sensitivityValInstCurrCny = normalizeDelta(shockedValuation.valuationCny, baseValuation.valuationCny);
                sensitivities.add(sensitivity);
                deltaSensitivities.add(sensitivity);
            }
        }

        if (enableCurvature && deltaDependency != null) {
            Map<String, Double> deltaByBucket = sumByBucket(deltaSensitivities, false);
            Map<String, Double> deltaByBucketCny = sumByBucket(deltaSensitivities, true);
            String curvatureBucket = normalizeCmtyBucket(deltaDependency.bucket);
            List<FrtbMarketData> curvatureMarkets = MarketData.getFrtbMarketDataListCMTYCurvature(marketData, curvatureBucket);
            for (FrtbMarketData shock : curvatureMarkets) {
                if (shock == null || shock.marketData == null) {
                    continue;
                }
                MeasureValuation shockedValuation = repriceFunction.reprice(shock.marketData);
                if (shockedValuation == null) {
                    continue;
                }
                if (isNoChangeByCny(baseValuation, shockedValuation, zeroTolerance)) {
                    continue;
                }
                FrtbSenes sensitivity = buildBaseSensitivity(instrumentId, instrumentCurrency, shock);
                sensitivity.riskFactorId = hasText(deltaDependency.riskFactorId) ? deltaDependency.riskFactorId : shock.riskFactorId;
                sensitivity.riskFactorBucket = hasText(deltaDependency.bucket) ? deltaDependency.bucket : shock.riskFactorBucket;
                sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation)
                        + deltaByBucket.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseValuation.valuationCny)
                        + deltaByBucketCny.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                sensitivities.add(sensitivity);
            }
        }

        FrtbDependency vegaDependency = firstDependency(vegaDependencies);
        if (vegaDependency == null) {
            return sensitivities;
        }
        List<FrtbMarketData> vegaShocks = MarketData.getFrtbMarketDataListVegaTenor(
                marketData,
                dataDate,
                Constants.FRTB.SA.RISK_CLASS.CR,
                vegaDependency.curveOrRiskFactor);
        for (FrtbMarketData vegaShock : vegaShocks) {
            if (vegaShock == null || vegaShock.marketData == null) {
                continue;
            }
            if (beforeVegaReprice != null) {
                beforeVegaReprice.run();
            }
            MeasureValuation shockedValuation = repriceFunction.reprice(vegaShock.marketData);
            if (shockedValuation == null) {
                continue;
            }
            if (isNoChangeByCny(baseValuation, shockedValuation, zeroTolerance)) {
                continue;
            }
            FrtbSenes sensitivity = buildBaseSensitivity(instrumentId, instrumentCurrency, vegaShock);
            sensitivity.riskFactorId = hasText(vegaDependency.riskFactorId) ? vegaDependency.riskFactorId : vegaShock.riskFactorId;
            sensitivity.riskFactorBucket = hasText(vegaDependency.bucket) ? vegaDependency.bucket : vegaShock.riskFactorBucket;
            sensitivity.riskFactorVertex1 = vegaShock.riskFactorVertex1;
            sensitivity.sensitivityValInstCurr = normalizeVega(shockedValuation.valuation, baseValuation.valuation);
            sensitivity.sensitivityValInstCurrCny = normalizeVega(shockedValuation.valuationCny, baseValuation.valuationCny);
            sensitivities.add(sensitivity);
        }
        return sensitivities;
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

    private static FrtbDependency firstDependency(List<FrtbDependency> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return null;
        }
        for (FrtbDependency dependency : dependencies) {
            if (dependency != null) {
                return dependency;
            }
        }
        return null;
    }

    private static String normalizeCmtyBucket(String bucket) {
        if (!hasText(bucket)) {
            return "";
        }
        Matcher matcher = Pattern.compile("(\\d+)\\s*$").matcher(bucket.trim());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return bucket.trim();
    }

    @FunctionalInterface
    public interface RepriceFunction {
        MeasureValuation reprice(MarketData shockedMarketData);
    }
}
