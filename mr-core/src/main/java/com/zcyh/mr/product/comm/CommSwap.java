package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.Preconditions;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.Measure;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 商品掉期类
 *
 * @author xujg
 * @version 1.0
 * @date 2024/7/11 16:12
 */
public class CommSwap {
    private LocalDate dataDate;
    private CommSwapInfo commSwapInfo;
    private MarketData marketData;
    private CommSwapMeasure commSwapMeasure = new CommSwapMeasure();

    private double fxRate;//币种汇率
    private int[] termDays;//根据估值日期
    private final static double[] num = EngineConstants.PRODUCT_CODE.TERM_YEAR;

    public CommSwap(LocalDate dataDate,CommSwapInfo tradeInfo,MarketData marketData){
        this.dataDate = dataDate;
        this.commSwapInfo = tradeInfo;
        this.marketData = marketData;
        this.termDays = CommUtils.tranfToDays(dataDate, EngineConstants.PRODUCT_CODE.TERM_CODE);
    }

    public CommSwapMeasure calc() {
        Preconditions.require(dataDate != null, "dataDate must be set");
        validateInputs(marketData);
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(commSwapInfo.discountCurve));
        CommSpot commSpot = new CommSpot(marketData.commSpot.get(commSwapInfo.referenceCurve));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        fxRate = fxSpot.getFxrate(valuationCurrency);
        double spotStrike = convertStrikePriceToValuationCurrency(
                commSwapInfo.spotStrike, valuationCurrency, strikeCurrency, fxSpot);
        double fwdStrike = convertStrikePriceToValuationCurrency(
                commSwapInfo.fwdStrike, valuationCurrency, strikeCurrency, fxSpot);

        //判断买卖方向，判断一端，另一端直接取反
        int direction = ("S".equalsIgnoreCase(commSwapInfo.buyOrSell) ? 1 : -1);
        double position = commSwapInfo.contractSize * direction * -1;
        CommSwapMeasure spot = count(irSpot,commSpot,commSwapInfo.spotSettleDate, spotStrike,direction);
        CommSwapMeasure fwd = count(irSpot,commSpot,commSwapInfo.fwdSettleDate,fwdStrike,direction * -1);

