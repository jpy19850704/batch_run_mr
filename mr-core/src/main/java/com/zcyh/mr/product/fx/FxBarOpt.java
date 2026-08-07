package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.Convert;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.option.BarOptBase;
import com.zcyh.mr.product.basic.option.BarOptUtil;
import com.zcyh.mr.product.basic.option.EurOptUtil;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 外汇标的障碍期权。
 * 保留场景估值与敏感性框架。
 * FRTB 敏感性统一通过基类公共模板输出。
 */
public class FxBarOpt extends BarOptBase<FxBarOpt.FxBarOptTradeInfo> {

    private OptionMeasure fxBarOptMeasure;
    private BarOptUtil barUtil;
    private final Middle middle = new Middle();

    public FxBarOpt(LocalDate dataDate, FxBarOptTradeInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    // ---------- 抽象方法实现（供 base.calc() 使用，但 FxBarOpt 覆写了 calc()） ----------

    @Override
    protected double getSpotPrice(MarketData md) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(info.baseCurrencyCode, info.underlyingCurrencyCode);
    }

    @Override
    protected double getFwdPrice(MarketData md, double s, double rd, double rf, double t) {
        return Math.exp((rd - rf) * t) * s;
    }

    @Override
    protected double getRf(MarketData md, double s, double rd, double t) {
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(info.underlyingDiscountCurve));
        return uIrSpot.spotRate(info.maturityDate);
    }

    @Override
    protected List<VolSurfacePoint> getVolCur(MarketData md, int days) {
        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));
        return fxVol.getVolCur(days);
    }

    @Override
    protected double getDiscountRate(MarketData md) {
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(info.baseDiscountCurve));
        return bIrSpot.spotRate(info.maturityDate);
    }

    @Override
    protected double getRebaseRate(MarketData md) {
        IrSpot dIrSpot = new IrSpot(md.irSpot.get(info.discountCurve));
        return dIrSpot.spotRate(info.maturityDate);
    }

    @Override
    protected String getCurrencyCode() {
        return info.currencyCode;
    }

    @Override
    protected double getFxRate(MarketData md) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(info.currencyCode);
    }

    @Override
    protected void validateSpecific(MarketData md) {
        validateRequiredInputs(md);
    }

    // ---------- FxBarOpt 特有的估值逻辑（覆写 base.calc()） ----------

    /**
     * FX 障碍期权基准估值入口。覆写基类以支持场景复用和 FRTB。
     */
    @Override
    public OptionMeasure calc() {
        normalizeDefaultInputs();
        fxBarOptMeasure = super.calc();
        middle.sigma = fxBarOptMeasure.impliedVol;
        middle.newSigma = true;
        fxBarOptMeasure.sensitivityList = buildFxFrtbSensListCommon(
                fxBarOptMeasure,
                info.settleDate,
                info.underlyingCurrencyCode,
                info.baseCurrencyCode,
                info.currencyCode,
                info.baseDiscountCurve,
                info.underlyingDiscountCurve,
                info.volatilitySurface,
                this::calcInternal,
                () -> middle.newSigma = true);
        return fxBarOptMeasure;
    }

    /**
     * 场景估值方法：使用传入的市场数据重新估值。
     */
    public OptionMeasure calcInternal(MarketData md) {
        normalizeDefaultInputs();
        validateRequiredInputs(md);
        OptionMeasure result = new OptionMeasure();
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(info.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(info.baseDiscountCurve));
        IrSpot dIrSpot = new IrSpot(md.irSpot.get(info.discountCurve));
        double rd = bIrSpot.spotRate(info.maturityDate);
        double rf = uIrSpot.spotRate(info.maturityDate);
        double rebase = dIrSpot.spotRate(info.maturityDate);
        int days = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        FxSpot fxSpotData = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        double s = fxSpotData.getFxrate(info.baseCurrencyCode, info.underlyingCurrencyCode);
        double l = info.downBarrierPrice == null ? Double.NaN : info.downBarrierPrice;
        double u = info.upBarrierPrice == null ? Double.NaN : info.upBarrierPrice;
        String barrierDirection = resolveBarrierDirection();
        double h;
        if (isDoubleBarrier()) {
            h = Double.NaN;
        } else {
            h = "Down".equalsIgnoreCase(barrierDirection) ? l : u;
        }
        boolean knockout = Boolean.TRUE.equals(info.knockOutFlag);
        boolean barrierHit = Boolean.TRUE.equals(info.touchBeforeFlag);
        double t = days / 365.0;
        double rebate = info.payoffLower;
        double settleToCny = fxSpotData.getFxrate(info.currencyCode);
        double fwd = Math.exp((rd - rf) * t) * s;
        String type = resolveBarrierType();
        double k = "Single_Barrier".equalsIgnoreCase(type) ? h : fwd;

        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));
        List<VolSurfacePoint> volCur = fxVol.getVolCur(days);
        double sigma;
        if (isDoubleBarrier()) {
            // 双障碍与主流程统一：直接取 Delta=0.5 的 ATM 波动率，不走 goalSeek。
            sigma = interpolateAtmVol(volCur);
        } else {
            EurOptUtil optUtil = new EurOptUtil(true, true, s, k, rd, rf, t, t, volCur, "black");
            sigma = optUtil.getSigma();
        }
        boolean vvFlag = Boolean.TRUE.equals(info.vvFlag);
        boolean applyNonNegativeFloor = vvFlag && isVvNonNegativeFloorEnabled();
        barUtil = new BarOptUtil(s, rebate, h, l, u, rd, rf, rebase, sigma, t, barrierDirection, knockout, barrierHit,
                type);
        double usedSigma = middle.newSigma ? sigma : middle.sigma;
        double baseValue = middle.newSigma ? barUtil.getValue() : barUtil.getValue(usedSigma);
        double valueUnit = baseValue;
        if (vvFlag) {
            double noTouchProb = barUtil.noTouchProb();
            valueUnit += BarOptUtil.computeScaledVvAdjustment(
                    s, k, h, l, u, rebate, rd, rf, rebase, usedSigma, t,
                    barrierDirection, knockout, barrierHit, type, volCur, isDoubleBarrier(), noTouchProb);
        }
        valueUnit = applyFloorIfNeeded(valueUnit, applyNonNegativeFloor);
        result.valuationUnit = valueUnit + info.basePayoff;
        middle.newSigma = false;

        result.instrumentId = info.instrumentId;
        result.position = pos;
        result.spotPrice = s;
        result.fwdPrice = fwd;
        result.valuation = result.valuationUnit * pos;
        result.valuationCcy = info.currencyCode;
        result.valuationCny = result.valuation * settleToCny;
        return result;
    }

    // ---------- 辅助方法 ----------

    private void normalizeDefaultInputs() {
        if (info.settleDate == null) {
            info.settleDate = info.maturityDate;
        }
    }

    /**
     * VV 调整开启时，允许通过配置控制是否对期权腿价格进行非负兜底。
     */
    private boolean isVvNonNegativeFloorEnabled() {
        return EngineConfiguration.getInstance()
                .getRequiredBoolean(EngineConstants.CFG.VV_NON_NEGATIVE_FLOOR_ENABLED);
    }

    /**
     * 非负兜底：避免 VV 调整在极端条件下把期权腿价格压到负值。
     */
    private double applyFloorIfNeeded(double value, boolean enabled) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (!enabled) {
            return value;
        }
        return Math.max(0.0, value);
    }

    private void validateRequiredInputs(MarketData md) {
        if (md == null || md.irSpot == null || md.fxVol == null || md.fxSpot == null)
            throw new IllegalArgumentException("marketData 缺少必要字段");
        if (info.instrumentId == null || info.instrumentId.trim().isEmpty())
            throw new IllegalArgumentException("INSTRUMENT_ID 不能为空");
        if (info.underlyingCurrencyCode == null || info.underlyingCurrencyCode.trim().isEmpty())
            throw new IllegalArgumentException("UNDERLYING_CURRENCY_CODE 不能为空");
        if (info.baseCurrencyCode == null || info.baseCurrencyCode.trim().isEmpty())
            throw new IllegalArgumentException("BASE_CURRENCY_CODE 不能为空");
        if (info.baseDiscountCurve == null || !md.irSpot.containsKey(info.baseDiscountCurve))
            throw new IllegalArgumentException("缺少基础市场曲线: " + info.baseDiscountCurve);
        if (info.underlyingDiscountCurve == null || !md.irSpot.containsKey(info.underlyingDiscountCurve))
            throw new IllegalArgumentException("缺少基础市场曲线: " + info.underlyingDiscountCurve);
        if (info.discountCurve == null || !md.irSpot.containsKey(info.discountCurve))
            throw new IllegalArgumentException("缺少基础市场曲线: " + info.discountCurve);
        if (info.volatilitySurface == null || !md.fxVol.containsKey(info.volatilitySurface))
            throw new IllegalArgumentException("缺少波动率曲面: " + info.volatilitySurface);
        if (info.maturityDate == null || !info.maturityDate.isAfter(dataDate))
            throw new IllegalArgumentException("MATURITY_DATE 必须晚于 DATA_DATE");
    }

    public static class FxBarOptTradeInfo extends BarOptBase.BarOptBaseTradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
    }

    private final class Middle {
        public double sigma = 0.0;
        public boolean newSigma = true;
    }
}

