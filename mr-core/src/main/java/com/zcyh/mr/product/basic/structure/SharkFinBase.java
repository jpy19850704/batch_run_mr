package com.zcyh.mr.product.basic.structure;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.validation.BooleanInputReader;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolUtil;
import com.zcyh.mr.marketdata.VolSurfacePoint;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.product.basic.option.BarOptUtil;
import com.zcyh.mr.product.basic.option.EurOptUtil;
import com.zcyh.mr.product.basic.option.EuroSingleUtil;
import com.zcyh.mr.product.basic.option.VannaVolgaAdjuster;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.zcyh.mr.product.basic.frtb.OptionBaseFrtbSupport.*;

/**
 * SharkFin 期权组合定价基类。
 * 组合结构：BarOptUtil（障碍腿）+ EuroSingleUtil（欧式腿）+ 固定腿。
 * sigma 校准、组合定价和 Greeks 计算均在 Base 层完成，与 StepUp 模式对齐。
 * 支持 Vanna-Volga overhedge 调整（按腿独立叠加）。
 */
public abstract class SharkFinBase<T extends SharkFinBase.SharkFinBaseTradeInfo, M extends OptionMeasure> {
    protected final LocalDate dataDate;
    protected final T info;
    protected final MarketData marketData;
    protected final double pos;

    /** Greeks 默认扰动步长（比例冲击 eps × s） */
    private static final double DEFAULT_EPS = 0.001;
    /** VV 腿级 Greeks 的 sigma 扰动步长 */
    private static final double VV_SIGMA_SHIFT = 0.001;
    /** VV 腿级 Greeks 的 spot 扰动比例 */
    private static final double VV_SPOT_SHIFT_RATIO = 1e-4;
    /** 数值稳定的最小正数 */
    private static final double MIN_POSITIVE = 1e-8;

    // ===== sigma 缓存（由 resolveSigma 填充） =====
    private double sigmaUp;
    private double sigmaDown;
    private double sigmaStrike;
    /** 双边障碍腿专用 sigma，按 ATM 口径插值得到。 */
    private double sigmaDoubleBarrier;

    // ===== 定价上下文（由 calc 填充，供 priceWith / Greeks 使用） =====
    private double mS, mRd, mRf, mRebase, mT, mTs;
    private int mDays;
    private boolean mIsDouble, mIsUp, mVvFlag;
    private List<VolSurfacePoint> mVolCurve;

    /**
     * 主估值结果对象：只承载本次主路径需要写入 detail 的诊断量。
     */
    private static final class PricingResult {
        double value;
        double upNoTouchProb = Double.NaN;
        double downNoTouchProb = Double.NaN;
        double upBarrierVvAdj = Double.NaN;
        double downBarrierVvAdj = Double.NaN;
        double upSingleVvAdj = Double.NaN;
        double downSingleVvAdj = Double.NaN;
    }

    protected SharkFinBase(LocalDate dataDate, T info, MarketData marketData) {
        this.dataDate = dataDate;
        this.info = info;
        this.marketData = marketData;
        this.pos = ("B".equalsIgnoreCase(info.buyOrSell) ? 1.0 : -1.0) * positionMultiplier();
    }

    protected abstract M newMeasure();

    protected abstract MarketContext buildMarketContext(MarketData marketData, int days, double t);

    /** 子类输入/市场数据校验 */
    protected abstract List<String> validateSpecificInputs(MarketData marketData);

    protected double positionMultiplier() {
        return nz(info.contractSize, 1.0);
    }

    protected void postProcessOptionOutput(M measure) {
        // 默认空实现
    }

