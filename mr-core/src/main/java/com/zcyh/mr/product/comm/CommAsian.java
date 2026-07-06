package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
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
 * COMM 亚式期权。
 */
public class CommAsian extends AsianBase<CommAsian.CommAsianInfo, CommAsian.CommAsianMeasure> {

    public CommAsian(LocalDate dataDate, CommAsianInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    protected CommAsianMeasure newMeasure() {
        return new CommAsianMeasure();
    }

    @Override
    protected String resolveProductCode() {
        if (hasText(info.productCode)) {
            return info.productCode.trim();
        }
        return Constants.PRODUCT_CODE.COMM_ASIAN;
    }

    @Override
    protected String resolveValuationCurrency(MarketData md) {
        return resolveCurrencyCode();
    }

    @Override
    protected double resolveValuationToCnyFx(MarketData md, String valuationCurrency) {
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
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
        CommSpot commSpot = new CommSpot(md.commSpot.get(info.referenceCurve));
        CommVol commVol = new CommVol(md.commVol.get(info.volatilitySurface));

        double spot = commSpot.fwdPrice(dataDate);
        double rd = cash ? discountIr.spotRate(info.maturityDate) : discountIr.spotRate(info.settleDate);
        double maturityForward = commSpot.fwdPrice(info.maturityDate);
        double rf = rd;
        if (Double.isFinite(spot) && spot > 0.0
                && Double.isFinite(maturityForward) && maturityForward > 0.0
                && Double.isFinite(maturityT) && maturityT > 0.0) {
            rf = -Math.log(maturityForward / spot) / maturityT + rd;
        }

        List<Map<String, Object>> volCurve = commVol.getVolCur(maturityDays);
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
        if (md.commSpot == null || !md.commSpot.containsKey(info.referenceCurve)) {
            errors.add("市场数据缺少商品曲线: " + info.referenceCurve);
        }
        if (md.commVol == null || !md.commVol.containsKey(info.volatilitySurface)) {
            errors.add("市场数据缺少商品波动率曲面: " + info.volatilitySurface);
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            errors.add("市场数据缺少即期汇率: FX_SPOT");
        }
    }

    @Override
    protected void afterSuccessfulCalc(CommAsianMeasure measure) {
        calcFrtbSens();
    }

    @Override
    protected String resolveCalcErrorPrefix() {
        return "COMM_ASIAN计算失败";
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
                    CommAsianMeasure shockedMeasure = calc(shockedMarketData);
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
                    CommAsianMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        String commBucket = getText(info.frtbCommBucket);
        String commAsset = resolveCmtyRiskFactorIdBase();
        if (!FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                measure,
                getText(info.instrumentId),
                commBucket,
                commAsset)) {
            List<FrtbDependency> cmtyDeltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
                    info.referenceCurve,
                    resolveCmtyRiskFactorId(),
                    commBucket);
            List<FrtbDependency> cmtyVegaDependencies = FrtbSensitivityBuilder.buildCmtyVegaDependencies(
                    info.volatilitySurface,
                    resolveCmtyRiskFactorIdVega(),
                    commBucket);
            List<FrtbSenes> cmtySensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                    marketData,
                    dataDate,
                    info.settleDate,
                    cmtyDeltaDependencies,
                    cmtyVegaDependencies,
                    true,
                    true,
                    info.instrumentId,
                    frtbCurrency,
                    1e-12,
                    baseValuation,
                    shockedMarketData -> {
                        CommAsianMeasure shockedMeasure = calc(shockedMarketData);
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                    },
                    null);
            list.addAll(cmtySensitivities);
        }

        measure.sensitivityList = list;
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
        if (!hasText(base)) {
            return null;
        }
        String location = getText(info.frtbCommLocation);
        if (location.isEmpty()) {
            return base;
        }
        return base + "&" + location;
    }

    private String resolveCmtyRiskFactorIdBase() {
        String asset = getText(info.frtbCommAsset);
        if (!asset.isEmpty()) {
            return asset;
        }
        return null;
    }

    private String resolveCmtyRiskFactorIdVega() {
        return resolveCmtyRiskFactorIdBase();
    }

    private String getText(String value) {
        return value == null ? "" : value.trim();
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

    public static class CommAsianMeasure extends OptionMeasure {
    }

    public static class CommAsianInfo extends AsianBase.AsianBaseInfo {
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
    }
}
