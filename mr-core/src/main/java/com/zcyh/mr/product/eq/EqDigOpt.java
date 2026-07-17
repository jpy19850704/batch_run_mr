package com.zcyh.mr.product.eq;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.option.DigOptBase;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 权益标的数字期权。
 * 标的价格从 eqSpot 获取，rf = 0（不考虑股息）。
 * rebase 统一使用 discountCurve。
 */
public class EqDigOpt extends DigOptBase<EqDigOpt.EqDigOptInfo> {

    public EqDigOpt(LocalDate dataDate, EqDigOptInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    public OptionMeasure calc() {
        OptionMeasure measure = super.calc();
        measure.sensitivityList = buildEqFrtbSensListCommon(
                measure,
                info.settleDate,
                info.currencyCode,
                info.discountCurve,
                info.referenceCurve,
                info.volatilitySurface,
                resolveOptionalBucket("11", "frtbEqBucket"),
                this::calc);
        return measure;
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        EqSpot eqSpot = new EqSpot(md.eqSpot.get(info.referenceCurve));
        return eqSpot.fwdPrice(dataDate);
    }

    @Override
    protected double getFwdPrice(MarketData md, double s, double rd, double rf, double t) {
        return s * Math.exp(rd * t);
    }

    @Override
    protected double getRf(MarketData md, double s, double rd, double t) {
        return 0.0;
    }

    @Override
    protected List<Map<String, Object>> getVolCur(MarketData md, int days) {
        EqVol eqVol = new EqVol(md.eqVol.get(info.volatilitySurface));
        return eqVol.getVolCur(days);
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
        if (info.referenceCurve == null || !md.eqSpot.containsKey(info.referenceCurve))
            throw new IllegalArgumentException("缺少权益价格曲线: REFERENCE_CURVE");
        if (info.volatilitySurface == null || !md.eqVol.containsKey(info.volatilitySurface))
            throw new IllegalArgumentException("缺少波动率曲面: VOLATILITY_SURFACE");
    }

    public static class EqDigOptInfo extends DigOptBase.DigOptBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
    }
}
