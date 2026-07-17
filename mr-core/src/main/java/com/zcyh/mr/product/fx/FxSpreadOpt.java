package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.SpreadOptBase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * FX Spread Option 产品类。
 * 继承 SpreadOptBase，实现 FX 特有的市场数据获取和校验。
 */
public class FxSpreadOpt extends SpreadOptBase<FxSpreadOpt.SpreadOptInfo, FxSpreadOpt.SpreadOptMeasure> {
    public FxSpreadOpt(LocalDate dataDate, SpreadOptInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    protected SpreadOptMeasure newMeasure() {
        return new SpreadOptMeasure();
    }

    @Override
    public FxSpreadOpt.SpreadOptMeasure calc() {
        FxSpreadOpt.SpreadOptMeasure measure = super.calc();
        if ("SUCCESS".equalsIgnoreCase(measure.status)) {
            measure.sensitivityList = buildFxFrtbSensListCommon(
                    measure,
                    info.settleDate,
                    info.underlyingCurrencyCode,
                    info.baseCurrencyCode,
                    info.baseCurrencyCode,
                    info.baseDiscountCurve,
                    info.underlyingDiscountCurve,
                    info.volatilitySurface,
                    this::calc);
        }
        return measure;
    }

    @Override
    protected String getValuationCcy() {
        return info.baseCurrencyCode;
    }

    @Override
    protected MarketContext buildMarketContext(MarketData md, int days, double t) {
        MarketContext ctx = new MarketContext();
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(info.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(info.baseDiscountCurve));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        ctx.s = fxSpot.getFxrate(info.baseCurrencyCode, info.underlyingCurrencyCode);
        if ("Cash".equalsIgnoreCase(info.settleType)) {
            ctx.rd = bIrSpot.spotRate(info.maturityDate);
            ctx.rf = uIrSpot.spotRate(info.maturityDate);
        } else {
            ctx.rd = bIrSpot.spotRate(info.settleDate);
            ctx.rf = uIrSpot.spotRate(info.settleDate);
        }
        if (Double.isFinite(t) && t > 0.0) {
            ctx.f = ctx.s * Math.exp((ctx.rd - ctx.rf) * t);
        } else {
            ctx.f = ctx.s;
        }
        ctx.cash = "CASH".equalsIgnoreCase(info.settleType);
        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));
        ctx.volCurve = fxVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(info.baseCurrencyCode);
        return ctx;
    }

    @Override
    protected List<String> validateSpecific() {
        List<String> errors = new ArrayList<>();
        if (info.baseCurrencyCode == null || info.baseCurrencyCode.trim().isEmpty()) {
            errors.add("BASE_CURRENCY_CODE 未设置");
        }
        if (info.underlyingCurrencyCode == null || info.underlyingCurrencyCode.trim().isEmpty()) {
            errors.add("UNDERLYING_CURRENCY_CODE 未设置");
        }
        if (info.baseDiscountCurve == null || !marketData.irSpot.containsKey(info.baseDiscountCurve)) {
            errors.add("缺少市场数据: BASE_DISCOUNT_CURVE");
        }
        if (info.underlyingDiscountCurve == null || !marketData.irSpot.containsKey(info.underlyingDiscountCurve)) {
            errors.add("缺少市场数据: UNDERLYING_DISCOUNT_CURVE");
        }
        if (info.baseCurrencyCode != null && info.underlyingCurrencyCode != null
                && info.baseCurrencyCode.equalsIgnoreCase(info.underlyingCurrencyCode)) {
            errors.add("BASE_CURRENCY_CODE 与 UNDERLYING_CURRENCY_CODE 不能相同");
        }
        if (info.volatilitySurface == null || !marketData.fxVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(FX_VOL)");
        }
        return errors;
    }

    public static class SpreadOptMeasure extends OptionMeasure {
    }

    public static class SpreadOptInfo extends SpreadOptBase.SpreadOptBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
    }
}
