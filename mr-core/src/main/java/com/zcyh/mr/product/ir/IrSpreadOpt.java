package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.SpreadOptBase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 利率 Spread Option 产品类。
 * 继承 SpreadOptBase，实现 IR 特有的市场数据获取和校验。
 * spot 取自历史定盘利率，远期利率通过 calFi() 计算，波动率取自利率波动率曲面。
 */
public class IrSpreadOpt extends SpreadOptBase<IrSpreadOpt.SpreadOptTradeInfo, IrSpreadOpt.SpreadOptMeasure> {

    public IrSpreadOpt(LocalDate dataDate, SpreadOptTradeInfo tradeInfo, MarketData marketData) {
        super(dataDate, tradeInfo, marketData);
    }

    @Override
    public SpreadOptMeasure calc() {
        SpreadOptMeasure measure = super.calc();
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
        IrSpot discountCurve = new IrSpot(md.irSpot.get(info.discountCurve));
        IrVol irVol = new IrVol(md.irVol.get(info.volatilitySurface));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);

        double fiData = calFi(dataDate, md);
        double fiMaturity = calFi(info.maturityDate, md);
        Double fixingToday = getFixingRate(md, dataDate);
        double diff = 0.0;
        if (fixingToday != null && Double.isFinite(fiData)) {
            diff = fixingToday - fiData;
        }

        // 统一口径：s 从价格曲线出发，再按 fixing 做可选校准
        if (Double.isFinite(fiData)) {
            ctx.s = fiData + diff;
        } else if (fixingToday != null) {
            ctx.s = fixingToday;
        } else {
            ctx.s = Double.NaN;
        }

        ctx.rd = discountCurve.spotRate(info.maturityDate);
        // 整条曲线平移：远期 = calFi(maturity) + diff
        ctx.f = Double.isFinite(fiMaturity) ? fiMaturity + diff : Double.NaN;
        if (!Double.isFinite(ctx.f) || ctx.f <= 0.0) {
            ctx.f = ctx.s;
        }
        // rf 对齐远期
        if (t > 0 && ctx.s > 0 && ctx.f > 0) {
            ctx.rf = -Math.log(ctx.f / ctx.s) / t + ctx.rd;
        } else {
            ctx.rf = ctx.rd;
        }
        ctx.cash = "CASH".equalsIgnoreCase(info.settleType);
        ctx.volCurve = irVol.getVolCur(days);
        ctx.fxToCny = fxSpot.getFxrate(info.currencyCode);
        return ctx;
    }

    @Override
    protected List<String> validateSpecific() {
        List<String> errors = new ArrayList<>();
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
        validateRateTermInputs(errors);
        return errors;
    }

    /**
     * 计算远期利率：ZERO 使用远期零息利率，PAR 使用平价互换利率。
     */
    private double calFi(LocalDate date, MarketData md) {
        String curveName = info.referenceCurve;
        if (curveName == null || !md.irSpot.containsKey(curveName)) {
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

    private void validateRateTermInputs(List<String> errors) {
        if (!hasText(info.termCode)) {
            errors.add("TERM_CODE不能为空");
        }
        if (!hasText(info.rateType)) {
            errors.add("RATE_TYPE不能为空");
            return;
        }
        String rateType = info.rateType.trim().toUpperCase();
        if (!"ZERO".equals(rateType) && !"PAR".equals(rateType)) {
            errors.add("RATE_TYPE仅支持 ZERO/PAR: " + info.rateType);
            return;
        }
        if ("PAR".equals(rateType) && !hasText(info.termFreq)) {
            errors.add("RATE_TYPE=PAR时TERM_FREQ不能为空");
        }
    }

    private Double getFixingRate(MarketData md, LocalDate date) {
        if (!hasText(info.fixingId) || md.fixingRate == null) {
            return null;
        }
        Fixing.FixingInfo fixingInfo = md.fixingRate.get(info.fixingId);
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

    public static class SpreadOptMeasure extends OptionMeasure {
    }

    public static class SpreadOptTradeInfo extends SpreadOptBase.SpreadOptBaseTradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        /** 利率模式：ZERO(远期零息) / PAR(平价互换) */
        @ProductInputField(allowedValues = {"ZERO", "PAR"}, ignoreCase = true)
        @JSONField(name = "RATE_TYPE")
        public String rateType;
        /** 利率期限代码，如 3M/1Y/10Y */
        @JSONField(name = "TERM_CODE")
        public String termCode;
        /** PAR 模式付息频率，如 3M/6M/1Y */
        @JSONField(name = "TERM_FREQ")
        public String termFreq;
    }
}