    /**
     * 外汇 SharkFin 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta/Vega/Curvature 和 GIRR Delta/Curvature。
     */
    protected List<FrtbSenes> buildFxFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String underlyingCurrencyCode,
            String baseCurrencyCode,
            String valuationCurrency,
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
                collectFxRiskCurrencies(underlyingCurrencyCode, baseCurrencyCode, valuationCurrency),
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
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(underlyingDiscountCurve, underlyingCurrencyCode);
        curveMap.put(baseDiscountCurve, baseCurrencyCode);
        curveMap.put(discountCurve, valuationCurrency);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);
        return list;
    }

    /**
     * 权益 SharkFin 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta、GIRR Delta/Curvature、EQ Delta/Vega/Curvature。
     */
    protected List<FrtbSenes> buildEqFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String valuationCurrency,
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
                collectFxRiskCurrencies(valuationCurrency));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(discountCurve, valuationCurrency);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                getFrtbInstrumentCurrency(),
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
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null);
        list.addAll(eqSensitivities);
        return list;
    }

    /**
     * 利率 SharkFin 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta、GIRR Delta/Vega/Curvature。
     */
    protected List<FrtbSenes> buildIrFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String valuationCurrency,
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
                collectFxRiskCurrencies(valuationCurrency));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        if (hasText(discountCurve)) {
            curveMap.put(discountCurve, valuationCurrency);
        }
        if (hasText(priceCurve)) {
            curveMap.put(priceCurve, valuationCurrency);
        }
        List<FrtbDependency> girrVegaDependencies = FrtbSensitivityBuilder.buildGirrVegaDependencies(
                volatilitySurface,
                valuationCurrency,
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
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);
        return list;
    }

    /**
     * 商品 SharkFin 产品公共 FRTB 输出模板。
     * 统一收口 FX Delta、GIRR Delta/Curvature、CMTY Delta/Vega/Curvature。
     */
    protected List<FrtbSenes> buildCmtyFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String valuationCurrency,
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
                collectFxRiskCurrencies(valuationCurrency));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(discountCurve, valuationCurrency);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                info.instrumentId,
                getFrtbInstrumentCurrency(),
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
                getFrtbInstrumentCurrency(),
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                null);
        list.addAll(cmtySensitivities);
        return list;
    }

    public M calc() {
        M measure = calc(this.marketData);
        if ("SUCCESS".equalsIgnoreCase(measure.status)) {
            postProcessOptionOutput(measure);
        }
        return measure;
    }

    public M calc(MarketData marketData) {
        M measure = newMeasure();
        List<String> errors = validateCommon();
        errors.addAll(validateSpecificInputs(marketData));
        if (!errors.isEmpty()) {
            return buildErrorMeasure(measure, errors);
        }

        try {
            int days = (int) ChronoUnit.DAYS.between(info.startDate, info.maturityDate);
            double t = yearFrac(dataDate, info.maturityDate);
            double ts = yearFrac(dataDate, info.settleDate);

            MarketContext ctx = buildMarketContext(marketData, days, t);
            if (!Double.isFinite(ctx.s) || ctx.s <= 0) {
                return buildErrorMeasure(measure, "SPOT_PRICE 无效: " + ctx.s);
            }
            if (ctx.volCurve == null || ctx.volCurve.isEmpty()) {
                return buildErrorMeasure(measure, "VOL_CURVE 为空");
            }

            // 填充定价上下文
            mS = ctx.s;
            mRd = ctx.rd;
            mRf = ctx.rf;
            mRebase = ctx.rebase;
            mT = t;
            mTs = ts;
            mDays = days;
            mVolCurve = ctx.volCurve;
            mIsDouble = isDoubleType(info.optionType);
            mIsUp = isUpDirection(info.optionType);
            mVvFlag = Boolean.TRUE.equals(info.vvFlag);

            // sigma 校准
            resolveSigma();

            // 定价
            PricingResult pricingResult = priceWithResult(mS, 0.0, 0.0);
            measure.valuationUnit = pricingResult.value;
            measure.valuation = measure.valuationUnit * pos;
            measure.valuationCny = measure.valuation * ctx.fxToCny;
            measure.instrumentId = info.instrumentId;
            measure.productCode = info.productCode;
            measure.dataDate = dataDate;
            measure.position = pos;
            measure.valuationCcy = getValuationCurrency();

            measure.spotPrice = ctx.s;
            measure.fwdPrice = ctx.f;

            measure.delta = calcDelta();
            measure.gamma = calcGamma();
            measure.vega = calcVega();
            measure.theta = calcTheta();

            measure.status = "SUCCESS";
            measure.logs = new ArrayList<>();
            measure.detail = buildDetail(pricingResult);
            return measure;
        } catch (Exception ex) {
            return buildErrorMeasure(measure, "计算异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    // ===== sigma 校准 =====

    /**
     * 通过 EurOptUtil 获取障碍/行权口径 sigma 并缓存。
     * 双障碍模式：缓存上障碍、下障碍、行权价 3 个 sigma，并额外缓存双边障碍腿的 ATM sigma。
     * 其中双边 barrier 腿使用 ATM sigma，EuroSingle 腿继续使用行权 sigma。
     * 单障碍模式：校准障碍方向的 sigma 和行权价 sigma 共 2 个。
     */
    private void resolveSigma() {
        boolean cash = false;
        double upB = nz(info.upBarrierPrice, 0.0);
        double downB = nz(info.downBarrierPrice, 0.0);
        double strike = nz(info.strikePrice, 1.0);

        if (mIsDouble) {
            EurOptUtil upVol = new EurOptUtil(true, cash, mS, upB, mRd, mRf, mT, mTs, mVolCurve, "black");
            sigmaUp = upVol.getSigma();
            EurOptUtil downVol = new EurOptUtil(true, cash, mS, downB, mRd, mRf, mT, mTs, mVolCurve, "black");
            sigmaDown = downVol.getSigma();
            EurOptUtil strikeVol = new EurOptUtil(true, cash, mS, strike, mRd, mRf, mT, mTs, mVolCurve, "black");
            sigmaStrike = strikeVol.getSigma();
            sigmaDoubleBarrier = resolveAtmSigma(mVolCurve);
        } else {
            double h = mIsUp ? upB : downB;
            EurOptUtil barVol = new EurOptUtil(mIsUp, cash, mS, h, mRd, mRf, mT, mTs, mVolCurve, "black");
            sigmaUp = barVol.getSigma();
            EurOptUtil strikeVol = new EurOptUtil(mIsUp, cash, mS, strike, mRd, mRf, mT, mTs, mVolCurve, "black");
            sigmaStrike = strikeVol.getSigma();
            sigmaDoubleBarrier = sigmaUp;
        }

    }

    // ===== Greeks 希腊值 =====

    /** 冲击幅度：比例冲击 eps × s（与原 SharkFinUtil 默认 absFlag=false 一致） */
    private double spotShift() {
        return DEFAULT_EPS * mS;
    }

    /** Delta：即期价格变动的一阶敏感性 */
    private double calcDelta() {
        double shift = spotShift();
        double vUp = priceWith(mS + shift, 0, 0);
        if (mS - shift <= 0) {
            double vMid = priceWith(mS, 0, 0);
            return (vUp - vMid) / shift;
        }
        double vDown = priceWith(mS - shift, 0, 0);
        return (vUp - vDown) / (2 * shift);
    }

    /** Gamma：即期价格变动的二阶敏感性 */
    private double calcGamma() {
        double shift = spotShift();
        double vMid = priceWith(mS, 0, 0);
        double vUp = priceWith(mS + shift, 0, 0);
        if (mS - shift <= 0) {
            double vUp2 = priceWith(mS + 2 * shift, 0, 0);
            return (vUp2 - 2 * vUp + vMid) / (shift * shift);
        }
        double vDown = priceWith(mS - shift, 0, 0);
        return (vUp - 2 * vMid + vDown) / (shift * shift);
    }

    /** Vega：波动率变动的一阶敏感性（sigma ±0.001 中央差分） */
    private double calcVega() {
        double shift = 0.001;
        double vUp = priceWith(mS, shift, 0);
        double vDown = priceWith(mS, -shift, 0);
        return (vUp - vDown) / (2 * shift * 100);
    }

    /** Theta：时间推移一天的价值变化 */
    private double calcTheta() {
        double shift = 1.0 / 365.0;
        return priceWith(mS, 0, -shift) - priceWith(mS, 0, 0);
    }

    // ===== 组合定价核心 =====

    /**
     * 以变动后的模型参数重新构建期权组合并定价。
     * 使用已校准缓存的 sigma 加上 sigmaShift。
     *
     * VV 架构说明：SharkFin 组合包含 barrier 腿和 EuroSingle 期权腿两类，
     * 各腿使用不同 VV strike（barrier 用障碍价口径，期权用行权价口径），
     * 且 noTouch 概率按腿分别计算。
     * 因此 VV 在此方法内部按腿独立叠加，而非使用 BarOptUtil 内置 VV 构造函数。
     *
     * @param sNew       冲击后的即期价格
     * @param sigmaShift sigma 加性冲击（叠加到各缓存 sigma 上）
     * @param tShift     时间加性冲击（负值表示时间推进）
     */
    private double priceWith(double sNew, double sigmaShift, double tShift) {
        return priceWithResult(sNew, sigmaShift, tShift).value;
    }

    /**
     * 返回主估值结果对象，供 detail 直接读取。
     */
    private PricingResult priceWithResult(double sNew, double sigmaShift, double tShift) {
        PricingResult result = new PricingResult();
        double tAdj = Math.max(1e-8, mT + tShift);
        double tsAdj = Math.max(1e-8, mTs + tShift);
        double notional = nz(info.notional, 0.0);
        double touchRate = nz(info.touchRate, 0.0);
        double baseRate = nz(info.baseRate, 0.0);
        double upB = nz(info.upBarrierPrice, 0.0);
        double downB = nz(info.downBarrierPrice, 0.0);
        double strike = nz(info.strikePrice, 1.0);
        double rebate = notional * (touchRate - baseRate) * mDays / 365.0;
        double barrierLeg = 0.0;
        double singleLeg = 0.0;
        boolean applyNonNegativeFloor = mVvFlag && isNonNegativeFloorEnabled();

        if (mIsDouble) {
            // 上/下障碍 sigma 仅用于各自单边 no-touch 概率（EuroSingle VV 计算）。
            double sigmaUpAdj = sigmaUp + sigmaShift;
            double sigmaDownAdj = sigmaDown + sigmaShift;
            // 双边障碍腿估值与 VV 调整统一使用 ATM sigma。
            double sigmaBarAdj = sigmaDoubleBarrier + sigmaShift;
            double sigmaStrikeAdj = sigmaStrike + sigmaShift;

            // 双边模式下 barrier 腿改为单一双边障碍，避免两条单边叠加造成路径重复计数。
            BarOptUtil doubleBar = new BarOptUtil(sNew, rebate, 0.0, downB, upB,
                    mRd, mRf, mRebase, sigmaBarAdj, tAdj, null, false, false, "double_Barrier");
            double barValue = doubleBar.getValue();
            double doubleNoTouch = doubleBar.noTouchProb();
            result.upNoTouchProb = doubleNoTouch;
            result.downNoTouchProb = doubleNoTouch;

            double rebate1 = notional * mDays / 365.0 / strike;
            EuroSingleUtil upSingle = new EuroSingleUtil(sNew, strike, upB, mRd, mRf, mRebase,
                    sigmaStrikeAdj, tAdj, tsAdj, "up", true, true);
            EuroSingleUtil downSingle = new EuroSingleUtil(sNew, strike, downB, mRd, mRf, mRebase,
                    sigmaStrikeAdj, tAdj, tsAdj, "down", true, false);
            double upSingleValue = upSingle.getValue();
            double downSingleValue = downSingle.getValue();

            if (mVvFlag) {
                double barrierVvAdj = BarOptUtil.computeScaledVvAdjustment(
                        sNew, strike, 0.0, downB, upB,
                        rebate, mRd, mRf, mRebase, sigmaBarAdj, tAdj,
                        null, false, false, "double_Barrier",
                        mVolCurve, true, doubleNoTouch);
                barValue += barrierVvAdj;
                result.upBarrierVvAdj = barrierVvAdj;
                result.downBarrierVvAdj = barrierVvAdj;

                // EuroSingle 两条腿逻辑保持不变，VV 仍按各自单边 no-touch 口径计算。
                double upNoTouch = BarOptUtil.noTouchProb(sNew, mRd, mRf, mRebase,
                        sigmaUpAdj, tAdj, 0, 0, upB, "up", "Single_Barrier");
                double downNoTouch = BarOptUtil.noTouchProb(sNew, mRd, mRf, mRebase,
                        sigmaDownAdj, tAdj, 0, 0, downB, "down", "Single_Barrier");

                double upSingleVvAdj = calcEuroSingleLegVvAdjustment(
                        sNew, strike, upB,
                        mRd, mRf, mRebase, sigmaStrikeAdj, tAdj, tsAdj,
                        "up", true, true, upNoTouch);
                double downSingleVvAdj = calcEuroSingleLegVvAdjustment(
                        sNew, strike, downB,
                        mRd, mRf, mRebase, sigmaStrikeAdj, tAdj, tsAdj,
                        "down", true, false, downNoTouch);
                upSingleValue += upSingleVvAdj;
                downSingleValue += downSingleVvAdj;
                result.upSingleVvAdj = upSingleVvAdj;
                result.downSingleVvAdj = downSingleVvAdj;
            }
            barValue = applyFloorIfNeeded(barValue, applyNonNegativeFloor);
            upSingleValue = applyFloorIfNeeded(upSingleValue, applyNonNegativeFloor);
            downSingleValue = applyFloorIfNeeded(downSingleValue, applyNonNegativeFloor);
            barrierLeg += barValue;
            singleLeg += (upSingleValue + downSingleValue) * rebate1;
        } else {
            String direction = mIsUp ? "up" : "down";
            double h = mIsUp ? upB : downB;
            double sigmaBarAdj = sigmaUp + sigmaShift;
            double sigmaStrikeAdj = sigmaStrike + sigmaShift;

            BarOptUtil bar = new BarOptUtil(sNew, rebate, h, 0, 0,
                    mRd, mRf, mRebase, sigmaBarAdj, tAdj, direction, false, false, "single_barrier");
            double barValue = bar.getValue();

            double rebate1 = notional * mDays / 365.0 / strike;
            EuroSingleUtil single = new EuroSingleUtil(sNew, strike, h, mRd, mRf, mRebase,
                    sigmaStrikeAdj, tAdj, tsAdj, direction, true, mIsUp);
            double singleValue = single.getValue();

            double noTouch = BarOptUtil.noTouchProb(sNew, mRd, mRf, mRebase,
                    sigmaBarAdj, tAdj, 0, 0, h, direction, "Single_Barrier");
            if (mIsUp) {
                result.upNoTouchProb = noTouch;
            } else {
                result.downNoTouchProb = noTouch;
            }
            if (mVvFlag) {
                double barrierVvAdj = BarOptUtil.computeScaledVvAdjustment(sNew, h, h, Double.NaN, Double.NaN,
                        rebate, mRd, mRf, mRebase, sigmaBarAdj, tAdj, direction, false, false, "single_barrier",
                        mVolCurve, false, noTouch);
                double singleVvAdj = calcEuroSingleLegVvAdjustment(
                        sNew, strike, h,
                        mRd, mRf, mRebase, sigmaStrikeAdj, tAdj, tsAdj,
                        direction, true, mIsUp, noTouch);
                barValue += barrierVvAdj;
                singleValue += singleVvAdj;
                if (mIsUp) {
                    result.upBarrierVvAdj = barrierVvAdj;
                    result.upSingleVvAdj = singleVvAdj;
                } else {
                    result.downBarrierVvAdj = barrierVvAdj;
                    result.downSingleVvAdj = singleVvAdj;
                }
            }
            barValue = applyFloorIfNeeded(barValue, applyNonNegativeFloor);
            singleValue = applyFloorIfNeeded(singleValue, applyNonNegativeFloor);
            barrierLeg += barValue;
            singleLeg += singleValue * rebate1;
        }

        double fixedLeg = Math.exp(-mRebase * tAdj) * notional * baseRate * tAdj;
        result.value = barrierLeg + singleLeg + fixedLeg;
        return result;
    }

    /**
     * EuroSingle 腿的 VV 调整：先按腿数值求 Greeks，再调用统一 VV 核心。
     */
    private double calcEuroSingleLegVvAdjustment(double s, double strike, double barrier,
            double rd, double rf, double rds, double sigma, double t, double ts,
            String direction, boolean knockout, boolean callOption, double noTouchProb) {
        double[] legGreeks = calcEuroSingleLegGreeks(s, strike, barrier, rd, rf, rds, sigma, t, ts,
                direction, knockout, callOption);
        return VannaVolgaAdjuster.adjustWithExoticGreeks(
                s, strike, rd, rf, sigma, t, mVolCurve, false, noTouchProb,
                legGreeks[0], legGreeks[1], legGreeks[2]);
    }

    /**
     * EuroSingle 腿级 Greeks（Vega/Vanna/Volga）数值计算。
     */
    private static double[] calcEuroSingleLegGreeks(double s, double strike, double barrier,
            double rd, double rf, double rds, double sigma, double t, double ts,
            String direction, boolean knockout, boolean callOption) {
        double sMid = Math.max(MIN_POSITIVE, s);
        double sigmaMid = Math.max(MIN_POSITIVE, sigma);
        double ds = Math.max(MIN_POSITIVE, Math.abs(sMid) * VV_SPOT_SHIFT_RATIO);
        double sUp = sMid + ds;
        double sDown = Math.max(MIN_POSITIVE, sMid - ds);
        double dsEff = sUp - sDown;
        if (dsEff <= 0) {
            dsEff = ds;
            sDown = sMid;
        }

        double dSigma = Math.min(VV_SIGMA_SHIFT, sigmaMid * 0.5);
        dSigma = Math.max(MIN_POSITIVE, dSigma);
        double sigmaUp = sigmaMid + dSigma;
        double sigmaDown = Math.max(MIN_POSITIVE, sigmaMid - dSigma);
        double dSigmaEff = sigmaUp - sigmaDown;
        if (dSigmaEff <= 0) {
            return new double[] { 0.0, 0.0, 0.0 };
        }

        double vMid = priceEuroSingleLeg(sMid, strike, barrier, rd, rf, rds, sigmaMid, t, ts, direction, knockout,
                callOption);
        double vSigmaUp = priceEuroSingleLeg(sMid, strike, barrier, rd, rf, rds, sigmaUp, t, ts, direction, knockout,
                callOption);
        double vSigmaDown = priceEuroSingleLeg(sMid, strike, barrier, rd, rf, rds, sigmaDown, t, ts, direction,
                knockout, callOption);
        double vega = (vSigmaUp - vSigmaDown) / dSigmaEff;

        double vUpSigmaUp = priceEuroSingleLeg(sUp, strike, barrier, rd, rf, rds, sigmaUp, t, ts, direction, knockout,
                callOption);
        double vUpSigmaDown = priceEuroSingleLeg(sUp, strike, barrier, rd, rf, rds, sigmaDown, t, ts, direction,
                knockout, callOption);
        double vDownSigmaUp = priceEuroSingleLeg(sDown, strike, barrier, rd, rf, rds, sigmaUp, t, ts, direction,
                knockout, callOption);
        double vDownSigmaDown = priceEuroSingleLeg(sDown, strike, barrier, rd, rf, rds, sigmaDown, t, ts, direction,
                knockout, callOption);
        double vanna = (vUpSigmaUp - vUpSigmaDown - vDownSigmaUp + vDownSigmaDown) / (dsEff * dSigmaEff);

        double volga = secondDerivativeAtMiddle(sigmaDown, vSigmaDown, sigmaMid, vMid, sigmaUp, vSigmaUp);
        if (!Double.isFinite(vega)) {
            vega = 0.0;
        }
        if (!Double.isFinite(vanna)) {
            vanna = 0.0;
        }
        if (!Double.isFinite(volga)) {
            volga = 0.0;
        }
        return new double[] { vega, vanna, volga };
    }

    /**
     * 通过三点插值计算中间点二阶导，支持非等距步长。
     */
    private static double secondDerivativeAtMiddle(double x0, double f0, double x1, double f1, double x2, double f2) {
        double d01 = x0 - x1;
        double d02 = x0 - x2;
        double d10 = x1 - x0;
        double d12 = x1 - x2;
        double d20 = x2 - x0;
        double d21 = x2 - x1;
        if (Math.abs(d01) < MIN_POSITIVE || Math.abs(d02) < MIN_POSITIVE
                || Math.abs(d10) < MIN_POSITIVE || Math.abs(d12) < MIN_POSITIVE
                || Math.abs(d20) < MIN_POSITIVE || Math.abs(d21) < MIN_POSITIVE) {
            return 0.0;
        }
        return 2.0 * (f0 / (d01 * d02) + f1 / (d10 * d12) + f2 / (d20 * d21));
    }

    /**
     * EuroSingle 单腿估值入口。
     */
    private static double priceEuroSingleLeg(double s, double strike, double barrier,
            double rd, double rf, double rds, double sigma, double t, double ts,
            String direction, boolean knockout, boolean callOption) {
        EuroSingleUtil leg = new EuroSingleUtil(s, strike, barrier, rd, rf, rds, sigma, t, ts,
                direction, knockout, callOption);
        return leg.getValue();
    }

    // ===== 辅助方法 =====

    private M buildErrorMeasure(M measure, String error) {
        ArrayList<String> errors = new ArrayList<>();
        errors.add(error);
        return buildErrorMeasure(measure, errors);
    }

    private M buildErrorMeasure(M measure, List<String> errors) {
        measure.instrumentId = info.instrumentId;
        measure.productCode = info.productCode;
        measure.dataDate = dataDate;
        measure.position = pos;
        measure.valuationCcy = getValuationCurrency();
        measure.status = "ERROR";
        measure.logs = Measure.errorLogs(errors);
        measure.detail = null;
        measure.cashFlowList = null;
        measure.sensitivityList = null;
        return measure;
    }

    /**
     * 估值/输出币种：统一使用 CURRENCY_CODE。
     */
    protected String getValuationCurrency() {
        if (hasText(info.currencyCode)) {
            return info.currencyCode.trim();
        }
        return null;
    }

    /**
     * FRTB 计量币种与估值输出币种保持一致。
     */
    protected String getFrtbInstrumentCurrency() {
        return getValuationCurrency();
    }

    /** 构建附加明细。 */
    protected Map<String, Object> buildDetail(PricingResult pricingResult) {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("OPTION_TYPE", info.optionType);
        detail.put("STRIKE_PRICE", info.strikePrice);
        detail.put("DOWN_BARRIER", info.downBarrierPrice);
        detail.put("UPPER_BARRIER", info.upBarrierPrice);
        detail.put("BASE_RATE", info.baseRate);
        detail.put("TOUCH_RATE", info.touchRate);
        detail.put("SIGMA_UP", sigmaUp);
        detail.put("SIGMA_DOWN", sigmaDown);
        detail.put("SIGMA_STRIKE", sigmaStrike);
        detail.put("SIGMA_DOUBLE_BARRIER", sigmaDoubleBarrier);
        if (Double.isFinite(pricingResult.upNoTouchProb)) {
            detail.put("UP_NO_TOUCH_PROB", pricingResult.upNoTouchProb);
        }
        if (Double.isFinite(pricingResult.downNoTouchProb)) {
            detail.put("DOWN_NO_TOUCH_PROB", pricingResult.downNoTouchProb);
        }
        if (mVvFlag) {
            if (Double.isFinite(pricingResult.upBarrierVvAdj)) {
                detail.put("UP_BARRIER_VV_ADJ", pricingResult.upBarrierVvAdj);
            }
            if (Double.isFinite(pricingResult.downBarrierVvAdj)) {
                detail.put("DOWN_BARRIER_VV_ADJ", pricingResult.downBarrierVvAdj);
            }
            if (Double.isFinite(pricingResult.upSingleVvAdj)) {
                detail.put("UP_SINGLE_VV_ADJ", pricingResult.upSingleVvAdj);
            }
            if (Double.isFinite(pricingResult.downSingleVvAdj)) {
                detail.put("DOWN_SINGLE_VV_ADJ", pricingResult.downSingleVvAdj);
            }
        }
        return detail;
    }

    protected static double impliedRf(double s, double f, double t, double rd) {
        if (s <= 0 || f <= 0 || t <= 0) {
            return rd;
        }
        return -Math.log(f / s) / t + rd;
    }

    protected static double nz(Double v, double dft) {
        return v == null ? dft : v;
    }

    /**
     * 双边障碍腿 sigma 口径：按 Delta=0.5 从波动率曲线插值获取 ATM sigma。
     */
    private static double resolveAtmSigma(List<VolSurfacePoint> volCurve) {
        if (volCurve == null || volCurve.isEmpty()) {
            return 0.2;
        }
        Double[] deltas = volCurve.stream()
                .map(VolSurfacePoint::getAxis2Value)
                .toArray(Double[]::new);
        Double[] vols = volCurve.stream()
                .map(VolSurfacePoint::getVolatilityRate)
                .toArray(Double[]::new);
        double sigma = com.zcyh.mr.math.Interpolation.interpolate(deltas, vols, 0.5,
                VolUtil.requireAxis2InterpolateType(volCurve));
        if (Double.isFinite(sigma) && sigma > 0.0) {
            return sigma;
        }
        double sum = 0.0;
        int count = 0;
        for (VolSurfacePoint item : volCurve) {
            if (item == null) {
                continue;
            }
            double vol = item.getVolatilityRate();
            if (Double.isFinite(vol) && vol > 0.0) {
                sum += vol;
                count++;
            }
        }
        if (count == 0) {
            return 0.2;
        }
        return sum / count;
    }

    /**
     * VV 开启时统一读取非负兜底开关。
     */
    protected static boolean isNonNegativeFloorEnabled() {
        return EngineConfiguration.getInstance()
                .getRequiredBoolean(EngineConstants.CFG.VV_NON_NEGATIVE_FLOOR_ENABLED);
    }

    /**
     * 腿级非负兜底，避免 VV 调整在极端场景下产生负的期权腿价格。
     */
    protected static double applyFloorIfNeeded(double value, boolean enabled) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (!enabled) {
            return value;
        }
        return Math.max(0.0, value);
    }

    protected static double yearFrac(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        return Math.max(days / 365.0, 1.0 / 365.0);
    }

    protected static boolean isDoubleType(String optionType) {
        return "double".equalsIgnoreCase(optionType);
    }

    protected static boolean isUpDirection(String optionType) {
        return isTrue(optionType, true);
    }

    protected static boolean isTrue(String text, boolean dft) {
        if (text == null) {
            return dft;
        }
        String t = text.trim();
        if ("true".equalsIgnoreCase(t) || "up".equalsIgnoreCase(t) || "call".equalsIgnoreCase(t)) {
            return true;
        }
        if ("false".equalsIgnoreCase(t) || "down".equalsIgnoreCase(t) || "put".equalsIgnoreCase(t)) {
            return false;
        }
        return dft;
    }

    private List<String> validateCommon() {
        ArrayList<String> errors = new ArrayList<>();
        if (!hasText(info.instrumentId)) {
            errors.add("INSTRUMENT_ID 不能为空");
        }
        if (!hasText(info.productCode)) {
            errors.add("PRODUCT_CODE 不能为空");
        }
        if (!"B".equalsIgnoreCase(info.buyOrSell) && !"S".equalsIgnoreCase(info.buyOrSell)) {
            errors.add("BUY_OR_SELL 仅支持 B/S");
        }
        if (info.contractSize != null
                && (!Double.isFinite(info.contractSize) || info.contractSize <= 0.0)) {
            errors.add("CONTRACT_SIZE 必须为正有限数");
        }
        if (info.startDate == null || info.maturityDate == null || info.settleDate == null) {
            errors.add("START_DATE/MATURITY_DATE/SETTLE_DATE 不能为空");
        } else {
            if (info.maturityDate.isBefore(info.startDate)) {
                errors.add("MATURITY_DATE 不能早于 START_DATE");
            }
            if (info.settleDate.isBefore(info.maturityDate)) {
                errors.add("SETTLE_DATE 不能早于 MATURITY_DATE");
            }
        }
        if (!hasText(info.optionType)) {
            errors.add("OPTION_TYPE 不能为空");
        } else if (!isSupportedOptionType(info.optionType)) {
            errors.add("OPTION_TYPE 非法，仅支持 Call/Put/Up/Down/True/False/Double");
        }

        if (info.notional == null || !Double.isFinite(info.notional) || info.notional < 0) {
            errors.add("NOTIONAL 必须为非负有限数");
        }
        if (info.strikePrice == null || !Double.isFinite(info.strikePrice) || info.strikePrice <= 0) {
            errors.add("STRIKE_PRICE 必须为正数");
        }

        String optionType = normalizeOptionType(info.optionType);
        if ("double".equals(optionType)) {
            if (info.downBarrierPrice == null || info.upBarrierPrice == null) {
                errors.add("Double 模式必须同时提供 DOWN_BARRIER 与 UPPER_BARRIER");
            } else if (!(info.upBarrierPrice > info.downBarrierPrice)) {
                errors.add("Double 模式要求 UPPER_BARRIER > DOWN_BARRIER");
            }
        } else if ("up".equals(optionType)) {
            if (info.upBarrierPrice == null) {
                errors.add("Up/Call/True 模式必须提供 UPPER_BARRIER");
            }
        } else if ("down".equals(optionType)) {
            if (info.downBarrierPrice == null) {
                errors.add("Down/Put/False 模式必须提供 DOWN_BARRIER");
            }
        }
        return errors;
    }

    private static boolean isSupportedOptionType(String optionType) {
        String t = normalizeOptionType(optionType);
        return "double".equals(t) || "up".equals(t) || "down".equals(t);
    }

    private static String normalizeOptionType(String optionType) {
        if (optionType == null) {
            return "";
        }
        String t = optionType.trim().toLowerCase();
        if ("double".equals(t)) {
            return "double";
        }
        if ("up".equals(t) || "call".equals(t) || "true".equals(t)) {
            return "up";
        }
        if ("down".equals(t) || "put".equals(t) || "false".equals(t)) {
            return "down";
        }
        return "";
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    public static class MarketContext {
        public double s;
        public double f;
        public double rd;
        public double rf;
        public double rebase;
        public double fxToCny;
        public List<VolSurfacePoint> volCurve;
    }

    public static class SharkFinBaseTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"Call", "Put", "Up", "Down", "True", "False", "Double"}, ignoreCase = true)
        @JSONField(name = "OPTION_TYPE")
        public String optionType;
        @ProductInputField(required = true)
        @JSONField(name = "TOUCH_RATE")
        public Double touchRate;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_RATE")
        public Double baseRate;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "START_DATE", format = "yyyy-MM-dd")
        public LocalDate startDate;
        @JSONField(name = "SETTLE_TYPE")
        public String settleType;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField(required = true)
        @JSONField(name = "DOWN_BARRIER")
        public Double downBarrierPrice;
        @ProductInputField(required = true)
        @JSONField(name = "UPPER_BARRIER")
        public Double upBarrierPrice;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyy-MM-dd")
        public LocalDate settleDate;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @ProductInputField(required = true)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        /** VV 开关：true 时启用 Vanna-Volga overhedge 调整 */
        @JSONField(name = "VV_FLAG", deserializeUsing = BooleanInputReader.class)
        public Boolean vvFlag;
    }
}

