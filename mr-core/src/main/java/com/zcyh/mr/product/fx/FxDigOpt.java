package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Convert;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.option.DigOptBase;
import com.zcyh.mr.product.basic.option.DigOptUtil;
import com.zcyh.mr.product.basic.option.EurOptUtil;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 外汇标的数字期权。
 * 保留场景估值与敏感性框架。
 * FRTB 敏感性统一通过基类公共模板输出。
 */
public class FxDigOpt extends DigOptBase<FxDigOpt.FxDigOptInfo> {

    private OptionMeasure fxDigOptMeasure;
    private DigOptUtil digUtil;
    private final Middle middle = new Middle();

    public FxDigOpt(LocalDate dataDate, FxDigOptInfo info, MarketData marketData) {
        super(dataDate, info, marketData);
    }

    // ---------- 抽象方法实现 ----------

    @Override
    protected double getSpotPrice(MarketData md) {
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
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
    protected List<Map<String, Object>> getVolCur(MarketData md, int days) {
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
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(info.currencyCode);
    }

    @Override
    protected void validateSpecific(MarketData md) {
        validateRequiredInputs(md);
    }

    // ---------- FxDigOpt 特有的估值逻辑（覆写 base.calc()） ----------

    /**
     * FX 数字期权基准估值入口。覆写基类以支持场景复用和 FRTB。
     */
    @Override
    public OptionMeasure calc() {
        normalizeDefaultInputs();
        fxDigOptMeasure = super.calc();
        middle.sigma = fxDigOptMeasure.impliedVol;
        middle.newSigma = true;
        fxDigOptMeasure.sensitivityList = buildFxFrtbSensListCommon(
                fxDigOptMeasure,
                info.settleDate,
                info.underlyingCurrencyCode,
                info.baseCurrencyCode,
                info.currencyCode,
                info.baseDiscountCurve,
                info.underlyingDiscountCurve,
                info.volatilitySurface,
                this::calcInternal,
                () -> middle.newSigma = true);
        return fxDigOptMeasure;
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
        FxSpot fxSpotData = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        double s = fxSpotData.getFxrate(info.baseCurrencyCode, info.underlyingCurrencyCode);
        double k = info.strikePrice;
        double t = days / 365.0;
        double rebate = info.payoffLower;
        double fwd = Math.exp((rd - rf) * t) * s;
        boolean call = "Call".equalsIgnoreCase(info.callOrPut);
        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));
        List<Map<String, Object>> volCur = fxVol.getVolCur(days);
        EurOptUtil optUtil = new EurOptUtil(call, true, s, k, rd, rf, t, t, volCur, "black");
        double sigma = optUtil.getSigma();
        boolean vvFlag = Boolean.TRUE.equals(info.vvFlag);
        boolean applyNonNegativeFloor = vvFlag && isVvNonNegativeFloorEnabled();
        double usedSigma = middle.newSigma ? sigma : middle.sigma;
        this.digUtil = new DigOptUtil(call, true, s, k, rebate, rd, rf, rebase, t, t, volCur, usedSigma,
                "black", vvFlag);
        double baseValue = applyFloorIfNeeded(digUtil.getValue(), applyNonNegativeFloor);
        result.valuationUnit = baseValue + info.basePayoff;
        middle.newSigma = false;

        DigOptUtil bumpedUtil = new DigOptUtil(call, true, s, k, rebate,
                rd + 0.0001, rf + 0.0001, rebase + 0.0001, t, t, volCur, usedSigma,
                "black", vvFlag);
        double bumpedValue = applyFloorIfNeeded(bumpedUtil.getValue(), applyNonNegativeFloor);

        result.instrumentId = info.instrumentId;
        result.position = pos;
        result.spotPrice = s;
        result.fwdPrice = fwd;
        result.valuation = result.valuationUnit * pos;
        result.valuationCcy = info.currencyCode;
        result.valuationCny = result.valuation * fxSpotData.getFxrate(info.currencyCode);
        return result;
    }

    // ---------- 辅助方法 ----------

    private void normalizeDefaultInputs() {
        if (info.settleDate == null)
            info.settleDate = info.maturityDate;
    }

    /**
     * VV 调整开启时，允许通过配置控制是否对期权腿价格进行非负兜底。
     */
    private boolean isVvNonNegativeFloorEnabled() {
        String value = Configure.getInstance().getValue(Constants.CFG.NON_NEGATIVE_FLOOR_ENABLED);
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        return Boolean.parseBoolean(value.trim());
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
        if (info.strikePrice == null || info.strikePrice <= 0)
            throw new IllegalArgumentException("STRIKE_PRICE 必须大于 0");
        if (info.callOrPut == null || info.callOrPut.trim().isEmpty())
            throw new IllegalArgumentException("CALL_OR_PUT 不能为空");
        String cp = info.callOrPut.trim().toLowerCase(Locale.ROOT);
        if (!"call".equals(cp) && !"put".equals(cp))
            throw new IllegalArgumentException("CALL_OR_PUT 仅支持 Call/Put: " + info.callOrPut);
    }

    private Map<String, Object> buildDetailFx() {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("BASE_PAYOFF", info.basePayoff);
        detail.put("PAYOFF_LOWER", info.payoffLower);
        detail.put("STRIKE_PRICE", info.strikePrice);
        detail.put("D2", digUtil.getD2());
        return detail;
    }

    // ---------- 内部类 ----------

    public static class FxDigOptInfo extends DigOptBase.DigOptBaseInfo {
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
