package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.WeddingCakeBase;

import java.time.LocalDate;

public class IrWeddingCake
        extends WeddingCakeBase<IrWeddingCake.IrWeddingCakeInfo, IrWeddingCake.IrWeddingCakeMeasure> {

    public IrWeddingCake(LocalDate dataDate, IrWeddingCakeInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    @Override
    public IrWeddingCakeMeasure calc() {
        IrWeddingCakeMeasure measure = super.calc();
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
    protected IrWeddingCakeMeasure newMeasure() {
        return new IrWeddingCakeMeasure();
    }

    @Override
    protected MarketContext buildMarketContext(MarketData md, int days, double t) {
        MarketContext ctx = new MarketContext();
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        IrSpot discount = new IrSpot(md.irSpot.get(info.discountCurve));
        IrVol irVol = new IrVol(md.irVol.get(info.volatilitySurface));
        Fixing fixing = new Fixing(md.fixingRate.get(info.fixingId));

        ctx.t = Math.max(0.0, t);
        ctx.ts = Math.max(0.0, yearFrac(dataDate, info.settleDate));
        ctx.s = fixing.getRate(dataDate);
        ctx.rd = discount.spotRate(info.maturityDate);
        ctx.rebase = discount.spotRate(info.settleDate);

        double fiData = calFi(dataDate, md);
        double fiMaturity = calFi(info.maturityDate, md);
        if (Double.isFinite(fiData) && Double.isFinite(fiMaturity)) {
            double diff = ctx.s - fiData;
            ctx.f = fiMaturity + diff;
        }
        if (!(ctx.f > 0.0)) {
            ctx.f = ctx.s;
        }
        if (ctx.t > 0.0 && ctx.s > 0.0 && ctx.f > 0.0) {
            ctx.rf = -Math.log(ctx.f / ctx.s) / ctx.t + ctx.rd;
        } else {
            ctx.rf = ctx.rd;
        }
        ctx.volCurve = irVol.getVolCur(days);
        if (ctx.volCurve == null || ctx.volCurve.isEmpty()) {
            throw new IllegalArgumentException("波动率曲线为空: " + info.volatilitySurface + ", days=" + days);
        }
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireNotNull(md.irVol, "marketData.irVol");
        if (!md.irSpot.containsKey(info.discountCurve)) {
            throw new IllegalArgumentException("缺少贴现曲线: " + info.discountCurve);
        }
        if (!md.irVol.containsKey(info.volatilitySurface)) {
            throw new IllegalArgumentException("缺少利率波动率曲面(IR_VOL): " + info.volatilitySurface);
        }
        if (!hasText(info.referenceCurve)) {
            throw new IllegalArgumentException("缺少利率曲线: REFERENCE_CURVE");
        }
        if (!md.irSpot.containsKey(info.referenceCurve)) {
            throw new IllegalArgumentException("缺少利率曲线: " + info.referenceCurve);
        }
        validateRateTermInputs();
    }

    private double calFi(LocalDate date, MarketData md) {
        String curveName = info.referenceCurve;
        if (!hasText(curveName) || !md.irSpot.containsKey(curveName)) {
            return Double.NaN;
        }
        IrSpot irSpot = new IrSpot(md.irSpot.get(curveName));
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

    public static class IrWeddingCakeMeasure extends OptionMeasure {
    }

    public static class IrWeddingCakeInfo extends WeddingCakeBase.WeddingCakeBaseInfo {
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "RATE_TYPE")
        public String rateType;
        @JSONField(name = "TERM_CODE")
        public String termCode;
        @JSONField(name = "TERM_FREQ")
        public String termFreq;
    }
}
