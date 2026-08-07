package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.option.BarOptBase;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 商品标的障碍期权。
 * 标的价格从 commSpot 获取，持有成本隐含在远期价格中。
 */
public class CommBarOpt extends BarOptBase<CommBarOpt.CommBarOptTradeInfo> {

    public CommBarOpt(LocalDate dataDate, CommBarOptTradeInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    public OptionMeasure calc() {
        OptionMeasure measure = super.calc();
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
        return measure;
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        CommSpot commSpot = new CommSpot(md.commSpot.get(info.referenceCurve));
        return commSpot.fwdPrice(dataDate);
    }

    @Override
    protected double getFwdPrice(MarketData md, double s, double rd, double rf, double t) {
        return s * Math.exp((rd - rf) * t);
    }

    @Override
    protected double getRf(MarketData md, double s, double rd, double t) {
        CommSpot commSpot = new CommSpot(md.commSpot.get(info.referenceCurve));
        double f = commSpot.fwdPrice(info.maturityDate);
        return (t > 0 && s > 0 && f > 0) ? -Math.log(f / s) / t + rd : rd;
    }

    @Override
    protected List<VolSurfacePoint> getVolCur(MarketData md, int days) {
        CommVol commVol = new CommVol(md.commVol.get(info.volatilitySurface));
        return commVol.getVolCur(days);
    }

    @Override
    protected double getDiscountRate(MarketData md) {
        IrSpot discountIr = new IrSpot(md.irSpot.get(info.discountCurve));
        return discountIr.spotRate(info.maturityDate);
    }

    @Override
    protected double getRebaseRate(MarketData md) {
        IrSpot settleIr = new IrSpot(md.irSpot.get(info.discountCurve));
        return settleIr.spotRate(info.maturityDate);
    }

    @Override
    protected String getCurrencyCode() {
        return info.currencyCode;
    }

    @Override
    protected double getFxRate(MarketData md) {
        com.zcyh.mr.support.EngineConfiguration cfg = com.zcyh.mr.support.EngineConfiguration.getInstance();
        FxSpot fxSpot = new FxSpot(cfg.getValue(com.zcyh.mr.support.EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(info.currencyCode);
    }

    @Override
    protected void validateSpecific(MarketData md) {
        if (info.discountCurve == null || !md.irSpot.containsKey(info.discountCurve))
            throw new IllegalArgumentException("缺少折现曲线: DISCOUNT_CURVE");
        if (info.referenceCurve == null || !md.commSpot.containsKey(info.referenceCurve))
            throw new IllegalArgumentException("缺少商品价格曲线: REFERENCE_CURVE");
        if (info.volatilitySurface == null || !md.commVol.containsKey(info.volatilitySurface))
            throw new IllegalArgumentException("缺少波动率曲面: VOLATILITY_SURFACE");
    }

    public static class CommBarOptTradeInfo extends BarOptBase.BarOptBaseTradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
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
