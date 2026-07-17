package com.zcyh.mr.product.basic.frtb;

import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.calc.FrtbCalcControl;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.frtb.builder.CmtySensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.builder.CsrSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.builder.EqSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.builder.FxSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.builder.GirrSensitivityBuilder;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FRTB 敏感性公共门面。
 * 对外保持统一静态入口，对内按风险类别分发到具体 builder。
 */
public class FrtbSensitivityBuilder {

    private FrtbSensitivityBuilder() {
    }

    @FunctionalInterface
    public interface RepriceFunction {
        com.zcyh.mr.product.basic.frtb.MeasureValuation reprice(MarketData shockedMarketData);
    }

    /**
     * 根据产品给出的曲线-币种映射生成 GIRR Delta 依赖。
     */
    public static List<FrtbDependency> buildGirrDeltaDependencies(Map<String, String> curveBucketMap) {
        return GirrSensitivityBuilder.buildDeltaDependencies(curveBucketMap);
    }

    /**
     * 根据两条 CCS 腿的币种与曲线构建 GIRR Basis Delta 依赖。
     */
    public static List<FrtbDependency> buildGirrDeltaBasisDependencies(
            String currency1,
            String curve1,
            String currency2,
            String curve2) {
        return GirrSensitivityBuilder.buildDeltaBasisDependencies(currency1, curve1, currency2, curve2);
    }

    /**
     * 构建单曲面 GIRR Vega 依赖。
     */
    public static List<FrtbDependency> buildGirrVegaDependencies(String volatilitySurface, String bucket) {
        return buildGirrVegaDependencies(volatilitySurface, bucket, null);
    }

    /**
     * 构建单曲面 GIRR Vega 依赖，并携带原始第二维输入。
     */
    public static List<FrtbDependency> buildGirrVegaDependencies(String volatilitySurface, String bucket, String secondaryVertex) {
        return GirrSensitivityBuilder.buildVegaDependencies(volatilitySurface, bucket, secondaryVertex);
    }

    /**
     * 根据风险币种构建 FX Delta 依赖。
     */
    public static List<FrtbDependency> buildFxDeltaDependencies(List<String> riskCurrencies) {
        return FxSensitivityBuilder.buildDeltaDependencies(riskCurrencies, null);
    }

    /**
     * 根据风险币种与货币对构建 FX Delta 依赖。
     */
    public static List<FrtbDependency> buildFxDeltaDependencies(List<String> riskCurrencies, String fxPair) {
        return FxSensitivityBuilder.buildDeltaDependencies(riskCurrencies, fxPair);
    }

    /**
     * 统一拼装 FX 风险因子货币对。
     */
    public static String buildFxPair(String underlyingCurrency, String baseCurrency) {
        if (underlyingCurrency == null || baseCurrency == null) {
            return null;
        }
        String undCcy = underlyingCurrency.trim().toUpperCase();
        String baseCcy = baseCurrency.trim().toUpperCase();
        if (undCcy.isEmpty() || baseCcy.isEmpty()) {
            return null;
        }
        return undCcy + "/" + baseCcy;
    }

    /**
     * 构建单曲面 FX Vega 依赖。
     */
    public static List<FrtbDependency> buildFxVegaDependencies(String volatilitySurface, String riskFactorId, String bucket) {
        return FxSensitivityBuilder.buildVegaDependencies(volatilitySurface, riskFactorId, bucket);
    }

    /**
     * 根据权益价格曲线构建 EQ Delta 依赖。
     */
    public static List<FrtbDependency> buildEqDeltaDependencies(String priceCurve, String bucket) {
        return EqSensitivityBuilder.buildDeltaDependencies(priceCurve, bucket);
    }

    /**
     * 构建单曲面 EQ Vega 依赖。
     */
    public static List<FrtbDependency> buildEqVegaDependencies(String volatilitySurface, String riskFactorId, String bucket) {
        return EqSensitivityBuilder.buildVegaDependencies(volatilitySurface, riskFactorId, bucket);
    }

    public static boolean warnMissingEqSensitivityInputs(Measure measure, String eqBucket) {
        return EqSensitivityBuilder.warnMissingSensitivityInputs(measure, eqBucket);
    }

    /**
     * 构建商品 Delta 依赖。
     */
    public static List<FrtbDependency> buildCmtyDeltaDependencies(String riskFactorId, String bucket) {
        return CmtySensitivityBuilder.buildDeltaDependencies(null, riskFactorId, bucket);
    }

    /**
     * 构建单曲线商品 Delta 依赖。
     */
    public static List<FrtbDependency> buildCmtyDeltaDependencies(String priceCurve, String riskFactorId, String bucket) {
        return CmtySensitivityBuilder.buildDeltaDependencies(priceCurve, riskFactorId, bucket);
    }

    /**
     * 构建单曲面商品 Vega 依赖。
     */
    public static List<FrtbDependency> buildCmtyVegaDependencies(String volatilitySurface, String riskFactorId, String bucket) {
        return CmtySensitivityBuilder.buildVegaDependencies(volatilitySurface, riskFactorId, bucket);
    }

    /**
     * 检查商品敏感性所需输入，缺失时写入 warning 并由调用方跳过 CMTY 敏感性。
     */
    public static boolean warnMissingCmtySensitivityInputs(
            Measure measure,
            String instrumentId,
            String cmtyBucket,
            String... cmtyRiskFactorIds) {
        return CmtySensitivityBuilder.warnMissingSensitivityInputs(
                measure,
                instrumentId,
                cmtyBucket,
                cmtyRiskFactorIds);
    }

