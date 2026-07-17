package com.zcyh.mr.product.basic.option;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.zcyh.mr.product.basic.frtb.OptionBaseFrtbSupport.*;

/**
 * 数字期权（二元期权）产品基类。
 * 提取 Ir/Eq/Fx/Comm DigOpt 的公共估值逻辑。
 * 使用 DigOptUtil 进行解析定价和 Greek 计算。
 *
 * @param <I> 子类 Info 类型，必须继承 DigOptBaseInfo
 */
public abstract class DigOptBase<I extends DigOptBase.DigOptBaseInfo> {

    protected final LocalDate dataDate;
    protected final I info;
    protected final MarketData marketData;
    protected final double pos;

    protected DigOptBase(LocalDate dataDate, I info, MarketData marketData) {
        this.dataDate = dataDate;
        this.info = info;
        this.marketData = marketData;
        this.pos = initPosition();
    }

    private double initPosition() {
        if (info == null || info.contractSize == null || info.buyOrSell == null)
            return 0.0;
        if ("B".equalsIgnoreCase(info.buyOrSell))
            return info.contractSize;
        if ("S".equalsIgnoreCase(info.buyOrSell))
            return -info.contractSize;
        return 0.0;
    }

    /**
     * 基准估值入口：校验 + 核心估值 + Greeks 计算。
     */
    public OptionMeasure calc() {
        if (info.settleDate == null)
            info.settleDate = info.maturityDate;
        validateCommon();
        validateSpecific(marketData);
        return calc(marketData);
    }

    /**
     * 场景估值入口：使用传入的市场数据进行纯估值，不重新校验。
     */
    public OptionMeasure calc(MarketData md) {
        int days = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        double t = days / 365.0;
        double s = getSpotPrice(md);
        double k = info.strikePrice;
        double rd = getDiscountRate(md);
        double rebase = getRebaseRate(md);
        double rf = getRf(md, s, rd, t);
        double fwd = getFwdPrice(md, s, rd, rf, t);

        boolean call = "Call".equalsIgnoreCase(info.callOrPut);
        double rebate = info.payoffLower;

        List<Map<String, Object>> volCur = getVolCur(md, days);
        EurOptUtil optUtil = new EurOptUtil(call, true, s, k, rd, rf, t, t, volCur, "black");
        double sigma = optUtil.getSigma();

        DigOptUtil digUtil = new DigOptUtil(call, true, s, k, rebate, rd, rf, rebase, t, t, volCur, sigma, "black",
                Boolean.TRUE.equals(info.vvFlag));

        boolean vv = Boolean.TRUE.equals(info.vvFlag);
        boolean applyNonNegativeFloor = vv && isNonNegativeFloorEnabled();
        double value = applyFloorIfNeeded(digUtil.getValue(), applyNonNegativeFloor);

        OptionMeasure measure = buildMeasure(digUtil, s, fwd, sigma, value, md);
        return measure;
    }

    /**
     * VV 开启时统一读取非负兜底开关。
     */
    private boolean isNonNegativeFloorEnabled() {
        return EngineConfiguration.getInstance()
                .getRequiredBoolean(EngineConstants.CFG.VV_NON_NEGATIVE_FLOOR_ENABLED);
    }

    /**
     * 统一非负兜底，防止 VV 调整在极端场景下产生负的期权腿价格。
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

    /**
     * 构建 OptionMeasure 通用字段。
     */
    protected OptionMeasure buildMeasure(DigOptUtil digUtil, double s, double fwd, double sigma, double value,
            MarketData md) {
        double fxRate = getFxRate(md);
        OptionMeasure measure = new OptionMeasure();
        measure.valuationUnit = value + info.basePayoff;
        measure.valuation = measure.valuationUnit * pos;
        measure.valuationCny = measure.valuation * fxRate;
        measure.spotPrice = s;
        measure.fwdPrice = fwd;
        measure.impliedVol = sigma;
        measure.delta = digUtil.Delta();
        measure.gamma = digUtil.Gamma();
        measure.vega = digUtil.Vega();
        measure.theta = digUtil.Theta();
        measure.instrumentId = info.instrumentId;
        measure.productCode = info.productCode;
        measure.dataDate = dataDate;
        measure.position = pos;
        measure.valuationCcy = getCurrencyCode();
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        measure.detail = buildDetail(digUtil);
        measure.cashFlowList = null;
        measure.sensitivityList = null;
        return measure;
    }

