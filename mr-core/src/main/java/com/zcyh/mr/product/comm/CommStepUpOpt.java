package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.structure.StepUpOptBase;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.CommVol;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommStepUpOpt extends StepUpOptBase<CommStepUpOpt.CommStepUpTradeInfo, OptionMeasure> {

    public CommStepUpOpt(LocalDate dataDate, CommStepUpTradeInfo stepUpInfo, MarketData marketData) {
        super(dataDate, stepUpInfo, marketData);
    }

    @Override
    protected OptionMeasure newMeasure() {
        return new OptionMeasure();
    }

    @Override
    protected List<FrtbSenes> getFrtbSensList() {
        List<FrtbSenes> sensList = new ArrayList<>();
        sensList.addAll(getSensListGIRRCommon());
        sensList.addAll(getSensListFXCommon());
        sensList.addAll(getSensListCMTY());
        return sensList;
    }

    @Override
    protected void validateSpecificInputs(MarketData md) {
        requireText(stepUpInfo.referenceCurve, "REFERENCE_CURVE");
        if (md.commSpot == null || !md.commSpot.containsKey(stepUpInfo.referenceCurve)) {
            throw new IllegalArgumentException("缺少商品价格曲线: " + stepUpInfo.referenceCurve);
        }
        if (md.commVol == null || !md.commVol.containsKey(getVolatilitySurface())) {
            throw new IllegalArgumentException("缺少商品波动率曲面: " + getVolatilitySurface());
        }
        resolveUnderlyingForCalc();
    }

    @Override
    protected PricingContext buildPricingContext(MarketData md) {
        IrSpot irSpot = new IrSpot(md.irSpot.get(getDiscountCurve()));
        Fixing fixing = new Fixing(md.fixingRate.get(resolveFixingKey()));
        CommSpot commSpot = new CommSpot(md.commSpot.get(stepUpInfo.referenceCurve));
        CommVol vol = new CommVol(md.commVol.get(getVolatilitySurface()));

        PricingContext ctx = new PricingContext();
        ctx.call = isCall();
        ctx.days = dayDiff(dataDate, getFixingDate());
        ctx.fixingT = ctx.days / YEAR_BASE;
        ctx.maturityT = yearFrac(dataDate, getMaturityDate());
        ctx.t = yearFrac(getStartDate(), getMaturityDate());
        ctx.s = fixing.getRate(dataDate);
        ctx.f = commSpot.fwdPrice(getFixingDate());
        ctx.rd = irSpot.spotRate(getFixingDate());
        ctx.rf = -Math.log(ctx.f / ctx.s) / ctx.fixingT + ctx.rd;
        ctx.rebase = irSpot.spotRate(getMaturityDate());
        ctx.k1 = ctx.call ? getUpperBarrier() : getLowerBarrier();
        ctx.k2 = ctx.call ? getLowerBarrier() : getUpperBarrier();
        ctx.rebate1 = getNotional() * (getHighRate() - getMidRate()) * ctx.t;
        ctx.rebate2 = getNotional() * (getMidRate() - getLowRate()) * ctx.t;
        ctx.rebate3 = getNotional() * getLowRate() * ctx.t;
        ctx.volCur = vol.getVolCur(ctx.days);
        return ctx;
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
        List<String> list = new ArrayList<>();
        if (hasText(stepUpInfo.currencyCode) && !"CNY".equalsIgnoreCase(stepUpInfo.currencyCode)) {
            list.add(stepUpInfo.currencyCode);
        }
        return list;
    }

    private List<FrtbSenes> getSensListCMTY() {
        String commBucket = hasText(stepUpInfo.frtbCommBucket) ? stepUpInfo.frtbCommBucket.trim() : null;
        String commAsset = resolveCmtyRiskFactorIdBase();
        if (FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                stepUpMeasure,
                getText(stepUpInfo.instrumentId),
                commBucket,
                commAsset)) {
            return new ArrayList<>();
        }
        List<FrtbDependency> deltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
                stepUpInfo.referenceCurve,
                resolveCmtyRiskFactorId(),
                commBucket);
        List<FrtbDependency> vegaDependencies = FrtbSensitivityBuilder.buildCmtyVegaDependencies(
                stepUpInfo.volatilitySurface,
                resolveCmtyRiskFactorIdVega(),
                commBucket);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                marketData,
                dataDate,
                stepUpInfo.settleDate,
                deltaDependencies,
                vegaDependencies,
                true,
                true,
                stepUpInfo.instrumentId,
                stepUpInfo.currencyCode,
                FRTB_ZERO_TOL,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(stepUpMeasure.valuation, stepUpMeasure.valuationCny),
                shockedMarketData -> {
                    OptionMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                () -> middle.newSigma = true);
        return sensitivities;
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
        if (!hasText(base)) {
            return null;
        }
        String location = getText(stepUpInfo.frtbCommLocation);
        if (!hasText(location)) {
            return base;
        }
        return base + "&" + location;
    }

    private String resolveCmtyRiskFactorIdBase() {
        String asset = getText(stepUpInfo.frtbCommAsset);
        if (hasText(asset)) {
            return asset;
        }
        return null;
    }

    private String resolveCmtyRiskFactorIdVega() {
        return resolveCmtyRiskFactorIdBase();
    }

    private String resolveUnderlyingForCalc() {
        if (hasText(stepUpInfo.underlyingCode)) {
            return stepUpInfo.underlyingCode.trim();
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CODE，无法进行商品交易计量");
    }

    private String getText(String value) {
        return value == null ? "" : value.trim();
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

    public static class CommStepUpTradeInfo extends StepUpOptBase.StepUpBaseTradeInfo {
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
    }
}

