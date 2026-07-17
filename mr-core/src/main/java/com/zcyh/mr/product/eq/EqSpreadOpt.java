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
import com.zcyh.mr.product.basic.structure.SpreadOptBase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 权益 Spread Option 产品类。
 * 继承 SpreadOptBase，实现 EQ 特有的市场数据获取和校验。
 */
public class EqSpreadOpt extends SpreadOptBase<EqSpreadOpt.SpreadOptInfo, EqSpreadOpt.SpreadOptMeasure> {

    public EqSpreadOpt(LocalDate dataDate, SpreadOptInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    public SpreadOptMeasure calc() {
        SpreadOptMeasure measure = super.calc();
        if ("SUCCESS".equalsIgnoreCase(measure.status)) {
            measure.sensitivityList = buildEqFrtbSensListCommon(
                    measure,
                    info.settleDate,
                    info.currencyCode,
                    info.discountCurve,
                info.referenceCurve,
                    info.volatilitySurface,
                    resolveOptionalBucket("11", "frtbEqBucket"),
                    this::calc);
        }
        return measure;
    }

    @Override
    protected SpreadOptMeasure newMeasure() {
        return new SpreadOptMeasure();
    }

    @Override
    protected String getValuationCcy() {
        return info.currencyCode;
    }

    @Override
    protected MarketContext buildMarketContext(MarketData md, int days, double t) {
        MarketContext ctx = new MarketContext();
        IrSpot irSpot = new IrSpot(md.irSpot.get(info.discountCurve));
        EqSpot eqSpot = new EqSpot(md.eqSpot.get(info.referenceCurve));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        ctx.s = eqSpot.fwdPrice(dataDate);
        ctx.rd = irSpot.spotRate(info.maturityDate);
        // 不考虑 dividend，rf = 0
        ctx.rf = 0.0;
        ctx.f = ctx.s * Math.exp(ctx.rd * t);
        ctx.cash = "CASH".equalsIgnoreCase(info.settleType);
        EqVol eqVol = new EqVol(md.eqVol.get(info.volatilitySurface));
        ctx.volCurve = eqVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected List<String> validateSpecific() {
        List<String> errors = new ArrayList<>();
        if (info.discountCurve == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            errors.add("缺少市场数据: DISCOUNT_CURVE");
        }
        if (info.referenceCurve == null || !marketData.eqSpot.containsKey(info.referenceCurve)) {
            errors.add("缺少市场数据: REFERENCE_CURVE(EQ_SPOT)");
        }
        if (info.volatilitySurface == null || !marketData.eqVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(EQ_VOL)");
        }
        return errors;
    }

    public static class SpreadOptMeasure extends OptionMeasure {
    }

    public static class SpreadOptInfo extends SpreadOptBase.SpreadOptBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
    }
}
