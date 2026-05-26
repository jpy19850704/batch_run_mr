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
import com.zcyh.mr.product.basic.structure.SharkFinBase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class IrSharkFin extends SharkFinBase<IrSharkFin.IrSharkFinInfo, IrSharkFin.IrSharkFinMeasure> {

    public IrSharkFin(java.time.LocalDate dataDate, IrSharkFinInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    public IrSharkFinMeasure calc() {
        IrSharkFinMeasure measure = super.calc();
        if (!"SUCCESS".equalsIgnoreCase(measure.status)) {
            return measure;
        }
        measure.sensitivityList = buildIrFrtbSensListCommon(
                measure,
                info.settleDate,
                getValuationCurrency(),
                info.discountCurve,
                info.referenceCurve,
                info.volatilitySurface,
                info.termCode,
                this::calc);
        return measure;
    }

    @Override
    protected IrSharkFinMeasure newMeasure() {
        return new IrSharkFinMeasure();
    }

    @Override
    protected void postProcessOptionOutput(IrSharkFinMeasure measure) {
        measure.cashFlowList = null;
        measure.sensitivityList = null;
    }

    @Override
    protected MarketContext buildMarketContext(MarketData marketData, int days, double t) {
        MarketContext ctx = new MarketContext();

        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        IrSpot discountCurve = new IrSpot(marketData.irSpot.get(info.discountCurve));
        IrVol irVol = new IrVol(marketData.irVol.get(info.volatilitySurface));

        ctx.rd = discountCurve.spotRate(info.maturityDate);
        ctx.rebase = ctx.rd;

        double fiData = calFi(dataDate, marketData);
        double fiMaturity = calFi(info.maturityDate, marketData);
        Double fixingToday = getFixingRate(marketData, dataDate);
        double diff = 0.0;
        if (fixingToday != null && Double.isFinite(fiData)) {
            diff = fixingToday - fiData;
        }

        if (Double.isFinite(fiData)) {
            ctx.s = fiData + diff;
        } else if (fixingToday != null) {
            ctx.s = fixingToday;
        } else {
            ctx.s = 0.0;
        }

        if (Double.isFinite(fiMaturity)) {
            ctx.f = fiMaturity + diff;
        }
        if (!(ctx.f > 0.0)) {
            ctx.f = ctx.s * Math.exp(ctx.rebase * t);
        }
        ctx.rf = impliedRf(ctx.s, ctx.f, t, ctx.rd);

        ctx.volCurve = irVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(getValuationCurrency());
        return ctx;
    }

    @Override
    protected List<String> validateSpecificInputs(MarketData marketData) {
        ArrayList<String> errors = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.irVol == null || marketData.fxSpot == null) {
            errors.add("marketData 缺少必要字段");
            return errors;
        }
        if (info.discountCurve == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            errors.add("缺少市场数据: DISCOUNT_CURVE");
        }
        if (info.volatilitySurface == null || !marketData.irVol.containsKey(info.volatilitySurface)) {
            errors.add("缺少市场数据: VOLATILITY_SURFACE(IR_VOL)");
        }
        if (!hasText(info.referenceCurve)) {
            errors.add("缺少市场数据: REFERENCE_CURVE(IR_SPOT)");
        } else if (!marketData.irSpot.containsKey(info.referenceCurve)) {
            errors.add("缺少市场数据: REFERENCE_CURVE(IR_SPOT)");
        }
        if (hasText(info.fixingId) && (marketData.fixingRate == null || !marketData.fixingRate.containsKey(info.fixingId))) {
            errors.add("缺少市场数据: FIXING_ID");
        }
        if (info.currencyCode == null || info.currencyCode.trim().isEmpty()) {
            errors.add("CURRENCY_CODE 不能为空");
        }
        return errors;
    }

    /**
     * 与 IrRange 保持一致：ZERO 使用远期零息利率，PAR 使用平价互换利率。
     */
    private double calFi(LocalDate date, MarketData marketData) {
        String curveName = info.referenceCurve;
        if (curveName == null || !marketData.irSpot.containsKey(curveName)) {
            return Double.NaN;
        }
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(curveName));
        String termCode = textOrDefault(info.termCode, "10Y");
        if ("ZERO".equalsIgnoreCase(textOrDefault(info.rateType, "PAR"))) {
            LocalDate endDate = IrSpot.parseTermCodeToDate(date, termCode);
            return irSpot.fwdRate(date, endDate);
        }
        String termFreq = textOrDefault(info.termFreq, "1Y");
        return irSpot.parSwapRate(date, termCode, termFreq);
    }

    private static String textOrDefault(String text, String dft) {
        if (text == null || text.trim().isEmpty()) {
            return dft;
        }
        return text.trim();
    }

    private Double getFixingRate(MarketData marketData, LocalDate date) {
        if (!hasText(info.fixingId) || marketData.fixingRate == null) {
            return null;
        }
        Fixing.FixingInfo fixingInfo = marketData.fixingRate.get(info.fixingId);
        if (fixingInfo == null) {
            return null;
        }
        try {
            return new Fixing(fixingInfo).getRate(date);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    public static class IrSharkFinMeasure extends OptionMeasure {
    }

    public static class IrSharkFinInfo extends SharkFinBase.SharkFinBaseInfo {
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        /** 利率模式：ZERO(远期零息) / PAR(平价互换)，默认 PAR */
        @JSONField(name = "RATE_TYPE")
        public String rateType;
        /** 利率期限代码，如 3M/1Y/10Y，默认 10Y */
        @JSONField(name = "TERM_CODE")
        public String termCode;
        /** PAR 模式付息频率，如 3M/6M/1Y，默认 1Y */
        @JSONField(name = "TERM_FREQ")
        public String termFreq;
    }
}
