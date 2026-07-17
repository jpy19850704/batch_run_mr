package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.Preconditions;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.Measure;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品远期估值类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class CommFwd {
    private LocalDate dataDate;
    private CommFwdInfo commFwdInfo;
    private MarketData marketData;
    private Measure commFwdMeasure = new Measure();

    public CommFwd(LocalDate dataDate, CommFwdInfo tradeInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.commFwdInfo = tradeInfo;
        this.marketData = marketData;
    }

    /**
     * 商品远期计量
     *
     * @param :
     * @return Measure
     * @author lsd
     * @date 2024/7/10 14:52
     */
    public Measure calc() {
        Preconditions.require(dataDate != null, "dataDate must be set");
        validateInputs(marketData);
        LocalDate settleDate = commFwdInfo.settleDate;
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);

        // 获取市场数据
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(commFwdInfo.discountCurve));
        CommSpot commSpot = new CommSpot(marketData.commSpot.get(commFwdInfo.referenceCurve));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        double fxRate = fxSpot.getFxrate(valuationCurrency);

        // 估值计量
        double disc = irSpot.discount(settleDate);
        double fwdPrice = commSpot.fwdPrice(settleDate);
        double disc1 = irSpot.discount(settleDate, 0.0001);
        double strikePrice = convertStrikePriceToValuationCurrency(
                commFwdInfo.strikePrice, valuationCurrency, strikeCurrency, fxSpot);
        double position = commFwdInfo.contractSize * ("B".equalsIgnoreCase(commFwdInfo.buyOrSell) ? 1 : -1);

        commFwdMeasure.position = position;
        commFwdMeasure.valuation = (fwdPrice - strikePrice) * disc * position;
        commFwdMeasure.valuationUnit = (position == 0 ? 0 : commFwdMeasure.valuation / position);
        commFwdMeasure.pv01 = (fwdPrice - strikePrice) * (disc1 - disc) * position;
        commFwdMeasure.valuationCny = commFwdMeasure.valuation * fxRate;
        commFwdMeasure.valuationCcy = valuationCurrency;
        commFwdMeasure.instrumentId = commFwdInfo.instrumentId;
        commFwdMeasure.dataDate = dataDate;
        commFwdMeasure.productCode = commFwdInfo.productCode;
        commFwdMeasure.status = "SUCCESS";
        commFwdMeasure.logs = new ArrayList<>();
        Map<String, Object> detail = new HashMap<>();
        detail.put("forward_price", fwdPrice);
        commFwdMeasure.detail = detail;
        getFrtbList();
        return commFwdMeasure;
    }

    public Measure calc(MarketData marketData) {
        Preconditions.require(dataDate != null, "dataDate must be set");
        validateInputs(marketData);
        LocalDate settleDate = commFwdInfo.settleDate;
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);

        // 获取市场数据
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(commFwdInfo.discountCurve));
        CommSpot commSpot = new CommSpot(marketData.commSpot.get(commFwdInfo.referenceCurve));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        double fxRate = fxSpot.getFxrate(valuationCurrency);

        // 估值计量
        double disc = irSpot.discount(settleDate);
        double fwdPrice = commSpot.fwdPrice(settleDate);
        double disc1 = irSpot.discount(settleDate, 0.0001);
        double strikePrice = convertStrikePriceToValuationCurrency(
                commFwdInfo.strikePrice, valuationCurrency, strikeCurrency, fxSpot);
        double position = commFwdInfo.contractSize * ("B".equalsIgnoreCase(commFwdInfo.buyOrSell) ? 1 : -1);

        Measure measure = new Measure();
        measure.valuation = (fwdPrice - strikePrice) * disc * position;
        measure.pv01 = (fwdPrice - strikePrice) * (disc1 - disc) * position;
        measure.valuationCny = measure.valuation * fxRate;
        measure.valuationCcy = valuationCurrency;
        measure.instrumentId = commFwdInfo.instrumentId;
        return measure;
    }
    
    /*商品远期frtb*/
    private void getFrtbList(){
        List<FrtbSenes> list=new ArrayList<>();
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);
        String fxRiskCurrency = resolveFxRiskCurrency(valuationCurrency, strikeCurrency);
        String commBucket = hasText(commFwdInfo.frtbCommBucket) ? commFwdInfo.frtbCommBucket.trim() : null;
        String commAsset = resolveCmtyRiskFactorIdBase();
        boolean enableCmty = !FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                commFwdMeasure,
                commFwdInfo.instrumentId,
                commBucket,
                commAsset);
        if (hasText(fxRiskCurrency)) {
            List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                    Collections.singletonList(fxRiskCurrency));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                    marketData,
                    dataDate,
                    commFwdInfo.settleDate,
                    fxDeltaDependencies,
                    Collections.emptyList(),
                    true,
                    false,
                    commFwdInfo.instrumentId,
                    valuationCurrency,
                    1e-12,
                    com.zcyh.mr.product.basic.frtb.MeasureValuation.of(commFwdMeasure.valuation, commFwdMeasure.valuationCny),
                    shockedMarketData -> {
                        Measure shockedMeasure = calc(shockedMarketData);
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                    });
        list.addAll(fxSensitivities);
        }

        if (enableCmty) {
            List<FrtbDependency> cmtyDeltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
            commFwdInfo.referenceCurve,
                    resolveCmtyRiskFactorId(),
                    commBucket);
        List<FrtbSenes> cmtySensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                    marketData,
                    dataDate,
                    commFwdInfo.settleDate,
                    cmtyDeltaDependencies,
                    Collections.emptyList(),
                    true,
                    false,
                    commFwdInfo.instrumentId,
                    valuationCurrency,
                    1e-12,
                    com.zcyh.mr.product.basic.frtb.MeasureValuation.of(commFwdMeasure.valuation, commFwdMeasure.valuationCny),
                    shockedMarketData -> {
                        Measure shockedMeasure = calc(shockedMarketData);
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                    },
                    null);
        list.addAll(cmtySensitivities);
        }

        HashMap<String,String> map = new HashMap<>();
        map.put(commFwdInfo.discountCurve, valuationCurrency);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                commFwdInfo.settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(map),
                Collections.emptyList(),
                true,
                false,
                commFwdInfo.instrumentId,
                valuationCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(commFwdMeasure.valuation, commFwdMeasure.valuationCny),
                shockedMarketData -> {
                    Measure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        list.removeIf(item -> Math.abs(item.sensitivityValInstCurrCny) < 1e-12);/*移除敏度结果接近0的元素*/
        commFwdMeasure.sensitivityList = list;
    }

    private String resolveValuationCurrency() {
        if (hasText(commFwdInfo.currencyCode)) {
            return commFwdInfo.currencyCode;
        }
        if (hasText(commFwdInfo.strikeCurrencyCode)) {
            return commFwdInfo.strikeCurrencyCode;
        }
        Preconditions.require(false, "currencyCode and strikeCurrencyCode cannot both be empty");
        return null;
    }

    private String resolveStrikeCurrency(String valuationCurrency) {
        if (hasText(commFwdInfo.strikeCurrencyCode)) {
            return commFwdInfo.strikeCurrencyCode;
        }
        return valuationCurrency;
    }

    private String resolveFxRiskCurrency(String valuationCurrency, String strikeCurrency) {
        if (hasText(valuationCurrency) && hasText(strikeCurrency) && !valuationCurrency.equalsIgnoreCase(strikeCurrency)) {
            if (!isDomesticCurrency(valuationCurrency)) {
                return valuationCurrency;
            }
            return strikeCurrency;
        }
        return valuationCurrency;
    }

    /**
     * 商品远期的本币集合与 FX builder 保持一致，统一豁免 CNY/CNH。
     */
    private boolean isDomesticCurrency(String currency) {
        return "CNY".equalsIgnoreCase(currency) || "CNH".equalsIgnoreCase(currency);
    }

    private double convertStrikePriceToValuationCurrency(double strikePrice, String valuationCurrency,
            String strikeCurrency, FxSpot fxSpot) {
        if (!hasText(valuationCurrency) || !hasText(strikeCurrency) || valuationCurrency.equalsIgnoreCase(strikeCurrency)) {
            return strikePrice;
        }
        double strikeToCny = fxSpot.getFxrate(strikeCurrency);
        double valuationToCny = fxSpot.getFxrate(valuationCurrency);
        return strikePrice * strikeToCny / valuationToCny;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void validateInputs(MarketData md) {
        if (commFwdInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空: instrumentId=" + (commFwdInfo.instrumentId == null ? "" : commFwdInfo.instrumentId));
        }
        if (!hasText(commFwdInfo.buyOrSell)
                || (!"B".equalsIgnoreCase(commFwdInfo.buyOrSell) && !"S".equalsIgnoreCase(commFwdInfo.buyOrSell))) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + commFwdInfo.buyOrSell);
        }
        if (commFwdInfo.contractSize == null || !Double.isFinite(commFwdInfo.contractSize)
                || commFwdInfo.contractSize <= 0.0) {
            throw new IllegalArgumentException("CONTRACT_SIZE 必须为正有限数: " + commFwdInfo.contractSize);
        }
        if (commFwdInfo.strikePrice == null || !Double.isFinite(commFwdInfo.strikePrice)) {
            throw new IllegalArgumentException("STRIKE_PRICE 无效: " + commFwdInfo.strikePrice);
        }
        if (commFwdInfo.settleDate == null) {
            throw new IllegalArgumentException("SETTLE_DATE 未设置");
        }
        if (!hasText(commFwdInfo.discountCurve)) {
            throw new IllegalArgumentException("DISCOUNT_CURVE 未设置");
        }
        if (!hasText(commFwdInfo.referenceCurve)) {
            throw new IllegalArgumentException("REFERENCE_CURVE 未设置");
        }
        if (md.irSpot == null || md.irSpot.get(commFwdInfo.discountCurve) == null) {
            throw new IllegalArgumentException("市场数据缺少利率曲线: " + commFwdInfo.discountCurve);
        }
        if (md.commSpot == null
                || md.commSpot.get(commFwdInfo.referenceCurve) == null
                || md.commSpot.get(commFwdInfo.referenceCurve).curveData == null
                || md.commSpot.get(commFwdInfo.referenceCurve).curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少商品价格曲线: " + commFwdInfo.referenceCurve);
        }
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);
        ensureFxRateAvailable(md, valuationCurrency);
        ensureFxRateAvailable(md, strikeCurrency);
        validateCmtyRiskFactorInputs();
    }

    private void ensureFxRateAvailable(MarketData md, String currency) {
        if (!hasText(currency) || "CNY".equalsIgnoreCase(currency)) {
            return;
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少汇率曲线: " + currency);
        }
        try {
            FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
            double rate = fxSpot.getFxrate(currency);
            if (!Double.isFinite(rate) || rate <= 0) {
                throw new IllegalArgumentException("汇率无效: " + currency + "=" + rate);
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("无法获取汇率: " + currency, ex);
        }
    }

    private void validateCmtyRiskFactorInputs() {
        resolveUnderlyingForCalc();
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
        if (!hasText(base)) {
            return null;
        }
        String location = commFwdInfo.frtbCommLocation == null ? "" : commFwdInfo.frtbCommLocation.trim();
        if (!hasText(location)) {
            return base;
        }
        return base + "&" + location;
    }

    private String resolveCmtyRiskFactorIdBase() {
        String asset = commFwdInfo.frtbCommAsset == null ? "" : commFwdInfo.frtbCommAsset.trim();
        if (hasText(asset)) {
            return asset;
        }
        return null;
    }

    private String resolveUnderlyingForCalc() {
        String underlying = commFwdInfo.underlyingCode == null ? "" : commFwdInfo.underlyingCode.trim();
        if (hasText(underlying)) {
            return underlying;
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CODE，无法进行商品交易计量");
    }

    // 商品远期内部类，封装传入的基本信息
    static public class CommFwdInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "STRIKE_CURRENCY_CODE")
        public String strikeCurrencyCode;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONTRACT_SIZE")
        public Double contractSize;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
        @ProductInputField(required = true)
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
    }
}

