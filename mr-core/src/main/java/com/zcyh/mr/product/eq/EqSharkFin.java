package com.zcyh.mr.product.eq;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.SharkFinBase;

import java.util.ArrayList;
import java.util.List;

public class EqSharkFin extends SharkFinBase<EqSharkFin.EqSharkFinInfo, EqSharkFin.EqSharkFinMeasure> {

    public EqSharkFin(java.time.LocalDate dataDate, EqSharkFinInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    public EqSharkFinMeasure calc() {
        EqSharkFinMeasure measure = super.calc();
        if ("SUCCESS".equalsIgnoreCase(measure.status)) {
            measure.sensitivityList = buildEqFrtbSensListCommon(
                    measure,
                    info.settleDate,
                    getValuationCurrency(),
                    info.discountCurve,
                info.referenceCurve,
                    info.volatilitySurface,
                    resolveOptionalBucket("11", "frtbEqBucket"),
                    this::calc);
        }
        return measure;
    }

    @Override
    protected EqSharkFinMeasure newMeasure() {
        return new EqSharkFinMeasure();
    }

    @Override
    protected void postProcessOptionOutput(EqSharkFinMeasure measure) {
        measure.cashFlowList = null;
        measure.sensitivityList = null;
    }

    @Override
    protected MarketContext buildMarketContext(MarketData marketData, int days, double t) {
        MarketContext ctx = new MarketContext();

        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(info.discountCurve));
        EqSpot eqSpot = new EqSpot(marketData.eqSpot.get(info.referenceCurve));
        EqVol eqVol = new EqVol(marketData.eqVol.get(info.volatilitySurface));

        ctx.s = eqSpot.fwdPrice(dataDate);
        ctx.rd = irSpot.spotRate(info.maturityDate);
        ctx.rebase = ctx.rd;
        ctx.f = ctx.s * Math.exp(ctx.rebase * t);
        ctx.rf = impliedRf(ctx.s, ctx.f, t, ctx.rd);
        ctx.volCurve = eqVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(getValuationCurrency());
        return ctx;
    }

    @Override
    protected List<String> validateSpecificInputs(MarketData marketData) {
        ArrayList<String> errors = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.eqSpot == null
                || marketData.eqVol == null || marketData.fxSpot == null) {
            errors.add("marketData 缺少必要字段");
            return errors;
        }
        if (info.discountCurve == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            errors.add("缺少市场数据: DISCOUNT_CURVE");
        }
        if (info.referenceCurve == null || !marketData.eqSpot.containsKey(info.referenceCurve)) {
            errors.add("缺少市场数据: REFERENCE_CURVE(EQ_SPOT)");
        }
        if (info.volatilitySurface == null || !marketData.eqVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(EQ_VOL)");
        }
        if (info.currencyCode == null || info.currencyCode.trim().isEmpty()) {
            errors.add("CURRENCY_CODE 不能为空");
        }
        return errors;
    }

    public static class EqSharkFinMeasure extends OptionMeasure {
    }

    public static class EqSharkFinInfo extends SharkFinBase.SharkFinBaseInfo {
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
    }
}
