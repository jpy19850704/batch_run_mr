package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.IrVol;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IrStepUpOpt extends StepUpOptBase<IrStepUpOpt.IrStepUpInfo, OptionMeasure> {

    public IrStepUpOpt(LocalDate dataDate, IrStepUpInfo stepUpInfo, MarketData marketData) {
        super(dataDate, stepUpInfo, marketData);
    }

    @Override
    protected OptionMeasure newMeasure() {
        return new OptionMeasure();
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireText(stepUpInfo.referenceCurve, "REFERENCE_CURVE");
        if (!md.irSpot.containsKey(stepUpInfo.referenceCurve)) {
            throw new IllegalArgumentException("缺少利率曲线: " + stepUpInfo.referenceCurve);
        }
        if (md.irVol == null || !md.irVol.containsKey(getVolatilitySurface())) {
            throw new IllegalArgumentException("缺少利率波动率曲面: " + getVolatilitySurface());
        }
        validateRateTermInputs();
    }

    @Override
    protected PricingContext buildPricingContext(MarketData md) {
        IrSpot irSpot = new IrSpot(md.irSpot.get(getDiscountCurve()));
        Fixing fixing = new Fixing(md.fixingRate.get(resolveFixingKey()));
        IrVol irVol = new IrVol(md.irVol.get(getVolatilitySurface()));

        PricingContext ctx = new PricingContext();
        ctx.call = isCall();
        ctx.days = dayDiff(dataDate, getFixingDate());
        ctx.fixingT = ctx.days / YEAR_BASE;
        ctx.maturityT = yearFrac(dataDate, getMaturityDate());
        ctx.t = yearFrac(getStartDate(), getMaturityDate());
        ctx.s = fixing.getRate(dataDate);
        ctx.f = calFi(md);
        ctx.rd = irSpot.spotRate(getFixingDate());
        ctx.rf = -Math.log(ctx.f / ctx.s) / ctx.fixingT + ctx.rd;
        ctx.rebase = irSpot.spotRate(getMaturityDate());
        ctx.k1 = ctx.call ? getUpperBarrier() : getLowerBarrier();
        ctx.k2 = ctx.call ? getLowerBarrier() : getUpperBarrier();
        ctx.rebate1 = getNotional() * (getHighRate() - getMidRate()) * ctx.t;
        ctx.rebate2 = getNotional() * (getMidRate() - getLowRate()) * ctx.t;
        ctx.rebate3 = getNotional() * getLowRate() * ctx.t;
        ctx.volCur = irVol.getVolCur(ctx.days);
        return ctx;
    }

    protected double calFi(MarketData marketData) {
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(stepUpInfo.referenceCurve));
        String termCode = resolveTermCode();
        // 与其他 IR 结构产品保持一致：ZERO 取远期零息，PAR 取平价互换利率。
        if ("ZERO".equalsIgnoreCase(resolveRateType())) {
            LocalDate endDate = IrSpot.parseTermCodeToDate(dataDate, termCode);
            return irSpot.fwdRate(dataDate, endDate);
        }
        return irSpot.parSwapRate(dataDate, termCode, resolveTermFreq());
    }

    protected double calS(MarketData marketData) {
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(stepUpInfo.referenceCurve));
        LocalDate endDate = IrSpot.parseTermCodeToDate(dataDate, resolveTermCode());
        return irSpot.spotRate(endDate);
    }

    @Override
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new java.util.ArrayList<>();
        list.addAll(getSensListGIRRCommon());
        list.addAll(getSensListFXCommon());
        return list;
    }

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        map.put(stepUpInfo.discountCurve, stepUpInfo.currencyCode);
        map.put(stepUpInfo.referenceCurve, stepUpInfo.currencyCode);
        return map;
    }

    @Override
    protected boolean enableGirrDelta() {
        return true;
    }

    @Override
    protected boolean enableGirrCurvature() {
        return true;
    }

    @Override
    protected boolean enableGirrVega() {
        return true;
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
    protected String getGirrVegaBucket() {
        return stepUpInfo.currencyCode;
    }

    @Override
    protected String getGirrVegaSecondaryVertex() {
        return resolveTermCode();
    }

    private String resolveTermCode() {
        return stepUpInfo.termCode.trim().toUpperCase();
    }

    private String resolveRateType() {
        return stepUpInfo.rateType.trim().toUpperCase();
    }

    private String resolveTermFreq() {
        return stepUpInfo.termFreq.trim().toUpperCase();
    }

    private void validateRateTermInputs() {
        if (!hasText(stepUpInfo.termCode)) {
            throw new IllegalArgumentException("TERM_CODE不能为空");
        }
        if (!hasText(stepUpInfo.rateType)) {
            throw new IllegalArgumentException("RATE_TYPE不能为空");
        }
        String rateType = stepUpInfo.rateType.trim().toUpperCase();
        if (!"ZERO".equals(rateType) && !"PAR".equals(rateType)) {
            throw new IllegalArgumentException("RATE_TYPE仅支持 ZERO/PAR: " + stepUpInfo.rateType);
        }
        if ("PAR".equals(rateType) && !hasText(stepUpInfo.termFreq)) {
            throw new IllegalArgumentException("RATE_TYPE=PAR时TERM_FREQ不能为空");
        }
    }

    @Override
    protected List<String> getFxRiskCurrencies() {
        List<String> list = new java.util.ArrayList<>();
        if (hasText(stepUpInfo.currencyCode) && !"CNY".equalsIgnoreCase(stepUpInfo.currencyCode)) {
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

    @Override
    protected boolean defaultAbsFlag() {
        return true;
    }

    public static class IrStepUpInfo extends StepUpOptBase.StepUpBaseInfo {
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        /** 利率模式：ZERO(远期零息) / PAR(平价互换)。 */
        @JSONField(name = "RATE_TYPE")
        public String rateType;
        /** 标的期限代码，如 3M/1Y/10Y。 */
        @JSONField(name = "TERM_CODE")
        public String termCode;
        /** PAR 模式付息频率，如 3M/6M/1Y。 */
        @JSONField(name = "TERM_FREQ")
        public String termFreq;
    }
}