    /**
     * 构建 CSR(non-sec) Delta 依赖。
     */
    public static List<FrtbDependency> buildCsrNonSecDeltaDependencies(
            String curve,
            String riskFactorId,
            String bucket,
            String outputType) {
        return CsrSensitivityBuilder.buildNonSecDeltaDependencies(curve, riskFactorId, bucket, outputType);
    }

    /**
     * 构建 CSR(non-ctp) Delta 依赖。
     */
    public static List<FrtbDependency> buildCsrSecNonCtpDeltaDependencies(
            String curve,
            String riskFactorId,
            String bucket,
            String outputType) {
        return CsrSensitivityBuilder.buildSecNonCtpDeltaDependencies(curve, riskFactorId, bucket, outputType);
    }

    /**
     * 统一构建 GIRR Delta / Curvature / Vega。
     */
    public static List<FrtbSenes> buildGirrSensitivities(
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
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseSnapshot,
            RepriceFunction repriceFunction,
            RepriceFunction curvatureRepriceFunction,
            Runnable beforeVegaReprice) {
        if (!FrtbCalcControl.isSensitivityEnabled()) {
            return Collections.emptyList();
        }
        return GirrSensitivityBuilder.buildSensitivities(
                marketData,
                dataDate,
                settleDate,
                deltaDependencies,
                vegaDependencies,
                enableDelta,
                enableCurvature,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseSnapshot,
                repriceFunction::reprice,
                curvatureRepriceFunction == null ? null : curvatureRepriceFunction::reprice,
                beforeVegaReprice);
    }

    /**
     * 统一构建 GIRR Basis Delta。
     */
    public static List<FrtbSenes> buildGirrDeltaBasisSensitivities(
            MarketData marketData,
            LocalDate dataDate,
            List<FrtbDependency> basisDependencies,
            String instrumentId,
            String instrumentCurrency,
            double zeroTolerance,
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseSnapshot,
            RepriceFunction repriceFunction) {
        if (!FrtbCalcControl.isSensitivityEnabled()) {
            return Collections.emptyList();
        }
        return GirrSensitivityBuilder.buildDeltaBasisSensitivities(
                marketData,
                dataDate,
                basisDependencies,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseSnapshot,
                repriceFunction::reprice);
    }

    /**
     * 统一构建 FX Delta / Curvature / Vega。
     */
    public static List<FrtbSenes> buildFxSensitivities(
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
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseValuation,
            RepriceFunction repriceFunction) {
        return buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                deltaDependencies,
                vegaDependencies,
                enableDelta,
                enableCurvature,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseValuation,
                repriceFunction,
                null);
    }

    public static List<FrtbSenes> buildFxSensitivities(
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
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseValuation,
            RepriceFunction repriceFunction,
            Runnable beforeVegaReprice) {
        if (!FrtbCalcControl.isSensitivityEnabled()) {
            return Collections.emptyList();
        }
        return FxSensitivityBuilder.buildSensitivities(
                marketData,
                dataDate,
                settleDate,
                deltaDependencies,
                vegaDependencies,
                enableDelta,
                enableCurvature,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseValuation,
                repriceFunction::reprice,
                beforeVegaReprice);
    }

    /**
     * 统一构建 EQ Delta / Curvature / Vega。
     */
    public static List<FrtbSenes> buildEqSensitivities(
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
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseValuation,
            RepriceFunction repriceFunction,
            Runnable beforeVegaReprice) {
        if (!FrtbCalcControl.isSensitivityEnabled()) {
            return Collections.emptyList();
        }
        return EqSensitivityBuilder.buildSensitivities(
                marketData,
                dataDate,
                settleDate,
                deltaDependencies,
                vegaDependencies,
                enableDelta,
                enableCurvature,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseValuation,
                repriceFunction::reprice,
                beforeVegaReprice);
    }

    /**
     * 统一构建商品 Delta / Curvature / Vega。
     */
    public static List<FrtbSenes> buildCmtySensitivities(
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
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseValuation,
            RepriceFunction repriceFunction,
            Runnable beforeVegaReprice) {
        if (!FrtbCalcControl.isSensitivityEnabled()) {
            return Collections.emptyList();
        }
        return CmtySensitivityBuilder.buildSensitivities(
                marketData,
                dataDate,
                settleDate,
                deltaDependencies,
                vegaDependencies,
                enableDelta,
                enableCurvature,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseValuation,
                repriceFunction::reprice,
                beforeVegaReprice);
    }

    /**
     * 统一构建 CSR(non-sec / non-ctp) Delta / Curvature。
     */
    public static List<FrtbSenes> buildCsrSensitivities(
            MarketData marketData,
            LocalDate dataDate,
            List<FrtbDependency> deltaDependencies,
            boolean enableDelta,
            boolean enableCurvature,
            String instrumentId,
            String instrumentCurrency,
            double zeroTolerance,
            com.zcyh.mr.product.basic.frtb.MeasureValuation baseValuation,
            RepriceFunction repriceFunction) {
        if (!FrtbCalcControl.isSensitivityEnabled()) {
            return Collections.emptyList();
        }
        return CsrSensitivityBuilder.buildSensitivities(
                marketData,
                dataDate,
                deltaDependencies,
                enableDelta,
                enableCurvature,
                instrumentId,
                instrumentCurrency,
                zeroTolerance,
                baseValuation,
                repriceFunction::reprice);
    }

}
