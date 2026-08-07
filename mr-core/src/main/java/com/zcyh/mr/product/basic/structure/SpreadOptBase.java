package com.zcyh.mr.product.basic.structure;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.validation.BooleanInputReader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolSurfacePoint;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.product.basic.option.EurOptUtil;
import com.zcyh.mr.product.basic.option.OptUtil;
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
 * Spread Option 公共基类。
 * 组合结构：两腿欧式 BS（k1/k2）差价定价。
 * sigma 校准、组合定价和 Greeks 计算均在 Base 层完成，与 SharkFinBase 模式对齐。
 * 支持 Vanna-Volga overhedge 调整（按腿独立叠加）。
 */
public abstract class SpreadOptBase<T extends SpreadOptBase.SpreadOptBaseTradeInfo, M extends OptionMeasure> {

    protected final LocalDate dataDate;
    protected final T info;
    protected final MarketData marketData;
    protected final double pos;

    /** Greeks 默认扰动步长（比例冲击 eps × s） */
    private static final double DEFAULT_EPS = 0.001;

    // ===== sigma 缓存（由 resolveSigma 填充） =====
    private double sigma1;
    private double sigma2;

    // ===== 定价上下文（由 calc 填充，供 priceWith / Greeks 使用） =====
    private double mS, mRd, mRf, mT, mTs;
    private double mK1, mK2, mRebate;
    private boolean mCall, mCash, mVvFlag;
    private List<VolSurfacePoint> mVolCurve;

    protected SpreadOptBase(LocalDate dataDate, T info, MarketData marketData) {
        this.dataDate = dataDate;
        this.info = info;
        this.marketData = marketData;
        this.pos = ("B".equalsIgnoreCase(info.buyOrSell) ? 1.0 : -1.0) * nz(info.contractSize, 1.0);
    }

    protected abstract M newMeasure();

    protected abstract MarketContext buildMarketContext(MarketData marketData, int days, double t);

    protected abstract List<String> validateSpecific();

    protected abstract String getValuationCcy();

    /**
     * 外汇 Spread 产品公共 FRTB 输出模板。
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
     * 权益 Spread 产品公共 FRTB 输出模板。
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
     * 利率 Spread 产品公共 FRTB 输出模板。
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
     * 商品 Spread 产品公共 FRTB 输出模板。
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

    public M calc() {
        return calc(this.marketData);
    }

    public M calc(MarketData marketData) {
        M measure = newMeasure();
        List<String> errors = validateCommon();
        errors.addAll(validateSpecific());
        if (!errors.isEmpty()) {
            return buildErrorMeasure(measure, errors);
        }

        try {
            int days = (int) ChronoUnit.DAYS.between(info.startDate, info.maturityDate);
            double t = yearFrac(dataDate, info.maturityDate);
            double ts = yearFrac(dataDate, info.settleDate);
            MarketContext ctx = buildMarketContext(marketData, days, t);
            ctx.call = isCallOptionType(info.optionType);

            if (!Double.isFinite(ctx.s) || ctx.s <= 0.0) {
                return buildErrorMeasure(measure, "SPOT_PRICE 无效: " + ctx.s);
            }
            if (!Double.isFinite(ctx.f) || ctx.f <= 0.0) {
                return buildErrorMeasure(measure, "FWD_PRICE 无效: " + ctx.f);
            }
            if (!Double.isFinite(ctx.rd) || !Double.isFinite(ctx.rf)) {
                return buildErrorMeasure(measure, "利率参数无效: rd=" + ctx.rd + ", rf=" + ctx.rf);
            }
            if (!Double.isFinite(ctx.fxToCny) || ctx.fxToCny <= 0.0) {
                return buildErrorMeasure(measure, "FX_TO_CNY 无效: " + ctx.fxToCny);
            }
            if (ctx.volCurve == null || ctx.volCurve.isEmpty()) {
                return buildErrorMeasure(measure, "VOL_CURVE 为空");
            }

            // 填充定价上下文
            mS = ctx.s;
            mRd = ctx.rd;
            mRf = ctx.rf;
            mT = t;
            mTs = ts;
            mK1 = nz(info.downBarrierPrice, 0.0);
            mK2 = nz(info.upBarrierPrice, 0.0);
            mRebate = nz(info.notional, 0.0) * days / 365.0 * nz(info.initialPrice, 0.0);
            mCall = ctx.call;
            mCash = ctx.cash;
            mVolCurve = ctx.volCurve;
            mVvFlag = Boolean.TRUE.equals(info.vvFlag);

            // sigma 校准
            resolveSigma();

            // 定价
            measure.valuationUnit = priceWith(mS, 0.0, 0.0);
            measure.valuation = measure.valuationUnit * pos;
            measure.valuationCny = measure.valuation * ctx.fxToCny;
            measure.spotPrice = ctx.s;
            measure.fwdPrice = ctx.f;
            measure.impliedVol = avgPositive(sigma1, sigma2);
            measure.delta = calcDelta();
            measure.gamma = calcGamma();
            measure.vega = calcVega();
            measure.theta = calcTheta();
            measure.detail = buildDetail();

            measure.instrumentId = info.instrumentId;
            measure.productCode = info.productCode;
            measure.dataDate = dataDate;
            measure.position = pos;
            measure.valuationCcy = getValuationCcy();
            measure.status = "SUCCESS";
            measure.logs = new ArrayList<>();
            return measure;
        } catch (Exception ex) {
            return buildErrorMeasure(
                    measure,
                    "计算异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    // ===== sigma 校准 =====

    /**
     * 通过 EurOptUtil 迭代 goalSeek 获取各行权价的 sigma 并缓存。
     * sigma1 对应下界行权价 k1，sigma2 对应上界行权价 k2。
     */
    private void resolveSigma() {
        EurOptUtil u1 = new EurOptUtil(mCall, mCash, mS, mK1, mRd, mRf, mT, mTs, mVolCurve, "black");
        sigma1 = u1.getSigma();
        EurOptUtil u2 = new EurOptUtil(mCall, mCash, mS, mK2, mRd, mRf, mT, mTs, mVolCurve, "black");
        sigma2 = u2.getSigma();
    }

