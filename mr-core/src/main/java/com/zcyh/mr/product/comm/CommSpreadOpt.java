package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.SpreadOptBase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品 Spread Option 产品类。
 * 继承 SpreadOptBase，实现 Commodity 特有的市场数据获取和校验。
 */
public class CommSpreadOpt extends SpreadOptBase<CommSpreadOpt.SpreadOptInfo, CommSpreadOpt.SpreadOptMeasure> {

    public CommSpreadOpt(LocalDate dataDate, SpreadOptInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    public SpreadOptMeasure calc() {
        SpreadOptMeasure measure = super.calc();
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
        CommSpot commSpot = new CommSpot(md.commSpot.get(info.referenceCurve));
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        ctx.s = commSpot.fwdPrice(dataDate);
        ctx.f = commSpot.fwdPrice(info.maturityDate);
        ctx.rd = irSpot.spotRate(info.maturityDate);
        if (Double.isFinite(ctx.s) && ctx.s > 0.0 && Double.isFinite(ctx.f) && ctx.f > 0.0
                && Double.isFinite(t) && t > 0.0) {
            ctx.rf = -Math.log(ctx.f / ctx.s) / t + ctx.rd;
        } else {
            ctx.rf = ctx.rd;
            if (!Double.isFinite(ctx.f) || ctx.f <= 0.0) {
                ctx.f = ctx.s;
            }
        }
        ctx.cash = "CASH".equalsIgnoreCase(info.settleType);
        CommVol commVol = new CommVol(md.commVol.get(info.volatilitySurface));
        ctx.volCurve = commVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected List<String> validateSpecific() {
        List<String> errors = new ArrayList<>();
        if (info.discountCurve == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            errors.add("缺少市场数据: DISCOUNT_CURVE");
        }
        if (info.referenceCurve == null || !marketData.commSpot.containsKey(info.referenceCurve)) {
            errors.add("缺少市场数据: REFERENCE_CURVE(COMM_SPOT)");
        }
        if (info.volatilitySurface == null || !marketData.commVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(COMM_VOL)");
        }
        return errors;
    }

    public static class SpreadOptMeasure extends OptionMeasure {
    }

    public static class SpreadOptInfo extends SpreadOptBase.SpreadOptBaseInfo {
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
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
