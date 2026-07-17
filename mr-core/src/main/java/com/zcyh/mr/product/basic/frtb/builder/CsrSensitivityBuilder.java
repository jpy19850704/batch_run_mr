package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSR 风险类别敏感性构建器。
 * 第一阶段仅支持 CSR(non-sec) 与 CSR(non-ctp) 的 Delta / Curvature。
 */
public class CsrSensitivityBuilder extends AbstractSensitivityBuilder {

    private CsrSensitivityBuilder() {
    }

    public static List<FrtbDependency> buildNonSecDeltaDependencies(
            String curve,
            String riskFactorId,
            String bucket,
            String outputType) {
        return buildDeltaDependencies(curve, riskFactorId, bucket, outputType, FrtbDependency.TYPE_CSR_NSEC_DELTA);
    }

    public static List<FrtbDependency> buildSecNonCtpDeltaDependencies(
            String curve,
            String riskFactorId,
            String bucket,
            String outputType) {
        return buildDeltaDependencies(curve, riskFactorId, bucket, outputType, FrtbDependency.TYPE_CSR_SECNCTP_DELTA);
    }

    private static List<FrtbDependency> buildDeltaDependencies(
            String curve,
            String riskFactorId,
            String bucket,
            String outputType,
            String type) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(curve) || !hasText(riskFactorId) || !hasText(bucket) || !hasText(outputType)) {
            return dependencies;
        }
        dependencies.add(FrtbDependency.of(type, curve, riskFactorId, bucket, outputType));
        return dependencies;
    }

    public static List<FrtbSenes> buildSensitivities(
            MarketData marketData,
            LocalDate dataDate,
            List<FrtbDependency> deltaDependencies,
            boolean enableDelta,
            boolean enableCurvature,
            String instrumentId,
            String instrumentCurrency,
            double zeroTolerance,
            MeasureValuation baseValuation,
            RepriceFunction repriceFunction) {

        List<FrtbSenes> sensitivities = new ArrayList<>();
        if (marketData == null || dataDate == null || baseValuation == null || repriceFunction == null) {
            return sensitivities;
        }

        List<FrtbSenes> deltaSensitivities = new ArrayList<>();
        if (enableDelta && deltaDependencies != null) {
            for (FrtbDependency dependency : deltaDependencies) {
                if (dependency == null || !hasText(dependency.curveOrRiskFactor) || !hasText(dependency.bucket)) {
                    continue;
                }
                HashMap<String, String> curveMap = new LinkedHashMap<>();
                curveMap.put(dependency.curveOrRiskFactor, dependency.bucket);
                List<FrtbMarketData> deltaMarkets = MarketData.getFrtbMarketDataListCSR(marketData, dataDate, curveMap);
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
                    sensitivity.riskFactorId = dependency.riskFactorId;
                    sensitivity.riskFactorBucket = dependency.bucket;
                    sensitivity.riskFactorClass = resolveRiskClass(dependency.type);
                    sensitivity.riskFactorType = hasText(dependency.outputType) ? dependency.outputType : shock.riskFactorType;
                    sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation) * 10000.0;
                    sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseValuation.valuationCny) * 10000.0;
                    sensitivities.add(sensitivity);
                    deltaSensitivities.add(sensitivity);
                }
            }
        }

        if (enableCurvature && deltaDependencies != null) {
            Map<String, Double> deltaByBucket = sumByBucket(deltaSensitivities, false);
            Map<String, Double> deltaByBucketCny = sumByBucket(deltaSensitivities, true);
            HashMap<String, List<String>> bucketCurveMap = toBucketCurveMap(deltaDependencies);
            for (Map.Entry<String, List<String>> entry : bucketCurveMap.entrySet()) {
                String bucket = entry.getKey();
                List<String> curveList = entry.getValue();
                if (!hasText(bucket) || curveList == null || curveList.isEmpty()) {
                    continue;
                }
                FrtbDependency dependency = findByBucket(deltaDependencies, bucket);
                if (dependency == null) {
                    continue;
                }
                if (!isInteger(bucket)) {
                    // 非数字 bucket 先保留 Delta，Curvature 暂不展开。
                    continue;
                }
                List<FrtbMarketData> curvatureMarkets = MarketData.getFrtbMarketDataListCSRCurvature(
                        marketData,
                        resolveRiskClass(dependency.type),
                        Integer.parseInt(bucket),
                        toCurveMap(bucket, curveList));
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
                    sensitivity.riskFactorId = dependency.riskFactorId;
                    sensitivity.riskFactorBucket = bucket;
                    sensitivity.riskFactorClass = resolveRiskClass(dependency.type);
                    sensitivity.riskFactorType = hasText(dependency.outputType) ? dependency.outputType : shock.riskFactorType;
                    sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseValuation.valuation)
                            + deltaByBucket.getOrDefault(bucket, 0.0) * shock.riskWeight;
                    sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseValuation.valuationCny)
                            + deltaByBucketCny.getOrDefault(bucket, 0.0) * shock.riskWeight;
                    sensitivities.add(sensitivity);
                }
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

    private static boolean isInteger(String text) {
        if (!hasText(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String resolveRiskClass(String dependencyType) {
        if (FrtbDependency.TYPE_CSR_SECNCTP_DELTA.equalsIgnoreCase(dependencyType)) {
            return EngineConstants.FRTB.SA.RISK_CLASS.CSR_S_N_CTP;
        }
        return EngineConstants.FRTB.SA.RISK_CLASS.CSR_N;
    }

    private static HashMap<String, List<String>> toBucketCurveMap(List<FrtbDependency> dependencies) {
        HashMap<String, List<String>> map = new LinkedHashMap<>();
        if (dependencies == null) {
            return map;
        }
        for (FrtbDependency dependency : dependencies) {
            if (dependency == null || !hasText(dependency.bucket) || !hasText(dependency.curveOrRiskFactor)) {
                continue;
            }
            List<String> curveList = map.computeIfAbsent(dependency.bucket, k -> new ArrayList<>());
            if (!curveList.contains(dependency.curveOrRiskFactor)) {
                curveList.add(dependency.curveOrRiskFactor);
            }
        }
        return map;
    }

    private static HashMap<String, List<String>> toCurveMap(String bucket, List<String> curveList) {
        HashMap<String, List<String>> map = new LinkedHashMap<>();
        map.put(bucket, curveList);
        return map;
    }

    private static FrtbDependency findByBucket(List<FrtbDependency> dependencies, String bucket) {
        if (dependencies == null || !hasText(bucket)) {
            return null;
        }
        for (FrtbDependency dependency : dependencies) {
            if (dependency == null) {
                continue;
            }
            if (bucket.equalsIgnoreCase(dependency.bucket)) {
                return dependency;
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface RepriceFunction {
        MeasureValuation reprice(MarketData shockedMarketData);
    }
}
