package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EQ 风险类别敏感性构建器。
 * 内聚 EQ 的 dependency 规则、bucket 规则、vega 顶点拆分与 curvature 规则。
 */
public class EqSensitivityBuilder extends AbstractSensitivityBuilder {

    private EqSensitivityBuilder() {
    }

    public static List<FrtbDependency> buildDeltaDependencies(String priceCurve, String bucket) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(priceCurve) || !hasText(bucket)) {
            return dependencies;
        }
        dependencies.add(FrtbDependency.of(
                FrtbDependency.TYPE_EQ_DELTA,
                priceCurve,
                priceCurve,
                bucket.trim()));
        return dependencies;
    }

    public static List<FrtbDependency> buildVegaDependencies(String volatilitySurface, String riskFactorId, String bucket) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(volatilitySurface) || !hasText(bucket)) {
            return dependencies;
        }
        dependencies.add(FrtbDependency.of(
                FrtbDependency.TYPE_EQ_VEGA,
                volatilitySurface,
                hasText(riskFactorId) ? riskFactorId : volatilitySurface,
                bucket.trim()));
        return dependencies;
    }

    public static boolean warnMissingSensitivityInputs(Measure measure, String eqBucket) {
        if (hasText(eqBucket)) {
            return false;
        }
        if (measure != null) {
            measure.addWarningLog("FRTB_EQ_BUCKET为空，跳过EQ敏感性计算");
        }
        return true;
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

        List<FrtbSenes> deltaSensitivities = new ArrayList<>();
        if (enableDelta && deltaDependencies != null) {
            for (FrtbDependency dependency : deltaDependencies) {
                if (dependency == null || !hasText(dependency.curveOrRiskFactor)) {
                    continue;
                }
                FrtbMarketData shock = MarketData.getFrtbMarketDataListEQDelta(
                        marketData,
                        dependency.curveOrRiskFactor,
                        dependency.bucket);
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
                sensitivity.riskFactorId = hasText(dependency.riskFactorId) ? dependency.riskFactorId : shock.riskFactorId;
                sensitivity.riskFactorBucket = hasText(dependency.bucket) ? dependency.bucket : shock.riskFactorBucket;
                sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation) * 100.0;
                sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseValuation.valuationCny) * 100.0;
                sensitivities.add(sensitivity);
                deltaSensitivities.add(sensitivity);
            }
        }

        if (enableCurvature && deltaDependencies != null) {
            Map<String, Double> deltaByBucket = sumByBucket(deltaSensitivities, false);
            Map<String, Double> deltaByBucketCny = sumByBucket(deltaSensitivities, true);
            for (FrtbDependency dependency : deltaDependencies) {
                if (dependency == null || !hasText(dependency.curveOrRiskFactor)) {
                    continue;
                }
                List<FrtbMarketData> curvatureMarkets = MarketData.getFrtbMarketDataListEQCurvature(
                        marketData,
                        dependency.curveOrRiskFactor,
                        dependency.bucket);
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
                    sensitivity.riskFactorId = hasText(dependency.riskFactorId) ? dependency.riskFactorId : shock.riskFactorId;
                    sensitivity.riskFactorBucket = hasText(dependency.bucket) ? dependency.bucket : shock.riskFactorBucket;
                    sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation)
                            + deltaByBucket.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                    sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseValuation.valuationCny)
                            + deltaByBucketCny.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
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
                    EngineConstants.FRTB.SA.RISK_CLASS.ER,
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
