package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.SharkFinBase;

import java.util.ArrayList;
import java.util.List;

public class FxSharkFin extends SharkFinBase<FxSharkFin.FxSharkFinInfo, FxSharkFin.FxSharkFinMeasure> {
    private boolean frtbScenarioPricing;

    public FxSharkFin(java.time.LocalDate dataDate, FxSharkFinInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    protected FxSharkFinMeasure newMeasure() {
        return new FxSharkFinMeasure();
    }

    @Override
    protected void postProcessOptionOutput(FxSharkFinMeasure measure) {
        measure.cashFlowList = null;
        if (frtbScenarioPricing) {
            measure.sensitivityList = null;
            return;
        }
        measure.sensitivityList = buildFxFrtbSensListCommon(
                measure,
                info.settleDate,
                resolveUnderlyingCurrency(),
                resolveBaseCurrency(),
                getValuationCurrency(),
                info.baseDiscountCurve,
                info.underlyingDiscountCurve,
                info.discountCurve,
                info.volatilitySurface,
                this::calcForFrtbScenario);
    }

    @Override
    protected MarketContext buildMarketContext(MarketData marketData, int days, double t) {
        MarketContext ctx = new MarketContext();

        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);

        String uCurrency = resolveUnderlyingCurrency();
        String bCurrency = resolveBaseCurrency();

        IrSpot uIrSpot = new IrSpot(marketData.irSpot.get(info.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(marketData.irSpot.get(info.baseDiscountCurve));
        IrSpot dIrSpot = new IrSpot(marketData.irSpot.get(info.discountCurve));
        FxVol fxVol = new FxVol(marketData.fxVol.get(info.volatilitySurface));

        // 与 FX 其他产品一致：rd=基础货币曲线，rf=标的货币曲线。
        ctx.rd = bIrSpot.spotRate(info.maturityDate);
        ctx.rf = uIrSpot.spotRate(info.maturityDate);
        ctx.rebase = dIrSpot.spotRate(info.maturityDate);
        ctx.s = fxSpot.getFxrate(bCurrency, uCurrency);
        ctx.f = ctx.s * Math.exp((ctx.rd - ctx.rf) * t);
        ctx.volCurve = fxVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(getValuationCurrency());
        return ctx;
    }

    @Override
    protected List<String> validateSpecificInputs(MarketData marketData) {
        ArrayList<String> errors = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.fxVol == null || marketData.fxSpot == null) {
            errors.add("marketData 缺少必要字段");
            return errors;
        }
        if (info.baseDiscountCurve == null || !marketData.irSpot.containsKey(info.baseDiscountCurve)) {
            errors.add("缺少市场数据: BASE_DISCOUNT_CURVE");
        }
        if (info.underlyingDiscountCurve == null || !marketData.irSpot.containsKey(info.underlyingDiscountCurve)) {
            errors.add("缺少市场数据: UNDERLYING_DISCOUNT_CURVE");
        }
        if (info.discountCurve == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            errors.add("缺少市场数据: DISCOUNT_CURVE");
        }
        if (info.volatilitySurface == null || !marketData.fxVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(FX_VOL)");
        }
        if (info.baseCurrencyCode == null || info.underlyingCurrencyCode == null) {
            errors.add("BASE_CURRENCY_CODE/UNDERLYING_CURRENCY_CODE 不能为空");
        }
        if (info.currencyCode == null || info.currencyCode.trim().isEmpty()) {
            errors.add("CURRENCY_CODE 不能为空");
        }
        return errors;
    }

    private String resolveUnderlyingCurrency() {
        if (info.underlyingCurrencyCode == null || info.underlyingCurrencyCode.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少UNDERLYING_CURRENCY_CODE");
        }
        return info.underlyingCurrencyCode;
    }

    private String resolveBaseCurrency() {
        if (info.baseCurrencyCode == null || info.baseCurrencyCode.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少BASE_CURRENCY_CODE");
        }
        return info.baseCurrencyCode;
    }

    private FxSharkFinMeasure calcForFrtbScenario(MarketData shockedMarketData) {
        boolean old = frtbScenarioPricing;
        frtbScenarioPricing = true;
        try {
            return calc(shockedMarketData);
        } finally {
            frtbScenarioPricing = old;
        }
    }

    public static class FxSharkFinMeasure extends OptionMeasure {
    }

    public static class FxSharkFinInfo extends SharkFinBase.SharkFinBaseInfo {
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
    }
}
