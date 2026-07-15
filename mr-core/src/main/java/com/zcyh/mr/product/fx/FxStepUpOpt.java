package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FxVol;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FxStepUpOpt extends StepUpOptBase<FxStepUpOpt.FxStepUpInfo, OptionMeasure> {

    public FxStepUpOpt(LocalDate dataDate, FxStepUpInfo stepUpInfo, MarketData marketData) {
        super(dataDate, stepUpInfo, marketData);
    }

    public String generateDiscountCurve(String code) {
        return "FX_IMPLIED_" + code;
    }

    @Override
    protected OptionMeasure newMeasure() {
        return new OptionMeasure();
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        String uCurrency = resolveUnderlyingCurrency();
        String bCurrency = resolveBaseCurrency();
        String uCurve = resolveUnderlyingDiscountCurve();
        String bCurve = resolveBaseDiscountCurve();
        if (!hasText(uCurrency) || uCurrency.length() != 3) {
            throw new IllegalArgumentException("UNDERLYING_CURRENCY_CODE 无效: " + uCurrency);
        }
        if (!hasText(bCurrency) || bCurrency.length() != 3) {
            throw new IllegalArgumentException("BASE_CURRENCY_CODE 无效: " + bCurrency);
        }
        if (!md.irSpot.containsKey(uCurve)) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + uCurve);
        }
        if (!md.irSpot.containsKey(bCurve)) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + bCurve);
        }
        if (md.fxVol == null || !md.fxVol.containsKey(getVolatilitySurface())) {
            throw new IllegalArgumentException("缺少外汇波动率曲面: " + getVolatilitySurface());
        }
    }

    @Override
    protected PricingContext buildPricingContext(MarketData md) {
        String uCurrency = resolveUnderlyingCurrency();
        String bCurrency = resolveBaseCurrency();
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(resolveUnderlyingDiscountCurve()));
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(resolveBaseDiscountCurve()));
        IrSpot irSpot = new IrSpot(md.irSpot.get(getDiscountCurve()));
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);

        PricingContext ctx = new PricingContext();
        ctx.call = isCall();
        ctx.days = dayDiff(dataDate, getFixingDate());
        ctx.fixingT = ctx.days / YEAR_BASE;
        ctx.maturityT = yearFrac(dataDate, getMaturityDate());
        ctx.t = yearFrac(getStartDate(), getMaturityDate());
        ctx.s = fxSpot.getFxrate(bCurrency, uCurrency);
        ctx.rd = bIrSpot.spotRate(getFixingDate());
        ctx.rf = uIrSpot.spotRate(getFixingDate());
        ctx.f = ctx.s * Math.exp((ctx.rd - ctx.rf) * ctx.fixingT);
        ctx.rebase = irSpot.spotRate(getMaturityDate());
        ctx.k1 = ctx.call ? getUpperBarrier() : getLowerBarrier();
        ctx.k2 = ctx.call ? getLowerBarrier() : getUpperBarrier();
        ctx.rebate1 = getNotional() * (getHighRate() - getMidRate()) * ctx.t;
        ctx.rebate2 = getNotional() * (getMidRate() - getLowRate()) * ctx.t;
        ctx.rebate3 = getNotional() * getLowRate() * ctx.t;
        FxVol fxVol = new FxVol(md.fxVol.get(getVolatilitySurface()));
        ctx.volCur = fxVol.getVolCur(ctx.days);
        return ctx;
    }

    private String resolveUnderlyingCurrency() {
        if (hasText(stepUpInfo.underlyingCurrencyCode)) {
            return stepUpInfo.underlyingCurrencyCode;
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CURRENCY_CODE");
    }

    private String resolveBaseCurrency() {
        if (hasText(stepUpInfo.baseCurrencyCode)) {
            return stepUpInfo.baseCurrencyCode;
        }
        throw new IllegalArgumentException("缺少BASE_CURRENCY_CODE");
    }

    private String resolveUnderlyingDiscountCurve() {
        if (hasText(stepUpInfo.underlyingDiscountCurve)) {
            return stepUpInfo.underlyingDiscountCurve;
        }
        return generateDiscountCurve(resolveUnderlyingCurrency());
    }

    private String resolveBaseDiscountCurve() {
        if (hasText(stepUpInfo.baseDiscountCurve)) {
            return stepUpInfo.baseDiscountCurve;
        }
        return generateDiscountCurve(resolveBaseCurrency());
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
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();
        list.addAll(buildFxFrtbSensListCommon(
                stepUpMeasure,
                getFrtbSettleDate(),
                resolveUnderlyingCurrency(),
                resolveBaseCurrency(),
                getCurrencyCode(),
                getVolatilitySurface(),
                this::calc));
        list.addAll(getSensListGIRRCommon());
        return list;
    }

    @Override
    protected Map<String, String> buildGirrCurveCcyMap() {
        String uCurrency = resolveUnderlyingCurrency();
        String bCurrency = resolveBaseCurrency();
        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(resolveUnderlyingDiscountCurve(), uCurrency);
        curveMap.put(resolveBaseDiscountCurve(), bCurrency);
        curveMap.put(stepUpInfo.discountCurve, stepUpInfo.currencyCode);
        return curveMap;
    }

    @Override
    protected List<String> getFxRiskCurrencies() {
        List<String> list = new ArrayList<>();
        String uCurrency = resolveUnderlyingCurrency();
        String bCurrency = resolveBaseCurrency();
        if (hasText(uCurrency) && !"CNY".equalsIgnoreCase(uCurrency)) {
            list.add(uCurrency);
        }
        if (hasText(bCurrency) && !"CNY".equalsIgnoreCase(bCurrency) && !bCurrency.equalsIgnoreCase(uCurrency)) {
            list.add(bCurrency);
        }
        return list;
    }

    @Override
    protected boolean enableFxCurvature() {
        return true;
    }

    @Override
    protected boolean enableFxVega() {
        return true;
    }

    @Override
    protected boolean enableGirrCurvature() {
        return false;
    }

    @Override
    protected String getFxVegaSurface() {
        return stepUpInfo.volatilitySurface;
    }

    @Override
    protected String getFxVegaBucketCurrency(List<String> fxRiskCurrencies) {
        return resolveUnderlyingCurrency();
    }

    @Override
    protected String getFxVegaCnyCurrency() {
        return resolveBaseCurrency();
    }

    @Override
    protected LocalDate getFrtbSettleDate() {
        return stepUpInfo.settleDate;
    }

    @Override
    protected String getFrtbInstrumentCurrency() {
        return stepUpInfo.currencyCode;
    }

    public static class FxStepUpInfo extends StepUpOptBase.StepUpBaseInfo {
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
    }
}
