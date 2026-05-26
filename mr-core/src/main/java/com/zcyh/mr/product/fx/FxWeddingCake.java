package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.WeddingCakeBase;

import java.time.LocalDate;
import java.util.Locale;

public class FxWeddingCake extends WeddingCakeBase<FxWeddingCake.FxWeddingCakeInfo, FxWeddingCake.FxWeddingCakeMeasure> {
    private boolean frtbScenarioPricing;

    public FxWeddingCake(LocalDate dataDate, FxWeddingCakeInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    protected FxWeddingCakeMeasure newMeasure() {
        return new FxWeddingCakeMeasure();
    }

    @Override
    protected void postProcessOptionOutput(FxWeddingCakeMeasure measure) {
        if (frtbScenarioPricing) {
            measure.sensitivityList = null;
            return;
        }
        measure.sensitivityList = buildFxFrtbSensListCommon(
                measure,
                info.settleDate,
                resolveUnderlyingCurrency(),
                resolveBaseCurrency(),
                info.currencyCode,
                resolveBaseDiscountCurve(),
                resolveUnderlyingDiscountCurve(),
                info.discountCurve,
                info.volatilitySurface,
                this::calcForFrtbScenario);
    }

    @Override
    protected MarketContext buildMarketContext(MarketData md, int days, double t) {
        MarketContext ctx = new MarketContext();
        String underlyingCcy = resolveUnderlyingCurrency();
        String baseCcy = resolveBaseCurrency();

        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        IrSpot underlyingIr = new IrSpot(md.irSpot.get(resolveUnderlyingDiscountCurve()));
        IrSpot baseIr = new IrSpot(md.irSpot.get(resolveBaseDiscountCurve()));
        IrSpot settleIr = new IrSpot(md.irSpot.get(info.discountCurve));
        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));

        ctx.s = fxSpot.getFxrate(baseCcy, underlyingCcy);
        ctx.t = Math.max(0.0, t);
        ctx.ts = Math.max(0.0, yearFrac(dataDate, info.settleDate));
        ctx.rd = baseIr.spotRate(info.maturityDate);
        ctx.rf = underlyingIr.spotRate(info.maturityDate);
        ctx.rebase = settleIr.spotRate(info.settleDate);
        ctx.f = ctx.s * Math.exp((ctx.rd - ctx.rf) * ctx.t);
        ctx.volCurve = fxVol.getVolCur(days);
        if (ctx.volCurve == null || ctx.volCurve.isEmpty()) {
            throw new IllegalArgumentException("波动率曲线为空: " + info.volatilitySurface + ", days=" + days);
        }
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireNotNull(md.fxSpot, "marketData.fxSpot");
        requireNotNull(md.fxSpot.curveData, "marketData.fxSpot.curveData");
        if (md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("缺少外汇即期曲线(FX_SPOT)");
        }
        requireNotNull(md.fxVol, "marketData.fxVol");

        String underlyingCcy = resolveUnderlyingCurrency();
        String baseCcy = resolveBaseCurrency();
        if (underlyingCcy.length() != 3 || baseCcy.length() != 3) {
            throw new IllegalArgumentException("UNDERLYING/BASE CURRENCY_CODE 必须是 3 位代码");
        }

        requireText(resolveUnderlyingDiscountCurve(), "UNDERLYING_DISCOUNT_CURVE");
        requireText(resolveBaseDiscountCurve(), "BASE_DISCOUNT_CURVE");
        if (!md.irSpot.containsKey(resolveUnderlyingDiscountCurve())) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + resolveUnderlyingDiscountCurve());
        }
        if (!md.irSpot.containsKey(resolveBaseDiscountCurve())) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + resolveBaseDiscountCurve());
        }
        if (!md.irSpot.containsKey(info.discountCurve)) {
            throw new IllegalArgumentException("缺少贴现曲线: " + info.discountCurve);
        }
        if (!md.fxVol.containsKey(info.volatilitySurface)) {
            throw new IllegalArgumentException("缺少外汇波动率曲面: " + info.volatilitySurface);
        }
    }

    private String resolveUnderlyingCurrency() {
        if (hasText(info.underlyingCurrencyCode)) {
            return normalizeCcy(info.underlyingCurrencyCode);
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CURRENCY_CODE");
    }

    private String resolveBaseCurrency() {
        if (hasText(info.baseCurrencyCode)) {
            return normalizeCcy(info.baseCurrencyCode);
        }
        throw new IllegalArgumentException("缺少BASE_CURRENCY_CODE");
    }

    private String resolveUnderlyingDiscountCurve() {
        if (hasText(info.underlyingDiscountCurve)) {
            return info.underlyingDiscountCurve;
        }
        return generateDiscountCurve(resolveUnderlyingCurrency());
    }

    private String resolveBaseDiscountCurve() {
        if (hasText(info.baseDiscountCurve)) {
            return info.baseDiscountCurve;
        }
        return generateDiscountCurve(resolveBaseCurrency());
    }

    private String generateDiscountCurve(String ccy) {
        return ccy + "_IMPLIED_ZERO";
    }

    private String normalizeCcy(String ccy) {
        return ccy == null ? "" : ccy.trim().toUpperCase(Locale.ROOT);
    }

    private FxWeddingCakeMeasure calcForFrtbScenario(MarketData shockedMarketData) {
        boolean old = frtbScenarioPricing;
        frtbScenarioPricing = true;
        try {
            return calc(shockedMarketData);
        } finally {
            frtbScenarioPricing = old;
        }
    }

    public static class FxWeddingCakeMeasure extends OptionMeasure {
    }

    public static class FxWeddingCakeInfo extends WeddingCakeBase.WeddingCakeBaseInfo {
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
    }
}
