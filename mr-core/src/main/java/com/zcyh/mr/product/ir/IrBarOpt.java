package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.option.BarOptBase;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 利率标的障碍期权。
 * 使用定盘利率作为即期价格，通过曲线平移推导远期利率。
 */
public class IrBarOpt extends BarOptBase<IrBarOpt.IrBarOptInfo> {

    public IrBarOpt(LocalDate dataDate, IrBarOptInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    public OptionMeasure calc() {
        OptionMeasure measure = super.calc();
        if (!"SUCCESS".equalsIgnoreCase(measure.status)) {
            return measure;
        }
        measure.sensitivityList = buildIrFrtbSensListCommon(
                measure,
                info.settleDate,
                info.currencyCode,
                info.discountCurve,
                info.referenceCurve,
                info.volatilitySurface,
                info.termCode,
                this::calc);
        return measure;
    }

    @Override
    protected double getSpotPrice(MarketData md) {
        Fixing fixing = new Fixing(md.fixingRate.get(info.fixingId));
        return fixing.getRate(dataDate);
    }

    @Override
    protected double getFwdPrice(MarketData md, double s, double rd, double rf, double t) {
        double diff = s - calFi(dataDate);
        return calFi(info.maturityDate) + diff;
    }

    @Override
    protected double getRf(MarketData md, double s, double rd, double t) {
        double f = getFwdPrice(md, s, rd, 0, t);
        return (t > 0 && s > 0 && f > 0) ? -Math.log(f / s) / t + rd : rd;
    }

    @Override
    protected List<Map<String, Object>> getVolCur(MarketData md, int days) {
        IrVol irVol = new IrVol(md.irVol.get(info.volatilitySurface));
        return irVol.getVolCur(days);
    }

    @Override
    protected double getDiscountRate(MarketData md) {
        IrSpot discountIr = new IrSpot(md.irSpot.get(info.discountCurve));
        return discountIr.spotRate(info.maturityDate);
    }

    @Override
    protected double getRebaseRate(MarketData md) {
        return getDiscountRate(md);
    }

    @Override
    protected String getCurrencyCode() {
        return info.currencyCode;
    }

    @Override
    protected double getFxRate(MarketData md) {
        com.zcyh.mr.basic.util.Configure cfg = com.zcyh.mr.basic.util.Configure.getInstance();
        FxSpot fxSpot = new FxSpot(cfg.getValue(com.zcyh.mr.core.Constants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(info.currencyCode);
    }

    @Override
    protected void validateSpecific(MarketData md) {
        if (info.discountCurve == null || !md.irSpot.containsKey(info.discountCurve))
            throw new IllegalArgumentException("缺少折现曲线: DISCOUNT_CURVE");
        if (info.referenceCurve == null || !md.irSpot.containsKey(info.referenceCurve))
            throw new IllegalArgumentException("缺少参考曲线: REFERENCE_CURVE");
        if (info.fixingId == null || !md.fixingRate.containsKey(info.fixingId))
            throw new IllegalArgumentException("缺少历史定盘: FIXING_ID");
        if (info.volatilitySurface == null || !md.irVol.containsKey(info.volatilitySurface))
            throw new IllegalArgumentException("缺少利率波动率曲面: VOLATILITY_SURFACE");
        validateRateTermInputs();
    }

    /**
     * 计算远期利率：ZERO 使用远期零息利率，PAR 使用平价互换利率。
     */
    private double calFi(LocalDate date) {
        String curveName = info.referenceCurve;
        if (curveName == null || !marketData.irSpot.containsKey(curveName))
            return Double.NaN;
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(curveName));
        String termCode = info.termCode.trim();
        if ("ZERO".equalsIgnoreCase(info.rateType.trim())) {
            LocalDate endDate = IrSpot.parseTermCodeToDate(date, termCode);
            return irSpot.fwdRate(date, endDate);
        }
        String termFreq = info.termFreq.trim();
        return irSpot.parSwapRate(date, termCode, termFreq);
    }

    private void validateRateTermInputs() {
        if (!hasText(info.termCode)) {
            throw new IllegalArgumentException("TERM_CODE不能为空");
        }
        if (!hasText(info.rateType)) {
            throw new IllegalArgumentException("RATE_TYPE不能为空");
        }
        String rateType = info.rateType.trim().toUpperCase();
        if (!"ZERO".equals(rateType) && !"PAR".equals(rateType)) {
            throw new IllegalArgumentException("RATE_TYPE仅支持 ZERO/PAR: " + info.rateType);
        }
        if ("PAR".equals(rateType) && !hasText(info.termFreq)) {
            throw new IllegalArgumentException("RATE_TYPE=PAR时TERM_FREQ不能为空");
        }
    }

    public static class IrBarOptInfo extends BarOptBase.BarOptBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField(allowedValues = {"ZERO", "PAR"}, ignoreCase = true)
        @JSONField(name = "RATE_TYPE")
        public String rateType;
        @JSONField(name = "TERM_CODE")
        public String termCode;
        @JSONField(name = "TERM_FREQ")
        public String termFreq;
    }
}
