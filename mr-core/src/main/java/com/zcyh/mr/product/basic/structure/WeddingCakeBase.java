package com.zcyh.mr.product.basic.structure;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.product.basic.option.WeddingCakeUtil;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * WeddingCake 结构化产品公共基类：
 * - 外部绝对收益率输入；
 * - 内部增量化处理；
 * - 到期后使用 fixing 直接判定层级收益。
 */
public abstract class WeddingCakeBase<T extends WeddingCakeBase.WeddingCakeBaseInfo, M extends OptionMeasure> {

    protected static final double MIN_SPOT = 1e-12;
    protected static final double DEFAULT_EPS = 0.0001;
    protected static final double VEGA_SHIFT = 0.001;
    protected static final double ONE_DAY = 1.0 / 365.0;

    protected final LocalDate dataDate;
    protected final T info;
    protected final MarketData marketData;
    protected final double pos;

    protected WeddingCakeBase(LocalDate dataDate, T info, MarketData marketData) {
        this.dataDate = dataDate;
        this.info = info;
        this.marketData = marketData;
        this.pos = ("B".equalsIgnoreCase(info.buyOrSell) ? 1.0 : -1.0) * nz(info.contractSize, 1.0);
    }

    protected abstract M newMeasure();

    protected abstract MarketContext buildMarketContext(MarketData marketData, int days, double t);

    protected void validateSpecificInputs(MarketData md) {
        // 默认不执行操作
    }

    protected void postProcessOptionOutput(M measure) {
        // 默认不执行操作
    }

