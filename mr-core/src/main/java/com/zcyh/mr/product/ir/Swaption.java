package com.zcyh.mr.product.ir;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;
import com.zcyh.mr.product.basic.option.EurOptUtil;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * @author xujg
 * @date 2024-09-24 09:53
 */
public class Swaption {

    private static final double EPS = 1e-12;
    private static final double VEGA_SHIFT = 0.001;

    LinkedList<StructuredCashflow.Cashflow> cashflowList;        /*现金流结果 remark:从债券中直接获取，getCashflowList*/
    HashMap<LocalDate, LinkedList<StructuredCashflow.ResetDateInfo>> resetDateInfoMap;

    /*内部公共变量*/
    private LocalDate dataDate;
    private MarketData marketData;
    private Calendar calendar;
    private StructuredCashflow bond;                                                                   /*bond类*/
    private Swaption.SwaptionTradeInfo swaptionInfo;                                          /*入参交易实体类*/
    private Swaption.SwaptionMeasure swaptionMeasure = new Swaption.SwaptionMeasure();   /*返回结果类*/

    public Swaption(LocalDate dataDate, Swaption.SwaptionTradeInfo tradeInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.swaptionInfo = tradeInfo;
        this.marketData = marketData;
        this.calendar = calendar;
        this.cashflowList = new LinkedList<>();
    }

    public SwaptionMeasure calc() {
        validateInputs(marketData);
        if (isExpired()) {
            swaptionMeasure = buildMaturedMeasure();
            return swaptionMeasure;
        }
        swaptionMeasure = this.swaptionValue();
        double shift = 0.0001;
        // 利率曲线 +1bp / -1bp：PV01 与 Gamma
        MarketData upMarketData = buildShiftedIrMarketData(marketData, shift);
        SwaptionMeasure upMeasure = calc(upMarketData);


        MarketData downMarketData = buildShiftedIrMarketData(marketData, -shift);
        SwaptionMeasure downMeasure = calc(downMarketData);
        swaptionMeasure.pv01 = upMeasure.valuation - swaptionMeasure.valuation;
        swaptionMeasure.gamma = (upMeasure.valuation - 2 * swaptionMeasure.valuation + downMeasure.valuation)
                / (shift * shift);

        // 波动率绝对上移0.1个百分点，Vega按1个波动率百分点输出
        MarketData vegaMd = isExerciseDate() ? null : buildShiftedIrVolMarketData(marketData, VEGA_SHIFT);
        if (vegaMd != null) {
            SwaptionMeasure vegaMeasure = calc(vegaMd);
            swaptionMeasure.vega = Math.abs(VEGA_SHIFT) < EPS
                    ? 0.0
                    : (vegaMeasure.valuation - swaptionMeasure.valuation) / (VEGA_SHIFT * 100.0);
        } else {
            swaptionMeasure.vega = 0.0;
        }
        swaptionMeasure.status = "SUCCESS";
        swaptionMeasure.logs = new ArrayList<>();
        getFrtb();

        return swaptionMeasure;
    }

    public SwaptionMeasure calc(MarketData marketData) {
        validateInputs(marketData);
        if (isExpired()) {
            return buildMaturedMeasure();
        }
        MarketData oldMarketData = this.marketData;
        this.marketData = marketData;
        try {
            return swaptionValue();
        } finally {
            this.marketData = oldMarketData;
        }
    }

