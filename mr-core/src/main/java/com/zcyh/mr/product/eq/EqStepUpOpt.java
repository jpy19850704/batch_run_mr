package com.zcyh.mr.product.eq;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.EqVol;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class EqStepUpOpt extends StepUpOptBase<EqStepUpOpt.EqStepUpTradeInfo, OptionMeasure> {

    public EqStepUpOpt(LocalDate dataDate, EqStepUpTradeInfo stepUpInfo, MarketData marketData) {
        super(dataDate, stepUpInfo, marketData);
    }

    @Override
    protected OptionMeasure newMeasure() {
        return new OptionMeasure();
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireText(stepUpInfo.referenceCurve, "REFERENCE_CURVE");
        if (!md.eqSpot.containsKey(stepUpInfo.referenceCurve)) {
            throw new IllegalArgumentException("缺少权益价格曲线: " + stepUpInfo.referenceCurve);
        }
        if (md.eqVol == null || !md.eqVol.containsKey(getVolatilitySurface())) {
            throw new IllegalArgumentException("缺少权益波动率曲面(EQ_VOL): " + getVolatilitySurface());
        }
    }

    @Override
    protected PricingContext buildPricingContext(MarketData md) {
        IrSpot irSpot = new IrSpot(md.irSpot.get(getDiscountCurve()));
        EqVol eqVol = new EqVol(md.eqVol.get(getVolatilitySurface()));
        EqSpot eqSpot = new EqSpot(md.eqSpot.get(stepUpInfo.referenceCurve));

        PricingContext ctx = new PricingContext();
        ctx.call = isCall();
        ctx.days = dayDiff(dataDate, getFixingDate());
        ctx.fixingT = ctx.days / YEAR_BASE;
        ctx.maturityT = yearFrac(dataDate, getMaturityDate());
        ctx.t = yearFrac(getStartDate(), getMaturityDate());
        ctx.s = eqSpot.fwdPrice(dataDate);
        ctx.rd = irSpot.spotRate(getFixingDate());
        ctx.f = ctx.s * Math.exp(ctx.rd * ctx.fixingT);
        ctx.rf = -Math.log(ctx.f / ctx.s) / ctx.fixingT + ctx.rd;
        ctx.rebase = irSpot.spotRate(getMaturityDate());
        ctx.k1 = ctx.call ? getUpperBarrier() : getLowerBarrier();
        ctx.k2 = ctx.call ? getLowerBarrier() : getUpperBarrier();
        ctx.rebate1 = getNotional() * (getHighRate() - getMidRate()) * ctx.t;
        ctx.rebate2 = getNotional() * (getMidRate() - getLowRate()) * ctx.t;
        ctx.rebate3 = getNotional() * getLowRate() * ctx.t;
        ctx.volCur = eqVol.getVolCur(ctx.days);
        return ctx;
    }

    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new java.util.ArrayList<>();
        list.addAll(getSensListGIRRCommon());
        String bucket = stepUpInfo.frtbEqBucket;
        if (FrtbSensitivityBuilder.warnMissingEqSensitivityInputs(stepUpMeasure, bucket)) {
            return list;
        }
        List<FrtbDependency> deltaDependencies = FrtbSensitivityBuilder.buildEqDeltaDependencies(stepUpInfo.referenceCurve, bucket);
        List<FrtbDependency> vegaDependencies = FrtbSensitivityBuilder.buildEqVegaDependencies(
                getVolatilitySurface(),
                stepUpInfo.referenceCurve,
                bucket);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildEqSensitivities(
                marketData,
                dataDate,
                getFrtbSettleDate(),
                deltaDependencies,
                vegaDependencies,
                true,
                true,
                stepUpMeasure.instrumentId,
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(stepUpMeasure.valuation, stepUpMeasure.valuationCny),
                shockedMarketData -> {
                    OptionMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                () -> middle.newSigma = true);
        list.addAll(sensitivities);
        return list;
    }

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        map.put(stepUpInfo.discountCurve, stepUpInfo.currencyCode);
        return map;
    }

    @Override
    protected boolean enableGirrDelta() {
        return true;
    }

    @Override
    protected boolean enableGirrCurvature() {
        return false;
    }

    @Override
    protected boolean enableGirrVega() {
        return false;
    }

    @Override
    protected LocalDate getFrtbSettleDate() {
        return stepUpInfo.settleDate;
    }

    @Override
    protected String getFrtbInstrumentCurrency() {
        return stepUpInfo.currencyCode;
    }

    @Override
    protected List<String> getFxRiskCurrencies() {
        List<String> list = new java.util.ArrayList<>();
        if (hasText(stepUpInfo.currencyCode)
                && !"CNY".equalsIgnoreCase(stepUpInfo.currencyCode)
                && !"CNH".equalsIgnoreCase(stepUpInfo.currencyCode)) {
            list.add(stepUpInfo.currencyCode);
        }
        return list;
    }

    @Override
    protected String getInstrumentId() {
        return stepUpInfo.instrumentId;
    }

    @Override
    protected String getProductCode() {
        return stepUpInfo.productCode;
    }

    @Override
    protected String getCallOrPut() {
        return stepUpInfo.callOrPut;
    }

    @Override
    protected String getBuyOrSell() {
        return stepUpInfo.buyOrSell;
    }

    @Override
    protected LocalDate getStartDate() {
        return stepUpInfo.startDate;
    }

    @Override
    protected LocalDate getMaturityDate() {
        return stepUpInfo.maturityDate;
    }

    @Override
    protected LocalDate getFixingDate() {
        return stepUpInfo.fixingDate;
    }

    @Override
    protected Double getNotional() {
        return stepUpInfo.notional;
    }

    @Override
    protected String getCurrencyCode() {
        return stepUpInfo.currencyCode;
    }

    @Override
    protected Double getUpperBarrier() {
        return stepUpInfo.upperBarrier;
    }

    @Override
    protected Double getLowerBarrier() {
        return stepUpInfo.lowerBarrier;
    }

    @Override
    protected Double getLowRate() {
        return stepUpInfo.lowRate;
    }

    @Override
    protected Double getMidRate() {
        return stepUpInfo.midRate;
    }

    @Override
    protected Double getHighRate() {
        return stepUpInfo.highRate;
    }

    @Override
    protected String getDiscountCurve() {
        return stepUpInfo.discountCurve;
    }

    @Override
    protected String getFixingId() {
        return stepUpInfo.fixingId;
    }

    @Override
    protected String getVolatilitySurface() {
        return stepUpInfo.volatilitySurface;
    }

    @Override
    protected Double getEps() {
        return stepUpInfo.eps;
    }

    @Override
    protected Boolean getAbsFlag() {
        return stepUpInfo.absFlag;
    }

    @Override
    protected Boolean getVvFlag() {
        return stepUpInfo.vvFlag;
    }

    @Override
    protected void setEps(Double eps) {
        stepUpInfo.eps = eps;
    }

    @Override
    protected void setAbsFlag(Boolean absFlag) {
        stepUpInfo.absFlag = absFlag;
    }

    public static class EqStepUpTradeInfo extends StepUpOptBase.StepUpBaseTradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField
        @JSONField(name = "FRTB_EQ_BUCKET")
        public String frtbEqBucket;
    }
}