    /**
     * 构建附加明细。
     */
    protected Map<String, Object> buildDetail(DigOptUtil digUtil) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("BASE_PAYOFF", info.basePayoff);
        detail.put("PAYOFF_LOWER", info.payoffLower);
        detail.put("STRIKE_PRICE", info.strikePrice);
        detail.put("D2", digUtil.getD2());
        return detail;
    }

    /**
     * 通用输入校验。
     */
    protected void validateCommon() {
        if (!"B".equalsIgnoreCase(info.buyOrSell) && !"S".equalsIgnoreCase(info.buyOrSell))
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + info.buyOrSell);
        if (info.contractSize == null || !Double.isFinite(info.contractSize) || info.contractSize <= 0.0)
            throw new IllegalArgumentException("CONTRACT_SIZE 必须为正有限数: " + info.contractSize);
        if (info.maturityDate == null || !info.maturityDate.isAfter(dataDate))
            throw new IllegalArgumentException("MATURITY_DATE 必须晚于 DATA_DATE");
        if (info.strikePrice == null || info.strikePrice <= 0)
            throw new IllegalArgumentException("STRIKE_PRICE 必须大于 0");
        if (info.callOrPut == null || info.callOrPut.trim().isEmpty())
            throw new IllegalArgumentException("CALL_OR_PUT 不能为空");
        String cp = info.callOrPut.trim().toLowerCase();
        if (!"call".equals(cp) && !"put".equals(cp))
            throw new IllegalArgumentException("CALL_OR_PUT 仅支持 Call/Put: " + info.callOrPut);
    }

    protected static String textOrDefault(String text, String dft) {
        if (text == null || text.trim().isEmpty())
            return dft;
        return text.trim();
    }

    protected static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    /**
     * 外汇数字期权公共 FRTB 输出模板。
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
            String volatilitySurface,
            Function<MarketData, OptionMeasure> repriceFunction,
            Runnable beforeVegaReprice) {
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
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                beforeVegaReprice);
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(underlyingDiscountCurve, underlyingCurrencyCode);
        curveMap.put(baseDiscountCurve, baseCurrencyCode);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                Collections.emptyList(),
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
     * 权益数字期权公共 FRTB 输出模板。
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
                Collections.emptyList(),
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
                Collections.emptyList(),
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

        if (FrtbSensitivityBuilder.warnMissingEqSensitivityInputs(measure, eqBucket)) {
            return list;
        }
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
     * 利率数字期权公共 FRTB 输出模板。
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
                Collections.emptyList(),
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
     * 商品数字期权公共 FRTB 输出模板。
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
                Collections.emptyList(),
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
                Collections.emptyList(),
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

        if (addMissingCmtyDependencyWarnings(
                measure,
                info.instrumentId,
                cmtyBucket,
                cmtyRiskFactorId,
                cmtyRiskFactorIdVega)) {
            return list;
        }

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

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    // ---------- 子类必须实现 ----------

    /** 获取标的即期价格 */
    protected abstract double getSpotPrice(MarketData md);

    /** 获取远期价格 */
    protected abstract double getFwdPrice(MarketData md, double s, double rd, double rf, double t);

    /** 获取外利率 rf */
    protected abstract double getRf(MarketData md, double s, double rd, double t);

    /** 获取波动率曲线 */
    protected abstract List<Map<String, Object>> getVolCur(MarketData md, int days);

    /** 获取折现利率 */
    protected abstract double getDiscountRate(MarketData md);

    /** 获取 rebase 利率（结算折现利率） */
    protected abstract double getRebaseRate(MarketData md);

    /** 获取币种代码 */
    protected abstract String getCurrencyCode();

    /** 获取 CNY 汇率转换系数 */
    protected abstract double getFxRate(MarketData md);

    /** 子类特有输入校验 */
    protected abstract void validateSpecific(MarketData md);

    /**
     * 数字期权公共输入信息基类。
     */
    public static class DigOptBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true, allowedValues = {"Call", "Put"}, ignoreCase = true)
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @ProductInputField(finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
        @JSONField(name = "BASE_PAYOFF")
        public Double basePayoff;
        @JSONField(name = "PAYOFF_LOWER")
        public Double payoffLower;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField(required = true)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        /** VV 开关：true 时启用 Vanna-Volga overhedge 调整 */
        @JSONField(name = "VV_FLAG")
        public Boolean vvFlag;
    }
}

