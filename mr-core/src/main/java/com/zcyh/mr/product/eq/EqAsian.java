package com.zcyh.mr.product.eq;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
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

/**
 * EQ 亚式期权。
 */
public class EqAsian extends AsianBase<EqAsian.EqAsianInfo, EqAsian.EqAsianMeasure> {

    public EqAsian(LocalDate dataDate, EqAsianInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    protected EqAsianMeasure newMeasure() {
        return new EqAsianMeasure();
    }

    @Override
    protected String resolveProductCode() {
        if (hasText(info.productCode)) {
            return info.productCode.trim();
        }
        return EngineConstants.PRODUCT_CODE.EQ_ASIAN;
    }

    @Override
    protected String resolveValuationCurrency(MarketData md) {
        return resolveCurrencyCode();
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
        IrSpot discountIr = new IrSpot(md.irSpot.get(info.discountCurve));
        EqSpot eqSpot = new EqSpot(md.eqSpot.get(info.referenceCurve));
        EqVol eqVol = new EqVol(md.eqVol.get(info.volatilitySurface));

        double spot = eqSpot.fwdPrice(dataDate);
        double rd = cash ? discountIr.spotRate(info.maturityDate) : discountIr.spotRate(info.settleDate);
        double rf = 0.0;

        List<Map<String, Object>> volCurve = eqVol.getVolCur(maturityDays);
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
        String valuationCurrency = resolveCurrencyCode();
        if (!hasText(valuationCurrency)) {
            errors.add("CURRENCY_CODE 未设置");
        }
        if (!hasText(info.discountCurve)) {
            errors.add("DISCOUNT_CURVE 未设置");
        }
        if (!hasText(info.referenceCurve)) {
            errors.add("REFERENCE_CURVE 未设置");
        }
        if (!hasText(info.volatilitySurface)) {
            errors.add("VOLATILITY_SURFACE 未设置");
        }
        if (md == null) {
            errors.add("市场数据为空");
            return;
        }
        if (md.irSpot == null || !md.irSpot.containsKey(info.discountCurve)) {
            errors.add("市场数据缺少利率曲线: " + info.discountCurve);
        }
        if (md.eqSpot == null || !md.eqSpot.containsKey(info.referenceCurve)) {
            errors.add("市场数据缺少权益曲线: " + info.referenceCurve);
        }
        if (md.eqVol == null || !md.eqVol.containsKey(info.volatilitySurface)) {
            errors.add("市场数据缺少权益波动率曲面: " + info.volatilitySurface);
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            errors.add("市场数据缺少即期汇率: FX_SPOT");
        }
    }

    @Override
    protected void afterSuccessfulCalc(EqAsianMeasure measure) {
        calcFrtbSens();
    }

    @Override
    protected String resolveCalcErrorPrefix() {
        return "EQ_ASIAN计算失败";
    }

    private void calcFrtbSens() {
        List<FrtbSenes> list = new ArrayList<>();
        String valuationCurrency = resolveCurrencyCode();
        String frtbCurrency = valuationCurrency;
        com.zcyh.mr.product.basic.frtb.MeasureValuation baseValuation = com.zcyh.mr.product.basic.frtb.MeasureValuation.of(
                measure.valuation, measure.valuationCny);

        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                FrtbSensitivityBuilder.buildFxDeltaDependencies(collectFxRiskCurrencies(valuationCurrency)),
                Collections.emptyList(),
                true,
                false,
                info.instrumentId,
                frtbCurrency,
                1e-12,
                baseValuation,
                shockedMarketData -> {
                    EqAsianMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null);
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(info.discountCurve, valuationCurrency);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                Collections.emptyList(),
                true,
                false,
                info.instrumentId,
                frtbCurrency,
                1e-12,
                baseValuation,
                shockedMarketData -> {
                    EqAsianMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        String eqBucket = info.frtbEqBucket;
        if (FrtbSensitivityBuilder.warnMissingEqSensitivityInputs(measure, eqBucket)) {
            measure.sensitivityList = list;
            return;
        }
        List<FrtbDependency> eqDeltaDependencies = FrtbSensitivityBuilder.buildEqDeltaDependencies(
                info.referenceCurve,
                eqBucket);
        List<FrtbDependency> eqVegaDependencies = FrtbSensitivityBuilder.buildEqVegaDependencies(
                info.volatilitySurface,
                info.referenceCurve,
                eqBucket);
        List<FrtbSenes> eqSensitivities = FrtbSensitivityBuilder.buildEqSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                eqDeltaDependencies,
                eqVegaDependencies,
                true,
                true,
                info.instrumentId,
                frtbCurrency,
                1e-12,
                baseValuation,
                shockedMarketData -> {
                    EqAsianMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null);
        list.addAll(eqSensitivities);

        measure.sensitivityList = list;
    }

    private List<String> collectFxRiskCurrencies(String... currencies) {
        LinkedHashSet<String> fxRiskCurrencies = new LinkedHashSet<>();
        if (currencies == null) {
            return new ArrayList<>();
        }
        for (String currency : currencies) {
            if (!hasText(currency)) {
                continue;
            }
            fxRiskCurrencies.add(currency.trim());
        }
        return new ArrayList<>(fxRiskCurrencies);
    }

    private String resolveCurrencyCode() {
        if (hasText(info.currencyCode)) {
            return info.currencyCode.trim();
        }
        return null;
    }

    public static class EqAsianMeasure extends OptionMeasure {
    }

    public static class EqAsianInfo extends AsianBase.AsianBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField
        @JSONField(name = "FRTB_EQ_BUCKET")
        public String frtbEqBucket;
    }
}