    public SwaptionMeasure swaptionValue() {
        validateInputs(marketData);
        StructuredCashflow.ScfInfo scfInfo = new StructuredCashflow.ScfInfo ();
        scfInfo.issueDate = swaptionInfo.underlyingStartDate;
        scfInfo.maturityDate = swaptionInfo.underlyingMaturityDate;
        scfInfo.currencyCode = swaptionInfo.currencyCode;
        scfInfo.couponProrated = true;
        scfInfo.payFreq = swaptionInfo.underlyingFreq;
        scfInfo.discountCurve = swaptionInfo.discountCurve;
        double signedNotional = getSignedNotional();
        scfInfo.notional = signedNotional;
        scfInfo.notionalFlag = "none";
        scfInfo.settleCalendar = swaptionInfo.underlyingSettleCalendar;
        scfInfo.settleRule = swaptionInfo.underlyingSettleRule;
        scfInfo.settleDayoff = swaptionInfo.underlyingSettleDayoff;
        scfInfo.fixingFreq = swaptionInfo.fixingFreq;
        scfInfo.referenceCurve = swaptionInfo.referenceCurve;
        scfInfo.fixingCalendar = swaptionInfo.fixingCalendar;
        scfInfo.spread = 0.0;
        scfInfo.interestType = "Fixed";
        scfInfo.interestRate = swaptionInfo.fixedRate;
        scfInfo.dayCountBasis = swaptionInfo.fixedDayCountBasis;

        /* RESET_FREQ 这个字段都没有但是bond不传报错*/
        scfInfo.resetFreq = swaptionInfo.fixingFreq;
        scfInfo.resetDayoff = swaptionInfo.fixingDayoff;
        scfInfo.resetRule = swaptionInfo.fixingRule;

        StructuredCashflow fixedBond = new StructuredCashflow(dataDate,scfInfo,marketData,calendar);/*固定端*/
        fixedBond.calc();
        LinkedList<StructuredCashflow.Cashflow> cf = fixedBond.getCashflowList();

        IrSpot discountSpot = new IrSpot(marketData.irSpot.get(swaptionInfo.discountCurve));
        double dfToSwapStart = discountSpot.fwdDiscount(dataDate, swaptionInfo.underlyingStartDate);
        double annuityAtSwapStart = 0.0;
        double fixedLegPvAtSwapStart = 0.0;
        for (StructuredCashflow.Cashflow cashFlow : cf) {
            double dfAtSwapStart = Math.abs(dfToSwapStart) < EPS ? 0.0 : cashFlow.discoutFactor / dfToSwapStart;
            annuityAtSwapStart += dfAtSwapStart * cashFlow.timeFactor * signedNotional;
            fixedLegPvAtSwapStart += dfAtSwapStart * cashFlow.cf;
        }
        LinkedList<StructuredCashflow.Cashflow> cf2 = new LinkedList<>();
        double floatingLegPvAtSwapStart;
        double yu;
        if (isExerciseDate()) {
            yu = resolveExerciseFixingRate(marketData);
            floatingLegPvAtSwapStart = yu * annuityAtSwapStart;
        } else {
            scfInfo.interestType = "Floating";
            StructuredCashflow floatingBond = new StructuredCashflow(dataDate,scfInfo,marketData,calendar); /*浮动端*/
            floatingBond.calc();
            cf2 = floatingBond.getCashflowList();
            floatingLegPvAtSwapStart = 0.0;
            for (StructuredCashflow.Cashflow cashFlow : cf2) {
                double dfAtSwapStart = Math.abs(dfToSwapStart) < EPS
                        ? 0.0 : cashFlow.discoutFactor / dfToSwapStart;
                floatingLegPvAtSwapStart += dfAtSwapStart * cashFlow.cf;
            }
            yu = Math.abs(annuityAtSwapStart) < EPS
                    ? 0.0 : floatingLegPvAtSwapStart / annuityAtSwapStart;
        }
        double timeFactorDCB = CurveFunc.timeFactor(dataDate,swaptionInfo.maturityDate,
                swaptionInfo.fixedDayCountBasis);   /*python中bondCal.timeFactorDCB函数*/
        int optionDays = Math.max(0, (int) ChronoUnit.DAYS.between(dataDate, swaptionInfo.maturityDate));
        int underlyingDays = Math.max(0,
                (int) ChronoUnit.DAYS.between(swaptionInfo.underlyingStartDate, swaptionInfo.underlyingMaturityDate));
        double vol = isExerciseDate() ? 0.0 : resolveVolatility(optionDays, underlyingDays);
        String valuationModel = resolveValuationModel();

        double rate = rateFact(yu, swaptionInfo.fixedRate, vol, Math.max(0.0, timeFactorDCB), valuationModel);
        double valuation = rate * annuityAtSwapStart * dfToSwapStart;
        SwaptionMeasure measure = new SwaptionMeasure();
        measure.valuation = Double.isFinite(valuation) ? valuation : 0.0;
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        measure.valuationCny = measure.valuation * fxSpot.getFxrate(swaptionInfo.currencyCode);
        measure.instrumentId = swaptionInfo.instrumentId;
        measure.productCode = swaptionInfo.productCode;
        measure.dataDate = dataDate;
        measure.valuationCcy = swaptionInfo.currencyCode;
        measure.position = signedNotional;
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        measure.cashFlowList = buildFloatingCashFlowList(cf2, marketData);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fixed_pv", fixedLegPvAtSwapStart);
        detail.put("float_pv", floatingLegPvAtSwapStart);
        detail.put("settle_type", resolveSettleType());
        if (isExerciseDate()) {
            detail.put("fixing_rate", yu);
            detail.put("exercise_date", swaptionInfo.maturityDate);
        }
        measure.detail = detail;
        return measure;
    }

