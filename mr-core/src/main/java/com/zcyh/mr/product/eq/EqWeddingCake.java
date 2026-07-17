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
import com.zcyh.mr.product.basic.structure.WeddingCakeBase;

import java.time.LocalDate;

public class EqWeddingCake
        extends WeddingCakeBase<EqWeddingCake.EqWeddingCakeInfo, EqWeddingCake.EqWeddingCakeMeasure> {

    public EqWeddingCake(LocalDate dataDate, EqWeddingCakeInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    public EqWeddingCakeMeasure calc() {
        EqWeddingCakeMeasure measure = super.calc();
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
    protected EqWeddingCakeMeasure newMeasure() {
        return new EqWeddingCakeMeasure();
    }

    @Override
    protected MarketContext buildMarketContext(MarketData md, int days, double t) {
        MarketContext ctx = new MarketContext();
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        IrSpot discount = new IrSpot(md.irSpot.get(info.discountCurve));
        EqSpot eqSpot = new EqSpot(md.eqSpot.get(info.referenceCurve));
        EqVol eqVol = new EqVol(md.eqVol.get(info.volatilitySurface));

        ctx.s = eqSpot.fwdPrice(dataDate);
        ctx.t = Math.max(0.0, t);
        ctx.ts = Math.max(0.0, yearFrac(dataDate, info.settleDate));
        ctx.rd = discount.spotRate(info.maturityDate);
        ctx.rebase = discount.spotRate(info.settleDate);
        // 不考虑 dividend，rf = 0
        ctx.rf = 0.0;
        ctx.f = ctx.s * Math.exp(ctx.rd * ctx.t);
        ctx.volCurve = eqVol.getVolCur(days);
        if (ctx.volCurve == null || ctx.volCurve.isEmpty()) {
            throw new IllegalArgumentException("波动率曲线为空: " + info.volatilitySurface + ", days=" + days);
        }
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireNotNull(md.eqSpot, "marketData.eqSpot");
        requireNotNull(md.eqVol, "marketData.eqVol");
        requireText(info.referenceCurve, "REFERENCE_CURVE");
        if (!md.irSpot.containsKey(info.discountCurve)) {
            throw new IllegalArgumentException("缺少贴现曲线: " + info.discountCurve);
        }
        if (!md.eqSpot.containsKey(info.referenceCurve)) {
            throw new IllegalArgumentException("缺少权益价格曲线(EQ_SPOT): " + info.referenceCurve);
        }
        if (!md.eqVol.containsKey(info.volatilitySurface)) {
            throw new IllegalArgumentException("缺少权益波动率曲面(EQ_VOL): " + info.volatilitySurface);
        }
    }

    public static class EqWeddingCakeMeasure extends OptionMeasure {
    }

    public static class EqWeddingCakeInfo extends WeddingCakeBase.WeddingCakeBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
    }
}