        //估值结果
        double val = spot.valuation + fwd.valuation;
        double pv01 = spot.pv01 + fwd.pv01;
        this.commSwapMeasure.spotValue = spot.valuation;
        this.commSwapMeasure.fwdValue = fwd.valuation;
        this.commSwapMeasure.position = position;
        this.commSwapMeasure.valuation = val;
        this.commSwapMeasure.valuationUnit = (position == 0 ? 0 : val / position);
        this.commSwapMeasure.pv01 = pv01;
        this.commSwapMeasure.valuationCny = commSwapMeasure.valuation * fxRate;
        this.commSwapMeasure.valuationCcy = valuationCurrency;
        this.commSwapMeasure.instrumentId = commSwapInfo.instrumentId;
        this.commSwapMeasure.productCode = commSwapInfo.productCode;
        this.commSwapMeasure.dataDate = dataDate;
        this.commSwapMeasure.status = "SUCCESS";
        this.commSwapMeasure.logs = new ArrayList<>();
        this.commSwapMeasure.cashFlowList = null;
        Map<String, Object> detail = new HashMap<>();
        if (commSwapInfo.spotSettleDate != null && dataDate.isBefore(commSwapInfo.spotSettleDate)) {
            detail.put("spot_price", commSpot.fwdPrice(commSwapInfo.spotSettleDate));
        }
        if (commSwapInfo.fwdSettleDate != null && dataDate.isBefore(commSwapInfo.fwdSettleDate)) {
            detail.put("forward_price", commSpot.fwdPrice(commSwapInfo.fwdSettleDate));
        }
        this.commSwapMeasure.detail = detail;
        getFrtbSensList();
        return this.commSwapMeasure;
    }

    public CommSwapMeasure calc(MarketData marketData) {
        Preconditions.require(dataDate != null, "dataDate must be set");
        validateInputs(marketData);
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(commSwapInfo.discountCurve));
        CommSpot commSpot = new CommSpot(marketData.commSpot.get(commSwapInfo.referenceCurve));
        double localFxRate = fxSpot.getFxrate(valuationCurrency);
        double spotStrike = convertStrikePriceToValuationCurrency(
                commSwapInfo.spotStrike, valuationCurrency, strikeCurrency, fxSpot);
        double fwdStrike = convertStrikePriceToValuationCurrency(
                commSwapInfo.fwdStrike, valuationCurrency, strikeCurrency, fxSpot);

        //判断买卖方向，判断一端，另一端直接取反
        int direction = ("S".equalsIgnoreCase(commSwapInfo.buyOrSell) ? 1 : -1);
        double position = commSwapInfo.contractSize * direction;
        CommSwapMeasure spot = count(irSpot,commSpot,commSwapInfo.spotSettleDate, spotStrike,direction);
        CommSwapMeasure fwd = count(irSpot,commSpot,commSwapInfo.fwdSettleDate,fwdStrike,direction * -1);

        //估值结果
        double val = spot.valuation + fwd.valuation;
        double pv01 = spot.pv01 + fwd.pv01;
        CommSwapMeasure measure = new CommSwapMeasure();
        measure.spotValue = spot.valuation;
        measure.fwdValue = fwd.valuation;
        measure.valuation = val;
        measure.pv01 = pv01;
        measure.valuationCny = val * localFxRate;
        measure.valuationCcy = valuationCurrency;
        measure.instrumentId = commSwapInfo.instrumentId;

        return measure;
    }
    /**
     *  掉期交易近端远端分别调用，计算得出的估值返回到封装类中，敏度以及现金流则直接写入返回的结果中
     * @date 2024-07-16 13:48:841
     * @author xujg
     */
    private CommSwapMeasure count(IrSpot irSpot, CommSpot commSpot,LocalDate date,double strike,int flg) {
        CommSwapMeasure res = new CommSwapMeasure();

        double disc = irSpot.discount(date);
        double price = commSpot.fwdPrice(date);
        double disc1 = irSpot.discount(date, 0.0001);

        //估值
        int t = (int) ChronoUnit.DAYS.between(dataDate,date);
        double valuation = (t < 0 ? 0 : (price - strike) * disc * commSwapInfo.contractSize * flg);
        double pv01 = (t < 0 ? 0 : (price - strike) * (disc1 - disc) * commSwapInfo.contractSize * flg);
        res.valuation = valuation;
        res.pv01 = pv01;
        return res;
    }

    private void getFrtbSensList() {
        List<FrtbSenes> list=new ArrayList<>();
        String valuationCurrency = resolveValuationCurrency();
        String strikeCurrency = resolveStrikeCurrency(valuationCurrency);
        String fxRiskCurrency = resolveFxRiskCurrency(valuationCurrency, strikeCurrency);
        String commBucket = hasText(commSwapInfo.frtbCommBucket) ? commSwapInfo.frtbCommBucket.trim() : null;
        String commAsset = resolveCmtyRiskFactorIdBase();
        boolean enableCmty = !FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                commSwapMeasure,
                commSwapInfo.instrumentId,
                commBucket,
                commAsset);
        HashMap<String,String> map = new HashMap<>();
        map.put(commSwapInfo.discountCurve, valuationCurrency);

        if (hasText(fxRiskCurrency)){
            List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                    Collections.singletonList(fxRiskCurrency));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                    marketData,
                    dataDate,
                    commSwapInfo.fwdSettleDate,
                    fxDeltaDependencies,
                    Collections.emptyList(),
                    true,
                    false,
                    commSwapInfo.instrumentId,
                    valuationCurrency,
                    1e-12,
                    com.zcyh.mr.product.basic.frtb.MeasureValuation.of(commSwapMeasure.valuation, commSwapMeasure.valuationCny),
                    shockedMarketData -> {
                        CommSwapMeasure shockedMeasure = calc(shockedMarketData);
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                    });
        list.addAll(fxSensitivities);
        }

        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                commSwapInfo.fwdSettleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(map),
                Collections.emptyList(),
                true,
                false,
                commSwapInfo.instrumentId,
                valuationCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(commSwapMeasure.valuation, commSwapMeasure.valuationCny),
                shockedMarketData -> {
                    CommSwapMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        if (enableCmty) {
            List<FrtbDependency> cmtyDeltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
                commSwapInfo.referenceCurve,
                    resolveCmtyRiskFactorId(),
                    commBucket);
        List<FrtbSenes> cmtySensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                    marketData,
                    dataDate,
                    commSwapInfo.fwdSettleDate,
                    cmtyDeltaDependencies,
                    Collections.emptyList(),
                    true,
                    false,
                    commSwapInfo.instrumentId,
                    valuationCurrency,
                    1e-12,
                    com.zcyh.mr.product.basic.frtb.MeasureValuation.of(commSwapMeasure.valuation, commSwapMeasure.valuationCny),
                    shockedMarketData -> {
                        CommSwapMeasure shockedMeasure = calc(shockedMarketData);
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                    },
                    null);
        list.addAll(cmtySensitivities);
        }

        list.removeIf(item -> Math.abs(item.sensitivityValInstCurrCny) < 1e-12);    /* 移除敏度结果接近0的元素 */
        commSwapMeasure.sensitivityList = list;
    }

    private String resolveValuationCurrency() {
        if (hasText(commSwapInfo.currencyCode)) {
            return commSwapInfo.currencyCode;
        }
        if (hasText(commSwapInfo.strikeCurrencyCode)) {
            return commSwapInfo.strikeCurrencyCode;
        }
        Preconditions.require(false, "currencyCode and strikeCurrencyCode cannot both be empty");
        return null;
    }

    private String resolveStrikeCurrency(String valuationCurrency) {
        if (hasText(commSwapInfo.strikeCurrencyCode)) {
            return commSwapInfo.strikeCurrencyCode;
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
     * 商品掉期的本币集合与 FX builder 保持一致，统一豁免 CNY/CNH。
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
        if (commSwapInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空: instrumentId=" + (commSwapInfo.instrumentId == null ? "" : commSwapInfo.instrumentId));
        }
        if (!hasText(commSwapInfo.buyOrSell)
                || (!"B".equalsIgnoreCase(commSwapInfo.buyOrSell) && !"S".equalsIgnoreCase(commSwapInfo.buyOrSell))) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + commSwapInfo.buyOrSell);
        }
        if (commSwapInfo.contractSize == null || !Double.isFinite(commSwapInfo.contractSize)
                || commSwapInfo.contractSize <= 0.0) {
            throw new IllegalArgumentException("CONTRACT_SIZE 必须为正有限数: " + commSwapInfo.contractSize);
        }
        if (commSwapInfo.spotStrike == null || !Double.isFinite(commSwapInfo.spotStrike)) {
            throw new IllegalArgumentException("SPOT_STRIKE 无效: " + commSwapInfo.spotStrike);
        }
        if (commSwapInfo.fwdStrike == null || !Double.isFinite(commSwapInfo.fwdStrike)) {
            throw new IllegalArgumentException("FWD_STRIKE 无效: " + commSwapInfo.fwdStrike);
        }
        if (commSwapInfo.spotSettleDate == null) {
            throw new IllegalArgumentException("SPOT_SETTLE_DATE 未设置");
        }
        if (commSwapInfo.fwdSettleDate == null) {
            throw new IllegalArgumentException("FWD_SETTLE_DATE 未设置");
        }
        if (!hasText(commSwapInfo.discountCurve)) {
            throw new IllegalArgumentException("DISCOUNT_CURVE 未设置");
        }
        if (!hasText(commSwapInfo.referenceCurve)) {
            throw new IllegalArgumentException("REFERENCE_CURVE 未设置");
        }
        if (md.irSpot == null || md.irSpot.get(commSwapInfo.discountCurve) == null) {
            throw new IllegalArgumentException("市场数据缺少利率曲线: " + commSwapInfo.discountCurve);
        }
        if (md.commSpot == null
                || md.commSpot.get(commSwapInfo.referenceCurve) == null
                || md.commSpot.get(commSwapInfo.referenceCurve).curveData == null
                || md.commSpot.get(commSwapInfo.referenceCurve).curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少商品价格曲线: " + commSwapInfo.referenceCurve);
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
        String location = commSwapInfo.frtbCommLocation == null ? "" : commSwapInfo.frtbCommLocation.trim();
        if (!hasText(location)) {
            return base;
        }
        return base + "&" + location;
    }

    private String resolveCmtyRiskFactorIdBase() {
        String asset = commSwapInfo.frtbCommAsset == null ? "" : commSwapInfo.frtbCommAsset.trim();
        if (hasText(asset)) {
            return asset;
        }
        return null;
    }

    private String resolveUnderlyingForCalc() {
        String underlying = commSwapInfo.underlyingCode == null ? "" : commSwapInfo.underlyingCode.trim();
        if (hasText(underlying)) {
            return underlying;
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CODE，无法进行商品交易计量");
    }

    /* 封装结果 */
    public static class CommSwapMeasure extends Measure {
        @JSONField(serialize = false, deserialize = false)
        public double spotValue;
        @JSONField(serialize = false, deserialize = false)
        public double fwdValue;
    }

    /* 商品掉期内部类，封装传入的基本信息 */
    public static class CommSwapInfo{
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
        @JSONField(name = "SPOT_STRIKE")
        public Double spotStrike;
        @ProductInputField(required = true)
        @JSONField(name = "SPOT_SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate spotSettleDate;
        @ProductInputField(required = true)
        @JSONField(name = "FWD_STRIKE")
        public Double fwdStrike;
        @ProductInputField(required = true)
        @JSONField(name = "FWD_SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate fwdSettleDate;
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