    /**
     * 利率因子
     * @date 2024-09-29 10:24:610
     * @author xujg
     */
    private double rateFact(double yu, double k, double vol, double maturityT, String valuationModel) {
        if (vol <= EPS || maturityT <= EPS) {
            return isCallOption() ? Math.max(yu - k, 0.0) : Math.max(k - yu, 0.0);
        }
        if ("black".equals(valuationModel) && (yu <= EPS || k <= EPS)) {
            return isCallOption() ? Math.max(yu - k, 0.0) : Math.max(k - yu, 0.0);
        }
        return EurOptUtil.priceByModel(isCallOption(), yu, k, vol, maturityT, valuationModel);
    }

    private String resolveValuationModel() {
        String model = swaptionInfo.valuationModel;
        if (StringUtils.isBlank(model)) {
            return "BACHELIER";
        }
        String normalized = model.trim().toUpperCase(Locale.ROOT);
        if ("BACHELIER".equals(normalized) || "NORMAL".equals(normalized)) {
            return "bachelier";
        }
        if ("BLACK76".equals(normalized) || "BLACK".equals(normalized) || "LOGNORMAL".equals(normalized)) {
            return "black";
        }
        throw new IllegalArgumentException("VALUATION_MODEL不支持: " + model + " (仅支持 BACHELIER/BLACK76)");
    }

    private String resolveSettleType() {
        String settleType = StringUtils.defaultIfBlank(swaptionInfo.settleType, "PHYSICAL");
        if (!"PHYSICAL".equalsIgnoreCase(settleType)) {
            throw new IllegalArgumentException("SETTLE_TYPE 当前仅支持 PHYSICAL: " + settleType);
        }
        return "PHYSICAL";
    }