    // ===== Greeks 希腊值 =====

    /** 冲击幅度：比例冲击 eps × s */
    private double spotShift() {
        return DEFAULT_EPS * mS;
    }

    /** Delta：即期价格变动的一阶敏感性 */
    private double calcDelta() {
        double shift = spotShift();
        double vUp = priceWith(mS + shift, 0, 0);
        if (mS - shift <= 0) {
            return (vUp - priceWith(mS, 0, 0)) / shift;
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

    /** Vega：sigma ±0.001 中央差分 */
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
     * 使用缓存 sigma + 冲击参数 reprice。
     * 直接调用 OptUtil.BS() 避免重复 goalSeek。
     * 当 VV_FLAG=true 时，对每腿独立叠加 VannaVolga overhedge 调整。
     *
     * @param sNew       冲击后的即期价格
     * @param sigmaShift sigma 加性冲击（叠加到各缓存 sigma 上）
     * @param tShift     时间加性冲击（负值表示时间推进）
     */
    private double priceWith(double sNew, double sigmaShift, double tShift) {
        double tAdj = Math.max(1e-8, mT + tShift);
        double tsAdj = Math.max(1e-8, mTs + tShift);
        double s1Adj = sigma1 + sigmaShift;
        double s2Adj = sigma2 + sigmaShift;

        double v1 = OptUtil.BS(mCall, mCash, sNew, mK1, mRd, mRf, s1Adj, tAdj, tsAdj, "black");
        double v2 = OptUtil.BS(mCall, mCash, sNew, mK2, mRd, mRf, s2Adj, tAdj, tsAdj, "black");

        // VV overhedge 调整：非路径依赖，noTouchProb = 1.0
        if (mVvFlag) {
            v1 += VannaVolgaAdjuster.adjust(sNew, mK1, mRd, mRf,
                    s1Adj, tAdj, mVolCurve, false, 1.0);
            v2 += VannaVolgaAdjuster.adjust(sNew, mK2, mRd, mRf,
                    s2Adj, tAdj, mVolCurve, false, 1.0);
        }

        return (v2 - v1) * mRebate;
    }

    // ===== 辅助方法 =====

    protected Map<String, Object> buildDetail() {
        LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
        detail.put("OPTION_TYPE", info.optionType);
        detail.put("DOWN_BARRIER", info.downBarrierPrice);
        detail.put("UPPER_BARRIER", info.upBarrierPrice);
        detail.put("INITIAL_PRICE", info.initialPrice);
        detail.put("NOTIONAL", info.notional);
        detail.put("SIGMA_K1", sigma1);
        detail.put("SIGMA_K2", sigma2);
        detail.put("VV_FLAG", mVvFlag);
        return detail;
    }

    private List<String> validateCommon() {
        List<String> errors = new ArrayList<>();
        if (!hasText(info.instrumentId)) {
            errors.add("INSTRUMENT_ID 未设置");
        }
        if (!hasText(info.productCode)) {
            errors.add("PRODUCT_CODE 未设置");
        }
        if (!"B".equalsIgnoreCase(info.buyOrSell) && !"S".equalsIgnoreCase(info.buyOrSell)) {
            errors.add("BUY_OR_SELL 仅支持 B/S");
        }
        if (!hasText(info.optionType)) {
            errors.add("OPTION_TYPE 未设置");
        } else if (!isValidOptionType(info.optionType)) {
            errors.add("OPTION_TYPE 仅支持 Call/Put/Up/Down/True/False");
        }
        if (!hasText(info.settleType)) {
            errors.add("SETTLE_TYPE 未设置");
        } else {
            String st = info.settleType.trim().toUpperCase();
            if (!"CASH".equals(st) && !"PHYSICAL".equals(st)) {
                errors.add("SETTLE_TYPE 仅支持 CASH/PHYSICAL");
            }
        }
        if (info.startDate == null) {
            errors.add("START_DATE 未设置");
        }
        if (info.maturityDate == null) {
            errors.add("MATURITY_DATE 未设置");
        }
        if (info.settleDate == null) {
            errors.add("SETTLE_DATE 未设置");
        }
        if (info.startDate != null && info.maturityDate != null && info.maturityDate.isBefore(info.startDate)) {
            errors.add("MATURITY_DATE 不能早于 START_DATE");
        }
        if (info.maturityDate != null && info.settleDate != null && info.settleDate.isBefore(info.maturityDate)) {
            errors.add("SETTLE_DATE 不能早于 MATURITY_DATE");
        }
        if (info.contractSize != null && (!Double.isFinite(info.contractSize) || info.contractSize <= 0.0)) {
            errors.add("CONTRACT_SIZE 必须为正数");
        }
        if (info.notional == null || !Double.isFinite(info.notional) || info.notional < 0.0) {
            errors.add("NOTIONAL 必须为非负有限数");
        }
        if (info.initialPrice == null || !Double.isFinite(info.initialPrice) || info.initialPrice < 0.0) {
            errors.add("INITIAL_PRICE 必须为非负有限数");
        }
        if (info.downBarrierPrice == null || info.upBarrierPrice == null
                || info.upBarrierPrice <= info.downBarrierPrice) {
            errors.add("执行区间无效: DOWN_BARRIER/UPPER_BARRIER");
        }
        return errors;
    }

    protected static boolean isCallOptionType(String optionType) {
        String ot = normalizeOptionType(optionType);
        if ("call".equals(ot)) {
            return true;
        }
        if ("put".equals(ot)) {
            return false;
        }
        throw new IllegalArgumentException("OPTION_TYPE 仅支持 Call/Put/Up/Down/True/False: " + optionType);
    }

    private static boolean isValidOptionType(String optionType) {
        String normalized = normalizeOptionType(optionType);
        return "call".equals(normalized) || "put".equals(normalized);
    }

    private static String normalizeOptionType(String optionType) {
        if (optionType == null) {
            return "";
        }
        String t = optionType.trim().toLowerCase();
        if ("call".equals(t) || "up".equals(t) || "true".equals(t)) {
            return "call";
        }
        if ("put".equals(t) || "down".equals(t) || "false".equals(t)) {
            return "put";
        }
        return "";
    }

    private static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    private static double avgPositive(double a, double b) {
        double sum = 0.0;
        int n = 0;
        if (Double.isFinite(a) && a > 0.0) {
            sum += a;
            n++;
        }
        if (Double.isFinite(b) && b > 0.0) {
            sum += b;
            n++;
        }
        return n > 0 ? sum / n : 0.0;
    }

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
        measure.valuationCcy = getValuationCcy();
        measure.status = "ERROR";
        measure.logs = Measure.errorLogs(errors);
        measure.detail = null;
        measure.cashFlowList = null;
        measure.sensitivityList = null;
        return measure;
    }

    protected static double nz(Double v, double dft) {
        return v == null ? dft : v;
    }

    protected static double yearFrac(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        return Math.max(days / 365.0, 1.0 / 365.0);
    }

    /** 市场参数上下文 */
    public static class MarketContext {
        public double s, f, rd, rf, fxToCny;
        public boolean call, cash;
        public List<VolSurfacePoint> volCurve;
    }

    /** 公共字段基类 */
    public static class SpreadOptBaseTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, allowedValues = {"Call", "Put"}, ignoreCase = true)
        @JSONField(name = "OPTION_TYPE")
        public String optionType;
        @ProductInputField(allowedValues = {"Call", "Put"}, ignoreCase = true)
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(requiredFor = {"IR_SPREADOPT"}, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @ProductInputField(requiredFor = {"IR_SPREADOPT"}, allowedValues = {"CASH", "PHYSICAL"}, ignoreCase = true)
        @JSONField(name = "SETTLE_TYPE")
        public String settleType;
        @ProductInputField(required = true)
        @JSONField(name = "START_DATE", format = "yyyy-MM-dd")
        public LocalDate startDate;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyy-MM-dd")
        public LocalDate settleDate;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField(required = true)
        @JSONField(name = "DOWN_BARRIER")
        public Double downBarrierPrice;
        @ProductInputField(required = true)
        @JSONField(name = "UPPER_BARRIER")
        public Double upBarrierPrice;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "INITIAL_PRICE")
        public Double initialPrice;
        @ProductInputField(requiredFor = {"EQ_SPREADOPT", "COMM_SPREADOPT", "IR_SPREADOPT"})
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @ProductInputField(requiredFor = {"FX_SPREADOPT"})
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(requiredFor = {"FX_SPREADOPT"})
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        /** VV 开关：true 时启用 Vanna-Volga overhedge 调整 */
        @JSONField(name = "VV_FLAG", deserializeUsing = BooleanInputReader.class)
        public Boolean vvFlag;
    }
}

