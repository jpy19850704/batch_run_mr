package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.IrSpot;
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
 * GIRR 风险类别敏感性构建器。
 * 内聚 GIRR 的 dependency 规则、bucket 规则、vega 顶点拆分与 curvature 规则。
 */
public class GirrSensitivityBuilder extends AbstractSensitivityBuilder {

    private static final double GIRR_DELTA_SCALE = 10000.0;
    private static final double GIRR_BASIS_SHIFT = 0.0001;

    private GirrSensitivityBuilder() {
    }

    public static List<FrtbDependency> buildDeltaDependencies(Map<String, String> curveBucketMap) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (curveBucketMap == null || curveBucketMap.isEmpty()) {
            return dependencies;
        }
        for (Map.Entry<String, String> entry : curveBucketMap.entrySet()) {
            if (!hasText(entry.getKey()) || !hasText(entry.getValue())) {
                continue;
            }
            dependencies.add(FrtbDependency.of(
                    FrtbDependency.TYPE_GIRR_DELTA,
                    entry.getKey(),
                    entry.getKey(),
                    normalizeGirrBucket(entry.getValue())));
        }
        return dependencies;
    }

    /**
     * 根据 CCS 两条腿的币种与曲线，展开 GIRR Basis Delta 依赖。
     */
    public static List<FrtbDependency> buildDeltaBasisDependencies(
            String currency1,
            String curve1,
            String currency2,
            String curve2) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        String ccy1 = normalizeCurrency(currency1);
        String ccy2 = normalizeCurrency(currency2);
        String c1 = trimToNull(curve1);
        String c2 = trimToNull(curve2);
        if (!hasText(ccy1) || !hasText(ccy2) || !hasText(c1) || !hasText(c2) || ccy1.equalsIgnoreCase(ccy2)) {
            return dependencies;
        }
        if ("USD".equalsIgnoreCase(ccy1)) {
            dependencies.add(buildBasisDependency(ccy2, c2, ccy1, c1, "USD"));
            return dependencies;
        }
        if ("USD".equalsIgnoreCase(ccy2)) {
            dependencies.add(buildBasisDependency(ccy1, c1, ccy2, c2, "USD"));
            return dependencies;
        }
        if ("EUR".equalsIgnoreCase(ccy1)) {
            dependencies.add(buildBasisDependency(ccy2, c2, ccy1, c1, "EUR"));
            return dependencies;
        }
        if ("EUR".equalsIgnoreCase(ccy2)) {
            dependencies.add(buildBasisDependency(ccy1, c1, ccy2, c2, "EUR"));
            return dependencies;
        }
        dependencies.add(buildBasisDependency(ccy1, c1, ccy2, c2, "USD"));
        dependencies.add(buildBasisDependency(ccy2, c2, ccy1, c1, "USD"));
        return dependencies;
    }

    public static List<FrtbDependency> buildVegaDependencies(String volatilitySurface, String bucket, String secondaryVertex) {
        List<FrtbDependency> dependencies = new ArrayList<>();
        if (!hasText(volatilitySurface)) {
            return dependencies;
        }
        FrtbDependency dependency = FrtbDependency.of(
                FrtbDependency.TYPE_GIRR_VEGA,
                volatilitySurface,
                volatilitySurface,
                normalizeGirrBucket(bucket));
        dependency.secondaryVertex = secondaryVertex;
        dependencies.add(dependency);
        return dependencies;
    }

    /**
     * 直接根据 Basis 依赖逐条 shock 对应曲线，统一生成 GIRR Basis Delta。
     */
    public static List<FrtbSenes> buildDeltaBasisSensitivities(
            MarketData marketData,
            LocalDate dataDate,
            List<FrtbDependency> basisDependencies,
            String instrumentId,
            String instrumentCurrency,
            double zeroTolerance,
            MeasureValuation baseSnapshot,
            RepriceFunction repriceFunction) {
        List<FrtbSenes> sensitivities = new ArrayList<>();
        if (marketData == null || dataDate == null || baseSnapshot == null || repriceFunction == null
                || basisDependencies == null || basisDependencies.isEmpty()) {
            return sensitivities;
        }
        for (FrtbDependency dependency : basisDependencies) {
            if (dependency == null || !FrtbDependency.TYPE_GIRR_DELTA_BASIS.equalsIgnoreCase(dependency.type)
                    || !hasText(dependency.curveOrRiskFactor) || !hasText(dependency.bucket)) {
                continue;
            }
            MarketData shockedMarketData = buildShiftedIrCurveMarketData(marketData, dependency.curveOrRiskFactor, GIRR_BASIS_SHIFT);
            if (shockedMarketData == null) {
                continue;
            }
            MeasureValuation shockedValuation = repriceFunction.reprice(shockedMarketData);
            if (shockedValuation == null || isNoChangeByCny(baseSnapshot, shockedValuation, zeroTolerance)) {
                continue;
            }
            FrtbSenes sensitivity = new FrtbSenes();
            sensitivity.instrumentId = instrumentId;
            sensitivity.riskFactorId = dependency.riskFactorId;
            sensitivity.riskFactorVertex1 = "";
            sensitivity.riskFactorVertex2 = "";
            sensitivity.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.GIRR;
            sensitivity.riskFactorBucket = normalizeGirrBucket(dependency.bucket);
            sensitivity.riskFactorType = "Basis";
            sensitivity.sensitivityType = "Delta";
            sensitivity.instrumentCurrency = instrumentCurrency;
            sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseSnapshot.valuation) * GIRR_DELTA_SCALE;
            sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseSnapshot.valuationCny) * GIRR_DELTA_SCALE;
            sensitivity.detail = buildSensitivityDetail(baseSnapshot, shockedValuation, GIRR_BASIS_SHIFT,
                    GIRR_DELTA_SCALE, 0.0, null, null);
            sensitivities.add(sensitivity);
        }
        return sensitivities;
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
            MeasureValuation baseSnapshot,
            RepriceFunction repriceFunction,
            RepriceFunction curvatureRepriceFunction,
            Runnable beforeVegaReprice) {

        List<FrtbSenes> sensitivities = new ArrayList<>();
        if (marketData == null || dataDate == null || baseSnapshot == null || repriceFunction == null) {
            return sensitivities;
        }

        HashMap<String, String> curveBucketMap = toCurveBucketMap(deltaDependencies);
        List<FrtbSenes> deltaSensitivities = new ArrayList<>();
        if (enableDelta && !curveBucketMap.isEmpty()) {
            List<FrtbMarketData> deltaMarkets = MarketData.getFrtbMarketDataListGIRRDelta(marketData, dataDate, curveBucketMap);
            for (FrtbMarketData shock : deltaMarkets) {
                if (shock == null || shock.marketData == null) {
                    continue;
                }
                MeasureValuation shockedValuation = repriceFunction.reprice(shock.marketData);
                if (shockedValuation == null) {
                    continue;
                }
                if (isNoChangeByCny(baseSnapshot, shockedValuation, zeroTolerance)) {
                    continue;
                }
                FrtbDependency dependency = findByCurve(deltaDependencies, shock.riskFactorId);
                FrtbSenes sensitivity = new FrtbSenes();
                sensitivity.instrumentId = instrumentId;
                sensitivity.riskFactorId = dependency != null && hasText(dependency.riskFactorId)
                        ? dependency.riskFactorId
                        : shock.riskFactorId;
                sensitivity.riskFactorVertex1 = shock.riskFactorVertex1;
                sensitivity.riskFactorClass = shock.riskFactorClass;
                sensitivity.riskFactorBucket = dependency != null && hasText(dependency.bucket)
                        ? normalizeGirrBucket(dependency.bucket)
                        : normalizeGirrBucket(shock.riskFactorBucket);
                sensitivity.riskFactorType = shock.riskFactorType;
                sensitivity.sensitivityType = shock.sensitivityType;
                sensitivity.instrumentCurrency = instrumentCurrency;
                sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseSnapshot.valuation) * GIRR_DELTA_SCALE;
                sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseSnapshot.valuationCny) * GIRR_DELTA_SCALE;
                sensitivity.detail = buildSensitivityDetail(baseSnapshot, shockedValuation, 0.0001,
                        GIRR_DELTA_SCALE, 0.0, null, null);
                sensitivities.add(sensitivity);
                deltaSensitivities.add(sensitivity);
            }
        }

        if (enableCurvature && !curveBucketMap.isEmpty()) {
            Map<String, Double> deltaByBucket = sumByBucket(deltaSensitivities, false);
            Map<String, Double> deltaByBucketCny = sumByBucket(deltaSensitivities, true);
            HashMap<String, List<String>> bucketCurveMap = toBucketCurveMap(deltaDependencies);
            List<FrtbMarketData> curvatureMarkets = MarketData.getFrtbMarketDataListGIRRCurvature(marketData, bucketCurveMap);
            for (FrtbMarketData shock : curvatureMarkets) {
                if (shock == null || shock.marketData == null) {
                    continue;
                }
                MeasureValuation shockedValuation = curvatureRepriceFunction == null
                        ? repriceFunction.reprice(shock.marketData)
                        : curvatureRepriceFunction.reprice(shock.marketData);
                if (shockedValuation == null) {
                    continue;
                }
                if (isNoChangeByCny(baseSnapshot, shockedValuation, zeroTolerance)) {
                    continue;
                }
                FrtbSenes sensitivity = new FrtbSenes();
                sensitivity.instrumentId = instrumentId;
                sensitivity.riskFactorId = shock.riskFactorId;
                sensitivity.riskFactorVertex1 = shock.riskFactorVertex1;
                sensitivity.riskFactorClass = shock.riskFactorClass;
                sensitivity.riskFactorBucket = shock.riskFactorBucket;
                sensitivity.riskFactorBucket = normalizeGirrBucket(sensitivity.riskFactorBucket);
                sensitivity.riskFactorType = shock.riskFactorType;
                sensitivity.sensitivityType = shock.sensitivityType;
                sensitivity.instrumentCurrency = instrumentCurrency;
                sensitivity.sensitivityValInstCurr = (shockedValuation.valuation - baseSnapshot.valuation)
                        + deltaByBucket.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                sensitivity.sensitivityValInstCurrCny = (shockedValuation.valuationCny - baseSnapshot.valuationCny)
                        + deltaByBucketCny.getOrDefault(sensitivity.riskFactorBucket, 0.0) * shock.riskWeight;
                sensitivity.detail = buildSensitivityDetail(baseSnapshot, shockedValuation, shock.riskWeight,
                        1.0, deltaByBucket.getOrDefault(sensitivity.riskFactorBucket, 0.0),
                        deltaByBucketCny.getOrDefault(sensitivity.riskFactorBucket, 0.0), shock.riskWeight);
                sensitivities.add(sensitivity);
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
                    Constants.FRTB.SA.RISK_CLASS.GIRR,
                    dependency.curveOrRiskFactor);
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
                if (isNoChangeByCny(baseSnapshot, shockedValuation, zeroTolerance)) {
                    continue;
                }
                double vegaValue = normalizeVega(shockedValuation.valuation, baseSnapshot.valuation);
                double vegaValueCny = normalizeVega(shockedValuation.valuationCny, baseSnapshot.valuationCny);
                Map<String, Double> vertex2Weights = splitToStandardVegaTenorsWithTolerance(
                        dataDate,
                        dependency.secondaryVertex,
                        FrtbParamsCache.getVegaMatchToleranceDays());
                if (vertex2Weights.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Double> vertex2Weight : vertex2Weights.entrySet()) {
                    FrtbSenes sensitivity = new FrtbSenes();
                    sensitivity.instrumentId = instrumentId;
                    sensitivity.riskFactorId = hasText(dependency.riskFactorId) ? dependency.riskFactorId : vegaShock.riskFactorId;
                    sensitivity.riskFactorVertex1 = vegaShock.riskFactorVertex1;
                    sensitivity.riskFactorVertex2 = vertex2Weight.getKey();
                    sensitivity.riskFactorClass = vegaShock.riskFactorClass;
                    sensitivity.riskFactorBucket = hasText(dependency.bucket) ? dependency.bucket : vegaShock.riskFactorBucket;
                    sensitivity.riskFactorBucket = normalizeGirrBucket(sensitivity.riskFactorBucket);
                    sensitivity.riskFactorType = "";
                    sensitivity.sensitivityType = vegaShock.sensitivityType;
                    sensitivity.instrumentCurrency = instrumentCurrency;
                    sensitivity.sensitivityValInstCurr = vegaValue * vertex2Weight.getValue();
                    sensitivity.sensitivityValInstCurrCny = vegaValueCny * vertex2Weight.getValue();
                    sensitivities.add(sensitivity);
                }
            }
        }
        return sensitivities;
    }

    private static FrtbDependency buildBasisDependency(
            String targetCurrency,
            String targetCurve,
            String otherCurrency,
            String otherCurve,
            String baseCurrency) {
        FrtbDependency dependency = FrtbDependency.of(
                FrtbDependency.TYPE_GIRR_DELTA_BASIS,
                targetCurve,
                "CCS_BASIS_" + targetCurrency + "_OVER_" + baseCurrency,
                normalizeGirrBucket(targetCurrency));
        dependency.currency1 = targetCurrency;
        dependency.curve1 = targetCurve;
        dependency.currency2 = otherCurrency;
        dependency.curve2 = otherCurve;
        return dependency;
    }

    private static String normalizeCurrency(String currency) {
        return trimToNull(currency) == null ? null : currency.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeGirrBucket(String bucket) {
        if (!hasText(bucket)) {
            return bucket;
        }
        return "CNH".equalsIgnoreCase(bucket.trim()) ? "CNY" : bucket.trim();
    }

    /**
     * Basis 直接对目标利率曲线做整条平移，不参与期限点拆分。
     */
    private static MarketData buildShiftedIrCurveMarketData(MarketData baseMarketData, String curveId, double shift) {
        if (baseMarketData == null || !hasText(curveId) || baseMarketData.irSpot == null) {
            return null;
        }
        IrSpot.IrSpotInfo baseCurve = baseMarketData.irSpot.get(curveId);
        if (baseCurve == null) {
            return null;
        }
        MarketData shockedMarketData = new MarketData();
        shockedMarketData.irSpot = new HashMap<>(baseMarketData.irSpot);
        shockedMarketData.irVol = new HashMap<>(baseMarketData.irVol);
        shockedMarketData.eqSpot = new HashMap<>(baseMarketData.eqSpot);
        shockedMarketData.eqVol = new HashMap<>(baseMarketData.eqVol);
        shockedMarketData.fxSpot = baseMarketData.fxSpot;
        shockedMarketData.fxVol = new HashMap<>(baseMarketData.fxVol);
        shockedMarketData.commSpot = new HashMap<>(baseMarketData.commSpot);
        shockedMarketData.commVol = new HashMap<>(baseMarketData.commVol);
        shockedMarketData.fixingRate = new HashMap<>(baseMarketData.fixingRate);

        IrSpot.IrSpotInfo shiftedCurve = CommUtils.deepCopy(baseCurve);
        shiftedCurve.shift(shift);
        shockedMarketData.irSpot.put(curveId, shiftedCurve);
        return shockedMarketData;
    }

    private static HashMap<String, String> toCurveBucketMap(List<FrtbDependency> dependencies) {
        HashMap<String, String> map = new LinkedHashMap<>();
        if (dependencies == null) {
            return map;
        }
        for (FrtbDependency dependency : dependencies) {
            if (dependency == null) {
                continue;
            }
            if (!FrtbDependency.TYPE_GIRR_DELTA.equalsIgnoreCase(dependency.type)) {
                continue;
            }
            if (!hasText(dependency.curveOrRiskFactor) || !hasText(dependency.bucket)) {
                continue;
            }
            map.put(dependency.curveOrRiskFactor, dependency.bucket);
        }
        return map;
    }

    private static HashMap<String, List<String>> toBucketCurveMap(List<FrtbDependency> dependencies) {
        HashMap<String, List<String>> map = new LinkedHashMap<>();
        if (dependencies == null) {
            return map;
        }
        for (FrtbDependency dependency : dependencies) {
            if (dependency == null) {
                continue;
            }
            if (!FrtbDependency.TYPE_GIRR_DELTA.equalsIgnoreCase(dependency.type)) {
                continue;
            }
            if (!hasText(dependency.curveOrRiskFactor) || !hasText(dependency.bucket)) {
                continue;
            }
            List<String> curveList = map.computeIfAbsent(dependency.bucket, k -> new ArrayList<>());
            if (!curveList.contains(dependency.curveOrRiskFactor)) {
                curveList.add(dependency.curveOrRiskFactor);
            }
        }
        return map;
    }

    private static FrtbDependency findByCurve(List<FrtbDependency> dependencies, String curveName) {
        if (dependencies == null || !hasText(curveName)) {
            return null;
        }
        for (FrtbDependency dependency : dependencies) {
            if (dependency == null) {
                continue;
            }
            if (curveName.equalsIgnoreCase(dependency.curveOrRiskFactor)) {
                return dependency;
            }
        }
        return null;
    }

    private static Map<String, Object> buildSensitivityDetail(
            MeasureValuation baseSnapshot,
            MeasureValuation shockedValuation,
            double shockSize,
            double sensitivityScale,
            double deltaBucket,
            Double deltaBucketCny,
            Double curvatureRiskWeight) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("BASE_VALUATION", baseSnapshot.valuation);
        detail.put("SHOCKED_VALUATION", shockedValuation.valuation);
        detail.put("DELTA_VALUATION", shockedValuation.valuation - baseSnapshot.valuation);
        detail.put("BASE_VALUATION_CNY", baseSnapshot.valuationCny);
        detail.put("SHOCKED_VALUATION_CNY", shockedValuation.valuationCny);
        detail.put("DELTA_VALUATION_CNY", shockedValuation.valuationCny - baseSnapshot.valuationCny);
        detail.put("SHOCK_SIZE", shockSize);
        detail.put("SENSITIVITY_SCALE", sensitivityScale);
        if (curvatureRiskWeight != null) {
            detail.put("DELTA_BUCKET", deltaBucket);
            detail.put("DELTA_BUCKET_CNY", deltaBucketCny);
            detail.put("CURVATURE_RISK_WEIGHT", curvatureRiskWeight);
        }
        return detail;
    }

    @FunctionalInterface
    public interface RepriceFunction {
        MeasureValuation reprice(MarketData shockedMarketData);
    }
}
