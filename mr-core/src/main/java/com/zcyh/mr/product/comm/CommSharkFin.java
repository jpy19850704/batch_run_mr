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
import com.zcyh.mr.product.basic.structure.SharkFinBase;

import java.util.ArrayList;
import java.util.List;

public class CommSharkFin extends SharkFinBase<CommSharkFin.CommSharkFinInfo, CommSharkFin.CommSharkFinMeasure> {

    public CommSharkFin(java.time.LocalDate dataDate, CommSharkFinInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    public CommSharkFinMeasure calc() {
        CommSharkFinMeasure measure = super.calc();
        if ("SUCCESS".equalsIgnoreCase(measure.status)) {
            measure.sensitivityList = buildCmtyFrtbSensListCommon(
                    measure,
                    info.settleDate,
                    getValuationCurrency(),
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
    protected CommSharkFinMeasure newMeasure() {
        return new CommSharkFinMeasure();
    }

    @Override
    protected void postProcessOptionOutput(CommSharkFinMeasure measure) {
        measure.cashFlowList = null;
        measure.sensitivityList = null;
    }

    @Override
    protected MarketContext buildMarketContext(MarketData marketData, int days, double t) {
        MarketContext ctx = new MarketContext();

        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(info.discountCurve));
        CommSpot commSpot = new CommSpot(marketData.commSpot.get(info.referenceCurve));
        CommVol commVol = new CommVol(marketData.commVol.get(info.volatilitySurface));

        ctx.s = commSpot.fwdPrice(dataDate);
        ctx.f = commSpot.fwdPrice(info.maturityDate);
        if (ctx.f <= 0) {
            ctx.f = ctx.s;
        }
        ctx.rd = irSpot.spotRate(info.maturityDate);
        ctx.rebase = ctx.rd;
        ctx.rf = impliedRf(ctx.s, ctx.f, t, ctx.rd);
        ctx.volCurve = commVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(getValuationCurrency());
        return ctx;
    }

    @Override
    protected List<String> validateSpecificInputs(MarketData marketData) {
        ArrayList<String> errors = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.commSpot == null
                || marketData.commVol == null || marketData.fxSpot == null) {
            errors.add("marketData 缺少必要字段");
            return errors;
        }
        if (info.discountCurve == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            errors.add("缺少市场数据: DISCOUNT_CURVE");
        }
        if (info.referenceCurve == null || !marketData.commSpot.containsKey(info.referenceCurve)) {
            errors.add("缺少市场数据: REFERENCE_CURVE(COMM_SPOT)");
        }
        if (info.volatilitySurface == null || !marketData.commVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(COMM_VOL)");
        }
        if (info.currencyCode == null || info.currencyCode.trim().isEmpty()) {
            errors.add("CURRENCY_CODE 不能为空");
        }
        return errors;
    }

    public static class CommSharkFinMeasure extends OptionMeasure {
    }

    public static class CommSharkFinInfo extends SharkFinBase.SharkFinBaseInfo {
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
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
        String underlyingCode = getText(info.underlyingCode);
        if (!underlyingCode.isEmpty()) {
            return underlyingCode;
        }
        return info.referenceCurve;
    }

    private String resolveCmtyRiskFactorIdVega() {
        return resolveCmtyRiskFactorIdBase();
    }

    private String getText(String value) {
        return value == null ? "" : value.trim();
    }
}
