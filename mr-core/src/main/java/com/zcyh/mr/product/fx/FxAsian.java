package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.option.AsianBase;
import com.zcyh.mr.product.basic.option.EurOptUtil;

import java.time.LocalDate;
import java.util.*;
import java.util.Locale;

/**
 * FX 亚式期权。
 * 说明：
 * 1. 产品输入与市场参数装配由本类负责；
 * 2. 亚式通用流程由 AsianBase 统一处理。
 */
public class FxAsian extends AsianBase<FxAsian.FxAsianTradeInfo, FxAsian.FxAsianMeasure> {

    public FxAsian(LocalDate dataDate, FxAsianTradeInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    protected FxAsianMeasure newMeasure() {
        return new FxAsianMeasure();
    }

    @Override
    protected String resolveProductCode() {
        if (hasText(info.productCode)) {
            return info.productCode.trim();
        }
        return EngineConstants.PRODUCT_CODE.FX_ASIAN;
    }

    @Override
    protected String resolveValuationCurrency(MarketData md) {
        return info.baseCurrencyCode;
    }

    @Override
    protected double resolveValuationToCnyFx(MarketData md, String valuationCurrency) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(valuationCurrency);
    }

    @Override
    protected MarketFactors resolveMarketFactors(
            MarketData md,
            boolean call,
            boolean cash,
            int maturityDays,
            int settleDays,
            double maturityT,
            double settleT) {
        IrSpot underIr = new IrSpot(md.irSpot.get(info.underlyingDiscountCurve));
        IrSpot baseIr = new IrSpot(md.irSpot.get(info.baseDiscountCurve));

        double rd;
        double rf;
        if (cash) {
            rd = baseIr.spotRate(info.maturityDate);
            rf = underIr.spotRate(info.maturityDate);
        } else {
            rd = baseIr.spotRate(info.settleDate);
            rf = underIr.spotRate(info.settleDate);
        }

        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        double spot = fxSpot.getFxrate(info.baseCurrencyCode, info.underlyingCurrencyCode);
        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));

        List<Map<String, Object>> volCurve = fxVol.getVolCur(maturityDays);
        EurOptUtil util = new EurOptUtil(
                call,
                cash,
                spot,
                info.strikePrice,
                rd,
                rf,
                maturityT,
                settleT,
                volCurve,
                "black");
        double sigma = util.getSigma();
        return new MarketFactors(spot, rd, rf, sigma);
    }

    @Override
    protected void validateMarketData(List<String> errors, MarketData md) {
        if (!hasText(info.baseCurrencyCode)) {
            errors.add("BASE_CURRENCY_CODE 未设置");
        }
        if (!hasText(info.underlyingCurrencyCode)) {
            errors.add("UNDERLYING_CURRENCY_CODE 未设置");
        }
        if (!hasText(info.baseDiscountCurve)) {
            errors.add("BASE_DISCOUNT_CURVE 未设置");
        }
        if (!hasText(info.underlyingDiscountCurve)) {
            errors.add("UNDERLYING_DISCOUNT_CURVE 未设置");
        }
        if (!hasText(info.volatilitySurface)) {
            errors.add("VOLATILITY_SURFACE 未设置");
        }
        if (md == null) {
            errors.add("市场数据为空");
            return;
        }
        if (md.irSpot == null || !md.irSpot.containsKey(info.baseDiscountCurve)) {
            errors.add("市场数据缺少利率曲线: " + info.baseDiscountCurve);
        }
        if (md.irSpot == null || !md.irSpot.containsKey(info.underlyingDiscountCurve)) {
            errors.add("市场数据缺少利率曲线: " + info.underlyingDiscountCurve);
        }
        if (md.fxVol == null || !md.fxVol.containsKey(info.volatilitySurface)) {
            errors.add("市场数据缺少波动率曲面: " + info.volatilitySurface);
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            errors.add("市场数据缺少即期汇率: FX_SPOT");
        }
    }

    @Override
    protected void afterSuccessfulCalc(FxAsianMeasure measure) {
        calcFrtbSens();
    }

    @Override
    protected String resolveCalcErrorPrefix() {
        return "FX_ASIAN计算失败";
    }

    private void calcFrtbSens() {
        List<FrtbSenes> list = new ArrayList<>();
        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(info.underlyingDiscountCurve, info.underlyingCurrencyCode);
        curveMap.put(info.baseDiscountCurve, info.baseCurrencyCode);

        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                collectFxDeltaDependencies(),
                collectFxVegaDependencies(),
                true,
                true,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    FxAsianMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null);
        list.addAll(fxSensitivities);

        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                Collections.emptyList(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    FxAsianMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);
        measure.sensitivityList = list;
    }

    private List<FrtbDependency> collectFxDeltaDependencies() {
        List<String> riskCurrencies = new ArrayList<>();
        if (hasText(info.underlyingCurrencyCode)) {
            riskCurrencies.add(info.underlyingCurrencyCode);
        }
        if (hasText(info.baseCurrencyCode)) {
            riskCurrencies.add(info.baseCurrencyCode);
        }
        return FrtbSensitivityBuilder.buildFxDeltaDependencies(
                riskCurrencies,
                FrtbSensitivityBuilder.buildFxPair(info.underlyingCurrencyCode, info.baseCurrencyCode));
    }

    private List<FrtbDependency> collectFxVegaDependencies() {
        String undCcy = normalizeCcy(info.underlyingCurrencyCode);
        String baseCcy = normalizeCcy(info.baseCurrencyCode);
        String riskFactorId = "FX_" + undCcy + "_" + baseCcy + "_VOL";
        String bucket = undCcy + "/" + baseCcy;
        return FrtbSensitivityBuilder.buildFxVegaDependencies(info.volatilitySurface, riskFactorId, bucket);
    }

    private String normalizeCcy(String ccy) {
        if (ccy == null) {
            return "";
        }
        return ccy.trim().toUpperCase(Locale.ROOT);
    }

    public static class FxAsianMeasure extends OptionMeasure {
    }

    public static class FxAsianTradeInfo extends AsianBase.AsianBaseTradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
    }
}
