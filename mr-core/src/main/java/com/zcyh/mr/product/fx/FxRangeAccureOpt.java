package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.structure.RangeAccureOptBase;

import java.time.LocalDate;
import java.util.*;

/**
 * 外汇标的区间累计期权。
 * 标的价格取自外汇定盘利率，波动率取自外汇波动率曲面，
 * 远期汇率通过两条隐含利率曲线推导。
 */
public class FxRangeAccureOpt extends RangeAccureOptBase<FxRangeAccureOpt.FxRangeAccureTradeInfo> {

    public FxRangeAccureOpt(LocalDate dataDate, FxRangeAccureTradeInfo rangeAccureInfo, MarketData marketData) {
        super(dataDate, rangeAccureInfo, marketData);
    }

    @Override
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();
        list.addAll(getSensListGIRR());
        list.addAll(buildFxFrtbSensListCommon(
                rangeAccureMeasure,
                getFrtbSettleDate(),
                resolveUnderlyingCurrency(),
                resolveBaseCurrency(),
                getValuationCurrency(),
                rangeAccureInfo.volatilitySurface,
                this::calc));
        return list;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireNotNull(md.fxSpot, "marketData.fxSpot");
        requireNotNull(md.fxSpot.curveData, "marketData.fxSpot.curveData");
        if (md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("缺少外汇即期曲线(FX_SPOT)");
        }
        requireNotNull(md.fxVol, "marketData.fxVol");
        if (!md.irSpot.containsKey(resolveUnderlyingDiscountCurve())) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + resolveUnderlyingDiscountCurve());
        }
        if (!md.irSpot.containsKey(resolveBaseDiscountCurve())) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + resolveBaseDiscountCurve());
        }
        if (!md.fxVol.containsKey(rangeAccureInfo.volatilitySurface)) {
            throw new IllegalArgumentException("缺少外汇波动率曲面: " + rangeAccureInfo.volatilitySurface);
        }
    }

    /**
     * 根据币种代码生成隐含利率曲线名称
     */
    private String generateDiscountCurve(String code) {
        return "FX_IMPLIED_" + code;
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(resolveBaseCurrency(), resolveUnderlyingCurrency());
    }

    @Override
    protected List<Map<String, Object>> getVolCurve(MarketData md, int days) {
        FxVol vol = new FxVol(md.fxVol.get(rangeAccureInfo.volatilitySurface));
        return vol.getVolCur(days);
    }

    @Override
    protected ObsParams buildObsParams(MarketData md, LocalDate obsDate, int days, double t,
            double s, double rebase, double discount) {
        ObsParams p = new ObsParams();
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(resolveUnderlyingDiscountCurve()));
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(resolveBaseDiscountCurve()));
        p.rd = bIrSpot.spotRate(obsDate);
        p.rf = uIrSpot.spotRate(obsDate);
        p.f = s * Math.exp((p.rd - p.rf) * t);
        return p;
    }

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        putCurveCcy(map, resolveUnderlyingDiscountCurve(), resolveUnderlyingCurrency());
        putCurveCcy(map, resolveBaseDiscountCurve(), resolveBaseCurrency());
        putCurveCcy(map, rangeAccureInfo.discountCurve, getValuationCurrency());
        return map;
    }

    @Override
    protected boolean enableGirrDelta() {
        return true;
    }

    @Override
    protected boolean enableGirrCurvature() {
        return false;
    }

    @Override
    protected boolean enableGirrVega() {
        return false;
    }

    @Override
    protected List<String> getFxRiskCurrencies() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        addFxCurrency(set, resolveUnderlyingCurrency());
        addFxCurrency(set, resolveBaseCurrency());
        addFxCurrency(set, getValuationCurrency());
        return new ArrayList<>(set);
    }

    @Override
    protected String getFxVegaBucketCurrency(List<String> fxRiskCurrencies) {
        String ccy = resolveUnderlyingCurrency();
        if (hasText(ccy)) {
            return ccy;
        }
        return super.getFxVegaBucketCurrency(fxRiskCurrencies);
    }

    private void addFxCurrency(Set<String> set, String ccy) {
        if (hasText(ccy) && !"CNY".equalsIgnoreCase(ccy)) {
            set.add(ccy);
        }
    }

    private String resolveUnderlyingCurrency() {
        if (hasText(rangeAccureInfo.underlyingCurrencyCode)) {
            return rangeAccureInfo.underlyingCurrencyCode;
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CURRENCY_CODE");
    }

    private String resolveBaseCurrency() {
        if (hasText(rangeAccureInfo.baseCurrencyCode)) {
            return rangeAccureInfo.baseCurrencyCode;
        }
        throw new IllegalArgumentException("缺少BASE_CURRENCY_CODE");
    }

    private String resolveUnderlyingDiscountCurve() {
        if (hasText(rangeAccureInfo.underlyingDiscountCurve)) {
            return rangeAccureInfo.underlyingDiscountCurve;
        }
        return generateDiscountCurve(resolveUnderlyingCurrency());
    }

    private String resolveBaseDiscountCurve() {
        if (hasText(rangeAccureInfo.baseDiscountCurve)) {
            return rangeAccureInfo.baseDiscountCurve;
        }
        return generateDiscountCurve(resolveBaseCurrency());
    }

    private void putCurveCcy(Map<String, String> map, String curve, String ccy) {
        if (hasText(curve) && hasText(ccy)) {
            map.put(curve, ccy);
        }
    }

    public static class FxRangeAccureTradeInfo extends RangeAccureOptBase.RangeAccureFrtbTradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
    }
}