    /**
     * 外汇 WeddingCake 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta/Vega/Curvature 和 GIRR Delta/Curvature。
     */
    protected List<FrtbSenes> buildFxFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String underlyingCurrencyCode,
            String baseCurrencyCode,
            String currencyCode,
            String baseDiscountCurve,
            String underlyingDiscountCurve,
            String discountCurve,
            String volatilitySurface,
            Function<MarketData, OptionMeasure> repriceFunction) {
        List<FrtbSenes> list = new ArrayList<>();
        if (measure == null || repriceFunction == null) {
            return list;
        }
        MeasureValuation baseValuation = toMeasureValuation(measure);

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                collectFxRiskCurrencies(underlyingCurrencyCode, baseCurrencyCode, currencyCode),
                FrtbSensitivityBuilder.buildFxPair(underlyingCurrencyCode, baseCurrencyCode));
        List<FrtbDependency> fxVegaDependencies = buildFxVegaDependencies(
                underlyingCurrencyCode,
                baseCurrencyCode,
                volatilitySurface);
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                fxVegaDependencies,
                true,
                true,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(underlyingDiscountCurve, underlyingCurrencyCode);
        curveMap.put(baseDiscountCurve, baseCurrencyCode);
        curveMap.put(discountCurve, currencyCode);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);
        return list;
    }

    /**
     * 权益 WeddingCake 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta、GIRR Delta/Curvature、EQ Delta/Vega/Curvature。
     */
    protected List<FrtbSenes> buildEqFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String currencyCode,
            String discountCurve,
            String priceCurve,
            String volatilitySurface,
            String eqBucket,
            Function<MarketData, OptionMeasure> repriceFunction) {
        List<FrtbSenes> list = new ArrayList<>();
        if (measure == null || repriceFunction == null) {
            return list;
        }
        MeasureValuation baseValuation = toMeasureValuation(measure);

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                collectFxRiskCurrencies(currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(discountCurve, currencyCode);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);

        List<FrtbDependency> eqDeltaDependencies = FrtbSensitivityBuilder.buildEqDeltaDependencies(priceCurve, eqBucket);
        List<FrtbDependency> eqVegaDependencies = FrtbSensitivityBuilder.buildEqVegaDependencies(
                volatilitySurface,
                priceCurve,
                eqBucket);
        List<FrtbSenes> eqSensitivities = FrtbSensitivityBuilder.buildEqSensitivities(
                marketData,
                dataDate,
                settleDate,
                eqDeltaDependencies,
                eqVegaDependencies,
                true,
                true,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null);
        list.addAll(eqSensitivities);
        return list;
    }

    /**
     * 利率 WeddingCake 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta、GIRR Delta/Vega/Curvature。
     */
    protected List<FrtbSenes> buildIrFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String currencyCode,
            String discountCurve,
            String priceCurve,
            String volatilitySurface,
            String girrSecondaryVertex,
            Function<MarketData, OptionMeasure> repriceFunction) {
        List<FrtbSenes> list = new ArrayList<>();
        if (measure == null || repriceFunction == null) {
            return list;
        }
        MeasureValuation baseValuation = toMeasureValuation(measure);

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                collectFxRiskCurrencies(currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        if (hasText(discountCurve)) {
            curveMap.put(discountCurve, currencyCode);
        }
        if (hasText(priceCurve)) {
            curveMap.put(priceCurve, currencyCode);
        }
        List<FrtbDependency> girrVegaDependencies = FrtbSensitivityBuilder.buildGirrVegaDependencies(
                volatilitySurface,
                currencyCode,
                girrSecondaryVertex);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                girrVegaDependencies,
                true,
                true,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);
        return list;
    }

    /**
     * 商品 WeddingCake 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta、GIRR Delta/Curvature、CMTY Delta/Vega/Curvature。
     */
    protected List<FrtbSenes> buildCmtyFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String currencyCode,
            String discountCurve,
            String priceCurve,
            String cmtyRiskFactorId,
            String cmtyRiskFactorIdVega,
            String cmtyBucket,
            String volatilitySurface,
            Function<MarketData, OptionMeasure> repriceFunction) {
        List<FrtbSenes> list = new ArrayList<>();
        if (measure == null || repriceFunction == null || !hasText(cmtyBucket)) {
            return list;
        }
        MeasureValuation baseValuation = toMeasureValuation(measure);

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                collectFxRiskCurrencies(currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(discountCurve, currencyCode);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);

        List<FrtbDependency> cmtyDeltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
                priceCurve,
                cmtyRiskFactorId,
                cmtyBucket);
        List<FrtbDependency> cmtyVegaDependencies = FrtbSensitivityBuilder.buildCmtyVegaDependencies(
                volatilitySurface,
                cmtyRiskFactorIdVega,
                cmtyBucket);
        List<FrtbSenes> cmtySensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                marketData,
                dataDate,
                settleDate,
                cmtyDeltaDependencies,
                cmtyVegaDependencies,
                true,
                true,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null);
        list.addAll(cmtySensitivities);
        return list;
    }

    public M calc() {
        try {
            M measure = calc(this.marketData);
            if ("SUCCESS".equalsIgnoreCase(measure.status)) {
                postProcessOptionOutput(measure);
            }
            return measure;
        } catch (Exception ex) {
            return buildErrorMeasure(ex);
        }
    }

    public M calc(MarketData md) {
        validateInputs(md);

        int days = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        double t = Math.max(0.0, yearFrac(dataDate, info.maturityDate));
        MarketContext ctx = buildMarketContext(md, days, t);

        Fixing.FixingInfo fixingInfo = md.fixingRate.get(info.fixingId);
        Fixing fixing = new Fixing(fixingInfo);

        double accrualYear = yearFrac(info.startDate, info.maturityDate);
        boolean matured = !dataDate.isBefore(info.maturityDate);

        LocalDate histEnd = minDate(dataDate, info.maturityDate);
        WeddingCakeUtil.TouchState histState = WeddingCakeUtil.TouchState.NONE;
        if (!histEnd.isBefore(info.startDate)) {
            histState = WeddingCakeUtil.detectTouchState(
                    fixing, fixingInfo, info.startDate, histEnd,
                    info.outerLowerBarrier, info.outerUpperBarrier,
                    info.innerLowerBarrier, info.innerUpperBarrier);
        }

        WeddingCakeUtil util = new WeddingCakeUtil(
                ctx.s, ctx.rd, ctx.rf, ctx.rebase, ctx.t, ctx.ts, ctx.volCurve,
                info.outerLowerBarrier, info.outerUpperBarrier,
                info.innerLowerBarrier, info.innerUpperBarrier,
                info.outRate, info.midRate, info.innerRate,
                Boolean.TRUE.equals(info.vvFlag));

        WeddingCakeUtil.Result rs = new WeddingCakeUtil.Result();
        WeddingCakeUtil.TouchState usedState = histState;
        if (matured) {
            WeddingCakeUtil.TouchState finalState = WeddingCakeUtil.detectTouchState(
                    fixing, fixingInfo, info.startDate, info.maturityDate,
                    info.outerLowerBarrier, info.outerUpperBarrier,
                    info.innerLowerBarrier, info.innerUpperBarrier);
            rs.stateLabel = "FINAL_" + finalState.name();
            rs.expectedRate = util.realizedRate(finalState);
            usedState = finalState;
        } else {
            rs = util.evaluate(histState, info.notional, accrualYear);
        }

        M measure = newMeasure();
        measure.instrumentId = info.instrumentId;
        measure.productCode = info.productCode;
        measure.dataDate = dataDate;
        measure.position = pos;
        measure.valuationCcy = info.currencyCode;

        // 统一使用 valueUnit() 定价，到期/未到期均一致
        // - 到期后 t→0，barrier 退化为确定性结果，由 usedState 区分层级
        // - 未到期含 VV 调整，与 Greeks 扰动口径统一
        measure.valuationUnit = util.valueUnit(info.notional, accrualYear,
                usedState, rs.sigmaOuter, rs.sigmaInner, 0.0, 0.0, 0.0);
        measure.valuation = measure.valuationUnit * pos;
        measure.valuationCny = measure.valuation * ctx.fxToCny;
        measure.spotPrice = ctx.s;
        measure.fwdPrice = ctx.f;
        measure.impliedVol = avg(rs.sigmaOuter, rs.sigmaInner);

        if (!matured && usedState != WeddingCakeUtil.TouchState.OUTER_TOUCHED) {
            applyGreeks(measure, util, ctx, usedState, rs.sigmaOuter, rs.sigmaInner, accrualYear);
        }

        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        measure.cashFlowList = null;
        measure.sensitivityList = null;
        measure.detail = buildDetail(rs, usedState);
        return measure;
    }

    private M buildErrorMeasure(Exception ex) {
        M m = newMeasure();
        m.instrumentId = info == null ? null : info.instrumentId;
        m.productCode = info == null ? null : info.productCode;
        m.dataDate = dataDate;
        m.position = pos;
        m.status = "ERROR";
        m.addErrorLog(ex == null || ex.getMessage() == null ? "UNKNOWN_ERROR" : ex.getMessage());
        m.cashFlowList = null;
        m.detail = null;
        m.sensitivityList = null;
        return m;
    }

    private void applyGreeks(M measure,
            WeddingCakeUtil util,
            MarketContext ctx,
            WeddingCakeUtil.TouchState histState,
            double sigmaOuter,
            double sigmaInner,
            double accrualYear) {
        double eps = nz(info.eps, DEFAULT_EPS);
        boolean absFlag = info.absFlag != null && info.absFlag;
        double sShift = absFlag ? eps : Math.max(Math.abs(ctx.s) * eps, eps);

        double vBase = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                0.0, 0.0, 0.0);
        double vUp = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                sShift, 0.0, 0.0);
        double vDown;
        if (ctx.s - sShift <= MIN_SPOT) {
            vDown = vBase;
        } else {
            vDown = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                    -sShift, 0.0, 0.0);
        }

        measure.delta = (vUp - vDown) / (ctx.s - sShift <= MIN_SPOT ? sShift : 2 * sShift);
        if (ctx.s - sShift <= MIN_SPOT) {
            double vUp2 = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                    2 * sShift, 0.0, 0.0);
            measure.gamma = (vUp2 - 2 * vUp + vBase) / (sShift * sShift);
        } else {
            measure.gamma = (vUp - 2 * vBase + vDown) / (sShift * sShift);
        }

        double vegaUp = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                0.0, VEGA_SHIFT, 0.0);
        double vegaDown = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                0.0, -VEGA_SHIFT, 0.0);
        measure.vega = (vegaUp - vegaDown) / (2 * VEGA_SHIFT * 100.0);

        double vTmr = util.valueUnit(info.notional, accrualYear, histState, sigmaOuter, sigmaInner,
                0.0, 0.0, -ONE_DAY);
        measure.theta = vTmr - vBase;
    }

    private LinkedHashMap<String, Object> buildDetail(WeddingCakeUtil.Result rs, WeddingCakeUtil.TouchState histState) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("MATURITY_DATE", info.maturityDate);
        detail.put("STATE", rs.stateLabel);
        detail.put("HIST_STATE", histState.name());
        detail.put("EXPECTED_RATE", rs.expectedRate);
        detail.put("OUT_RATE", info.outRate);
        detail.put("MID_RATE", info.midRate);
        detail.put("INNER_RATE", info.innerRate);
        detail.put("BASE_RATE", info.outRate);
        detail.put("DELTA_OUTER", info.midRate - info.outRate);
        detail.put("DELTA_INNER", info.innerRate - info.midRate);
        detail.put("OUTER_NO_TOUCH_PROB", rs.pOuter);
        detail.put("INNER_NO_TOUCH_PROB", rs.pInner);
        detail.put("SIGMA_OUTER", rs.sigmaOuter);
        detail.put("SIGMA_INNER", rs.sigmaInner);
        if (Boolean.TRUE.equals(info.vvFlag)) {
            detail.put("VV_ADJ_OUTER", rs.vvAdjOuter);
            detail.put("VV_ADJ_INNER", rs.vvAdjInner);
        }
        return detail;
    }

    protected void validateInputs(MarketData md) {
        requireNotNull(info, "TRADE_INFO");
        requireNotNull(md, "marketData");
        requireNotNull(md.irSpot, "marketData.irSpot");
        requireNotNull(md.fixingRate, "marketData.fixingRate");

        requireText(info.instrumentId, "INSTRUMENT_ID");
        requireText(info.productCode, "PRODUCT_CODE");
        requireText(info.buyOrSell, "BUY_OR_SELL");
        if (!"B".equalsIgnoreCase(info.buyOrSell) && !"S".equalsIgnoreCase(info.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B 或 S: " + info.buyOrSell);
        }

        requireNotNull(info.startDate, "START_DATE");
        requireNotNull(info.maturityDate, "MATURITY_DATE");
        requireNotNull(info.settleDate, "SETTLE_DATE");
        if (info.maturityDate.isBefore(info.startDate)) {
            throw new IllegalArgumentException("MATURITY_DATE 不能早于 START_DATE");
        }
        if (info.settleDate.isBefore(info.maturityDate)) {
            throw new IllegalArgumentException("SETTLE_DATE 不能早于 MATURITY_DATE");
        }

        requireNotNull(info.notional, "NOTIONAL");
        if (info.notional <= 0) {
            throw new IllegalArgumentException("NOTIONAL 必须大于 0");
        }

        requireNotNull(info.outerLowerBarrier, "OUTER_LOWER_BARRIER");
        requireNotNull(info.outerUpperBarrier, "OUTER_UPPER_BARRIER");
        requireNotNull(info.innerLowerBarrier, "INNER_LOWER_BARRIER");
        requireNotNull(info.innerUpperBarrier, "INNER_UPPER_BARRIER");
        if (!(info.outerLowerBarrier < info.innerLowerBarrier
                && info.innerLowerBarrier < info.innerUpperBarrier
                && info.innerUpperBarrier < info.outerUpperBarrier)) {
            throw new IllegalArgumentException("障碍必须满足 OUTER_LOWER < INNER_LOWER < INNER_UPPER < OUTER_UPPER");
        }

        requireNotNull(info.outRate, "OUT_RATE");
        requireNotNull(info.midRate, "MID_RATE");
        requireNotNull(info.innerRate, "INNER_RATE");

        requireText(info.volatilitySurface, "VOLATILITY_SURFACE");
        requireText(info.currencyCode, "CURRENCY_CODE");
        requireText(info.discountCurve, "DISCOUNT_CURVE");
        requireText(info.fixingId, "FIXING_ID");
        if (!md.fixingRate.containsKey(info.fixingId)) {
            throw new IllegalArgumentException("缺少历史 fixing 曲线: " + info.fixingId);
        }

        if (info.eps == null) {
            info.eps = DEFAULT_EPS;
        }
        if (info.absFlag == null) {
            info.absFlag = false;
        }
        if (info.eps <= 0) {
            throw new IllegalArgumentException("EPS 必须大于 0");
        }

        validateSpecificInputs(md);
    }

    protected static double yearFrac(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        return Math.max(days / 365.0, 0.0);
    }

    protected static LocalDate minDate(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    protected static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    protected String resolveOptionalBucket(String defaultBucket, String... fieldNames) {
        if (fieldNames == null || fieldNames.length == 0) {
            return defaultBucket;
        }
        for (String fieldName : fieldNames) {
            String value = readTextField(info, fieldName);
            if (hasText(value)) {
                return value.trim();
            }
        }
        return defaultBucket;
    }

    private String readTextField(Object target, String fieldName) {
        if (target == null || !hasText(fieldName)) {
            return null;
        }
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                return value == null ? null : String.valueOf(value);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private MeasureValuation toMeasureValuation(OptionMeasure measure) {
        if (measure == null) {
            return null;
        }
        return MeasureValuation.of(measure.valuation, measure.valuationCny);
    }

    private List<String> collectFxRiskCurrencies(String... currencies) {
        LinkedHashSet<String> fxRiskCurrencies = new LinkedHashSet<>();
        if (currencies == null) {
            return new ArrayList<>();
        }
        for (String currency : currencies) {
            if (!hasText(currency)) {
                continue;
            }
            fxRiskCurrencies.add(currency);
        }
        return new ArrayList<>(fxRiskCurrencies);
    }

    private List<FrtbDependency> buildFxVegaDependencies(
            String underlyingCurrencyCode,
            String baseCurrencyCode,
            String volatilitySurface) {
        String undCcy = normalizeCcy(underlyingCurrencyCode);
        String baseCcy = normalizeCcy(baseCurrencyCode);
        String riskFactorId = "FX_" + undCcy + "_" + baseCcy + "_VOL";
        String bucket = undCcy + "/" + baseCcy;
        return FrtbSensitivityBuilder.buildFxVegaDependencies(volatilitySurface, riskFactorId, bucket);
    }

    private String normalizeCcy(String ccy) {
        if (ccy == null) {
            return "";
        }
        return ccy.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    protected static void requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("输入字段不能为空: " + fieldName);
        }
    }

    protected static void requireNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("输入字段不能为空: " + fieldName);
        }
    }

    protected static double nz(Double value, double dft) {
        return value == null ? dft : value;
    }

    protected static double avg(double x, double y) {
        if (x <= 0 && y <= 0) {
            return 0.0;
        }
        if (x <= 0) {
            return y;
        }
        if (y <= 0) {
            return x;
        }
        return (x + y) * 0.5;
    }

    public static class MarketContext {
        public double s;
        public double f;
        public double rd;
        public double rf;
        public double rebase;
        public double t;
        public double ts;
        public double fxToCny;
        public List<java.util.Map<String, Object>> volCurve;
    }

    public static class WeddingCakeBaseInfo {
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @JSONField(name = "CONTRACT_SIZE")
        public Double contractSize;
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "START_DATE", format = "yyyyMMdd")
        public LocalDate startDate;
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;

        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "FIXING_ID")
        public String fixingId;

        @JSONField(name = "OUTER_LOWER_BARRIER")
        public Double outerLowerBarrier;
        @JSONField(name = "OUTER_UPPER_BARRIER")
        public Double outerUpperBarrier;
        @JSONField(name = "INNER_LOWER_BARRIER")
        public Double innerLowerBarrier;
        @JSONField(name = "INNER_UPPER_BARRIER")
        public Double innerUpperBarrier;

        @JSONField(name = "OUT_RATE")
        public Double outRate;
        @JSONField(name = "MID_RATE")
        public Double midRate;
        @JSONField(name = "INNER_RATE")
        public Double innerRate;

        @JSONField(name = "EPS")
        public Double eps;
        @JSONField(name = "ABS_FLAG")
        public Boolean absFlag;
        /** VV 开关：true 时启用 Vanna-Volga overhedge 调整 */
        @JSONField(name = "VV_FLAG")
        public Boolean vvFlag;
    }
}