    public void getFrtb() {
        List<FrtbSenes> list = new ArrayList<>();
        HashMap<String,String> map  = new HashMap<>();
        map.put(swaptionInfo.discountCurve, swaptionInfo.currencyCode);
        if (!isExerciseDate()) {
            map.put(swaptionInfo.referenceCurve, swaptionInfo.currencyCode);
        }
        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(map);
        List<FrtbDependency> girrVegaDependencies = isExerciseDate()
                ? Collections.emptyList()
                : FrtbSensitivityBuilder.buildGirrVegaDependencies(
                        swaptionInfo.volatilitySurface,
                        swaptionInfo.currencyCode,
                        resolveGirrVegaSecondaryVertex());
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                swaptionInfo.maturityDate,
                girrDeltaDependencies,
                girrVegaDependencies,
                true,
                true,
                swaptionInfo.instrumentId,
                swaptionInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(swaptionMeasure.valuation, swaptionMeasure.valuationCny),
                shockedMarketData -> {
                    SwaptionMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                Collections.singletonList(swaptionInfo.currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                swaptionInfo.maturityDate,
                fxDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                swaptionMeasure.instrumentId,
                swaptionInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(swaptionMeasure.valuation, swaptionMeasure.valuationCny),
                shockedMarketData -> {
                    SwaptionMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                });
        list.addAll(fxSensitivities);

        list.removeIf(item -> Math.abs(item.sensitivityValInstCurr) < 1e-12
                && Math.abs(item.sensitivityValInstCurrCny) < 1e-12);/*移除敏度结果为0的元素*/
        swaptionMeasure.sensitivityList = list;
    }

    private boolean isCallOption() {
        if (StringUtils.isBlank(swaptionInfo.callOrPut)) {
            throw new IllegalArgumentException("CALL_OR_PUT为空: instrumentId=" + swaptionInfo.instrumentId);
        }
        if ("CALL".equalsIgnoreCase(swaptionInfo.callOrPut)) {
            return true;
        }
        if ("PUT".equalsIgnoreCase(swaptionInfo.callOrPut)) {
            return false;
        }
        throw new IllegalArgumentException("CALL_OR_PUT取值非法(仅支持CALL/PUT): " + swaptionInfo.callOrPut);
    }

    private double getSignedNotional() {
        if (swaptionInfo.notional == null) {
            throw new IllegalArgumentException("名义本金为空: instrumentId=" + swaptionInfo.instrumentId);
        }
        return "S".equalsIgnoreCase(swaptionInfo.buyOrSell) ? -swaptionInfo.notional : swaptionInfo.notional;
    }

    private double resolveVolatility(int optionDays, int underlyingDays) {
        IrVol.IrVolInfo volInfo = marketData.irVol == null ? null : marketData.irVol.get(swaptionInfo.volatilitySurface);
        if (volInfo == null || volInfo.curveData == null || volInfo.curveData.isEmpty()) {
            throw new IllegalArgumentException("波动率曲面不存在或为空: " + swaptionInfo.volatilitySurface);
        }
        IrVol irVol = new IrVol(volInfo);
        List<VolSurfacePoint> volSlice = irVol.getVolCur(optionDays);
        if (volSlice == null || volSlice.isEmpty()) {
            throw new IllegalArgumentException("波动率切片为空: optionDays=" + optionDays);
        }
        double vol = irVol.underlyingTerm(underlyingDays, volSlice);
        if (!Double.isFinite(vol) || vol < 0) {
            throw new IllegalArgumentException("波动率插值结果非法: " + vol);
        }
        return vol;
    }

    /**
     * GIRR Vega 第二维统一使用标的利率期限，优先 FIXING_FREQ，缺失时回退 UNDERLYING_FREQ。
     */
    private String resolveGirrVegaSecondaryVertex() {
        if (StringUtils.isNotBlank(swaptionInfo.fixingFreq)) {
            return swaptionInfo.fixingFreq.trim();
        }
        if (StringUtils.isNotBlank(swaptionInfo.underlyingFreq)) {
            return swaptionInfo.underlyingFreq.trim();
        }
        return null;
    }

    private boolean isExerciseDate() {
        return dataDate.equals(swaptionInfo.maturityDate);
    }

    private boolean isExpired() {
        return dataDate.isAfter(swaptionInfo.maturityDate);
    }

    private double resolveExerciseFixingRate(MarketData md) {
        if (StringUtils.isBlank(swaptionInfo.fixingId)) {
            throw new IllegalArgumentException("行权日 FIXING_ID 不能为空: instrumentId=" + swaptionInfo.instrumentId);
        }
        Fixing.FixingInfo fixingInfo = md.fixingRate == null ? null : md.fixingRate.get(swaptionInfo.fixingId);
        if (fixingInfo == null) {
            throw new IllegalArgumentException("行权日定盘利率数据缺失: fixingId=" + swaptionInfo.fixingId);
        }
        return new Fixing(fixingInfo).getRate(swaptionInfo.maturityDate);
    }

    private SwaptionMeasure buildMaturedMeasure() {
        SwaptionMeasure measure = new SwaptionMeasure();
        measure.instrumentId = swaptionInfo.instrumentId;
        measure.productCode = swaptionInfo.productCode;
        measure.dataDate = dataDate;
        measure.valuationCcy = swaptionInfo.currencyCode;
        measure.position = getSignedNotional();
        measure.valuation = 0.0;
        measure.valuationCny = 0.0;
        measure.valuationUnit = 0.0;

        measure.gamma = 0.0;
        measure.vega = 0.0;
        measure.status = "SUCCESS";
        measure.sensitivityList = new ArrayList<>();
        measure.cashFlowList = new ArrayList<>();

        measure.logs = new ArrayList<>();
        measure.detail = new LinkedHashMap<>();
        if (isExpired()) {
            measure.detail.put("MATURED", true);
            measure.detail.put("MATURED_DATE", swaptionInfo.maturityDate);
            measure.detail.put("MATURED_INFO", "已到期");
        }
        return measure;
    }

    private List<BaseCashFlow> buildFloatingCashFlowList(List<StructuredCashflow.Cashflow> src, MarketData md) {
        List<BaseCashFlow> res = new ArrayList<>();
        if (src == null) {
            return res;
        }
        IrSpot discountCurve = new IrSpot(md.irSpot.get(swaptionInfo.discountCurve));
        for (StructuredCashflow.Cashflow cf : src) {
            ScfCashFlow out = new ScfCashFlow();
            out.dataDate = dataDate;
            out.currencyCode = swaptionInfo.currencyCode;
            out.paymentDate = cf.paymentDate;
            out.prepaymentDate = cf.prePaymentDate;
            out.theoPaymentDate = cf.theoPaymentDate;
            out.fwdStartDat = cf.fwdStartDate;
            out.fwdEndDate = cf.fwdEndDate;
            out.cashFlowType = cf.cashType;
            out.cashflow = cf.cf;
            out.discountRate = discountCurve.spotRate(cf.paymentDate);
            out.discountFactor = cf.discoutFactor;
            out.rate = cf.rate;
            out.startNotional = cf.startNotional;
            out.endNotional = cf.endNotional;
            res.add(out);
        }
        return res;
    }

    /**
     * 仅复制需要 shock 的利率曲线，构造局部替换后的市场数据。
     */
    private MarketData buildShiftedIrMarketData(MarketData baseMarketData, double shift) {
        MarketData shockedMarketData = new MarketData();
        shockedMarketData.irSpot = new HashMap<>(baseMarketData.irSpot);
        shockedMarketData.irVol = new HashMap<>(baseMarketData.irVol);
        shockedMarketData.eqSpot = new HashMap<>(baseMarketData.eqSpot);
        shockedMarketData.eqVol = new HashMap<>(baseMarketData.eqVol);
        shockedMarketData.commSpot = new HashMap<>(baseMarketData.commSpot);
        shockedMarketData.commVol = new HashMap<>(baseMarketData.commVol);
        shockedMarketData.fxVol = new HashMap<>(baseMarketData.fxVol);
        shockedMarketData.fixingRate = new HashMap<>(baseMarketData.fixingRate);
        shockedMarketData.fxSpot = baseMarketData.fxSpot;

        HashSet<String> curveSet = new HashSet<>();
        curveSet.add(swaptionInfo.discountCurve);
        curveSet.add(swaptionInfo.referenceCurve);
        curveSet.removeIf(StringUtils::isBlank);
        for (String curveId : curveSet) {
            IrSpot.IrSpotInfo spotInfo = baseMarketData.irSpot.get(curveId);
            if (spotInfo != null) {
                IrSpot.IrSpotInfo shockedCurve = CommUtils.deepCopy(spotInfo);
                shockedCurve.shift(shift);
                shockedMarketData.irSpot.put(curveId, shockedCurve);
            }
        }
        return shockedMarketData;
    }

    /**
     * 产品内 greek 仍保留单一 Vega 指标，但不再依赖旧的 MarketData GIRR Vega API。
     * 这里仅对当前交易所用利率波动率曲面构造一层绝对波动率 shock 增量面。
     */
    private MarketData buildShiftedIrVolMarketData(MarketData baseMarketData, double shiftRatio) {
        if (baseMarketData == null
                || baseMarketData.irVol == null
                || StringUtils.isBlank(swaptionInfo.volatilitySurface)
                || baseMarketData.irVol.get(swaptionInfo.volatilitySurface) == null) {
            return null;
        }
        MarketData shockedMarketData = new MarketData();
        shockedMarketData.irSpot = new HashMap<>(baseMarketData.irSpot);
        shockedMarketData.irVol = new HashMap<>(baseMarketData.irVol);
        shockedMarketData.eqSpot = new HashMap<>(baseMarketData.eqSpot);
        shockedMarketData.eqVol = new HashMap<>(baseMarketData.eqVol);
        shockedMarketData.commSpot = new HashMap<>(baseMarketData.commSpot);
        shockedMarketData.commVol = new HashMap<>(baseMarketData.commVol);
        shockedMarketData.fxVol = new HashMap<>(baseMarketData.fxVol);
        shockedMarketData.fixingRate = new HashMap<>(baseMarketData.fixingRate);
        shockedMarketData.fxSpot = baseMarketData.fxSpot;

        IrVol.IrVolInfo baseVolInfo = baseMarketData.irVol.get(swaptionInfo.volatilitySurface);
        IrVol.IrVolInfo shockedVolInfo = CommUtils.deepCopy(baseVolInfo);
        List<VolSurfacePoint> shockCurveData = new ArrayList<>();
        if (baseVolInfo.curveData != null) {
            for (VolSurfacePoint point : baseVolInfo.curveData) {
                if (point == null) {
                    continue;
                }
                shockCurveData.add(point.withVolatilityRate(
                        shiftRatio));
            }
        }
        shockedVolInfo.shockCurveData = shockCurveData;
        shockedMarketData.irVol.put(swaptionInfo.volatilitySurface, shockedVolInfo);
        return shockedMarketData;
    }

    private void validateInputs(MarketData md) {
        if (swaptionInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        requireText(swaptionInfo.productCode, "PRODUCT_CODE");
        requireText(swaptionInfo.instrumentId, "INSTRUMENT_ID");
        isCallOption();
        if (!"B".equalsIgnoreCase(swaptionInfo.buyOrSell)
                && !"S".equalsIgnoreCase(swaptionInfo.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + swaptionInfo.buyOrSell);
        }
        requireCurrencyCode(swaptionInfo.currencyCode, "CURRENCY_CODE");
        requireNonNegativeFinite(swaptionInfo.notional, "NOTIONAL");
        if (swaptionInfo.maturityDate == null) {
            throw new IllegalArgumentException("到期日为空: instrumentId=" + swaptionInfo.instrumentId);
        }
        if (swaptionInfo.underlyingStartDate == null || swaptionInfo.underlyingMaturityDate == null) {
            throw new IllegalArgumentException("标的起止日期为空: instrumentId=" + swaptionInfo.instrumentId);
        }
        if (swaptionInfo.maturityDate.isAfter(swaptionInfo.underlyingStartDate)) {
            throw new IllegalArgumentException("MATURITY_DATE 不能晚于 UNDERLYING_START_DATE");
        }
        if (!swaptionInfo.underlyingStartDate.isBefore(swaptionInfo.underlyingMaturityDate)) {
            throw new IllegalArgumentException("UNDERLYING_START_DATE 必须早于 UNDERLYING_MATURITY_DATE");
        }
        requireText(swaptionInfo.underlyingFreq, "UNDERLYING_FREQ");
        requireFinite(swaptionInfo.fixedRate, "FIXED_RATE");
        requireText(swaptionInfo.fixedDayCountBasis, "FIXED_DAY_COUNT_BASIS");
        requireText(swaptionInfo.fixingFreq, "FIXING_FREQ");
        requireText(swaptionInfo.discountCurve, "DISCOUNT_CURVE");
        requireText(swaptionInfo.referenceCurve, "REFERENCE_CURVE");
        requireText(swaptionInfo.volatilitySurface, "VOLATILITY_SURFACE");
        resolveValuationModel();
        resolveSettleType();
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空: instrumentId=" + swaptionInfo.instrumentId);
        }
        if (isExpired()) {
            return;
        }
        if (md.irSpot == null || md.irSpot.get(swaptionInfo.discountCurve) == null) {
            throw new IllegalArgumentException("折现曲线不存在: " + swaptionInfo.discountCurve);
        }
        if (isExerciseDate()) {
            resolveExerciseFixingRate(md);
            return;
        }
        if (md.irSpot.get(swaptionInfo.referenceCurve) == null) {
            throw new IllegalArgumentException("参考曲线不存在: " + swaptionInfo.referenceCurve);
        }
        if (md.irVol == null || md.irVol.get(swaptionInfo.volatilitySurface) == null) {
            throw new IllegalArgumentException("波动率曲面不存在: " + swaptionInfo.volatilitySurface);
        }
        for (VolSurfacePoint row : md.irVol.get(swaptionInfo.volatilitySurface).curveData) {
            if (row == null
                    || row.getOptionTerm() < 0
                    || !Double.isFinite(row.getAxis2Value())
                    || !Double.isFinite(row.getVolatilityRate())) {
                throw new IllegalArgumentException(
                        "波动率曲面字段缺失，仅支持 OPTION_TERM/UNDERLYING_TERM/VOLATILITY_RATE 标准格式");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static void requireCurrencyCode(String value, String field) {
        requireText(value, field);
        if (value.length() != 3) {
            throw new IllegalArgumentException(field + " 必须为3位货币代码: " + value);
        }
    }

    private static void requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " 必须为有限数: " + value);
        }
    }

    private static void requireNonNegativeFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " 必须为非负有限数: " + value);
        }
    }

    /*swaption内部类，封装计量指标*/
    public static class SwaptionMeasure extends OptionMeasure {
    }
    
    public static class SwaptionTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"CALL", "PUT"}, ignoreCase = true)
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_START_DATE", format = "yyyy-MM-dd")
        public LocalDate underlyingStartDate;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate underlyingMaturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_FREQ")
        public String underlyingFreq;
        @ProductInputField
        @JSONField(name = "UNDERLYING_SETTLE_CALENDAR")
        public String underlyingSettleCalendar;
        @ProductInputField(allowedValues = {"Regular_Preceding", "Modified_Preceding",
                "Regular_Following", "Modified_Following"}, ignoreCase = true)
        @JSONField(name = "UNDERLYING_SETTLE_RULE")
        public String underlyingSettleRule;
        @ProductInputField(min = "0")
        @JSONField(name = "UNDERLYING_SETTLE_DAYOFF")
        public Integer underlyingSettleDayoff;
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "FIXED_RATE")
        public Double fixedRate;
        @ProductInputField(required = true)
        @JSONField(name = "FIXED_DAY_COUNT_BASIS")
        public String fixedDayCountBasis = "actual/365";
        @ProductInputField
        @JSONField(name = "FIXING_CALENDAR")
        public String fixingCalendar;
        @ProductInputField(allowedValues = {"Regular_Preceding", "Modified_Preceding",
                "Regular_Following", "Modified_Following"}, ignoreCase = true)
        @JSONField(name = "FIXING_RULE")
        public String fixingRule;
        @ProductInputField(min = "0")
        @JSONField(name = "FIXING_DAYOFF")
        public Integer fixingDayoff;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField(allowedValues = {"BACHELIER", "NORMAL", "BLACK76", "BLACK", "LOGNORMAL"},
                ignoreCase = true)
        @JSONField(name = "VALUATION_MODEL")
        public String valuationModel;

        @ProductInputField(required = true)
        @JSONField(name = "FIXING_FREQ")
        public String fixingFreq;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @ProductInputField(allowedValues = {"PHYSICAL"}, ignoreCase = true)
        @JSONField(name = "SETTLE_TYPE")
        public String settleType = "PHYSICAL";
    }
}

