package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.WeddingCakeBase;

import java.time.LocalDate;

public class CommWeddingCake extends WeddingCakeBase<CommWeddingCake.CommWeddingCakeInfo, CommWeddingCake.CommWeddingCakeMeasure> {

    public CommWeddingCake(LocalDate dataDate, CommWeddingCakeInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    public CommWeddingCakeMeasure calc() {
        CommWeddingCakeMeasure measure = super.calc();
        if ("SUCCESS".equalsIgnoreCase(measure.status)) {
            measure.sensitivityList = buildCmtyFrtbSensListCommon(
                    measure,
                    info.settleDate,
                    info.currencyCode,
                    info.discountCurve,
                info.referenceCurve,
                    resolveCmtyRiskFactorId(),
                    resolveCmtyRiskFactorIdVega(),
                    info.frtbCommBucket,
                    info.volatilitySurface,
                    this::calc);
        }
        return measure;
    }

    @Override
    protected CommWeddingCakeMeasure newMeasure() {
        return new CommWeddingCakeMeasure();
    }

    @Override
    protected MarketContext buildMarketContext(MarketData md, int days, double t) {
        MarketContext ctx = new MarketContext();
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        IrSpot discount = new IrSpot(md.irSpot.get(info.discountCurve));
        CommSpot commSpot = new CommSpot(md.commSpot.get(info.referenceCurve));
        CommVol commVol = new CommVol(md.commVol.get(info.volatilitySurface));

        ctx.s = commSpot.fwdPrice(dataDate);
        ctx.t = Math.max(0.0, t);
        ctx.ts = Math.max(0.0, yearFrac(dataDate, info.settleDate));
        ctx.rd = discount.spotRate(info.maturityDate);
        ctx.rebase = discount.spotRate(info.settleDate);
        ctx.f = commSpot.fwdPrice(info.maturityDate);
        if (!(ctx.f > 0.0)) {
            ctx.f = ctx.s * Math.exp(ctx.rd * ctx.t);
        }
        if (ctx.t > 0.0 && ctx.s > 0.0 && ctx.f > 0.0) {
            ctx.rf = -Math.log(ctx.f / ctx.s) / ctx.t + ctx.rd;
        } else {
            ctx.rf = ctx.rd;
        }
        ctx.volCurve = commVol.getVolCur(days);
        if (ctx.volCurve == null || ctx.volCurve.isEmpty()) {
            throw new IllegalArgumentException("波动率曲线为空: " + info.volatilitySurface + ", days=" + days);
        }
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireNotNull(md.commSpot, "marketData.commSpot");
        requireNotNull(md.commVol, "marketData.commVol");
        requireText(info.referenceCurve, "REFERENCE_CURVE");
        if (!md.irSpot.containsKey(info.discountCurve)) {
            throw new IllegalArgumentException("缺少贴现曲线: " + info.discountCurve);
        }
        if (!md.commSpot.containsKey(info.referenceCurve)) {
            throw new IllegalArgumentException("缺少商品价格曲线(COMM_SPOT): " + info.referenceCurve);
        }
        if (!md.commVol.containsKey(info.volatilitySurface)) {
            throw new IllegalArgumentException("缺少商品波动率曲面(COMM_VOL): " + info.volatilitySurface);
        }
    }

    public static class CommWeddingCakeMeasure extends OptionMeasure {
    }

    public static class CommWeddingCakeInfo extends WeddingCakeBase.WeddingCakeBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
        if (base == null || base.isEmpty()) {
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
}
