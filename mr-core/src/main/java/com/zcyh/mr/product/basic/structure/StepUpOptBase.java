package com.zcyh.mr.product.basic.structure;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Convert;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolUtil;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.product.basic.option.DigOptUtil;
import com.zcyh.mr.product.basic.option.EurOptUtil;
import com.zcyh.mr.product.basic.option.VannaVolgaAdjuster;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static com.zcyh.mr.product.basic.option.EurOptUtil.cdf;
import static com.zcyh.mr.product.basic.frtb.OptionBaseFrtbSupport.*;

/**
 * StepUp 交易公共基类。
 * 保持各产品 JSON 入参独立，通过子类转发公共字段，复用估值主流程。
 */
public abstract class StepUpOptBase<T, M extends OptionMeasure> {
    protected static final double YEAR_BASE = 365.0;
    protected static final double FRTB_ZERO_TOL = 1e-12;
    protected static final double DEFAULT_EPS = 0.0001;

    protected final LocalDate dataDate;
    protected final T stepUpInfo;
    protected final MarketData marketData;
    protected M stepUpMeasure;
    protected final Double pos;
    protected DigOptUtil digUtil1;
    protected DigOptUtil digUtil2;
    protected final Middle middle = new Middle();

    protected StepUpOptBase(LocalDate dataDate, T stepUpInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.stepUpInfo = stepUpInfo;
        this.marketData = marketData;
        this.pos = "B".equalsIgnoreCase(getBuyOrSell()) ? 1.0 : -1.0;
    }

    /**
     * 子类返回自己的结果类型实例，保证接口兼容。
     */
    protected abstract M newMeasure();

    /**
     * 子类构建估值所需差异参数。
     */
    protected abstract PricingContext buildPricingContext(MarketData md);

    /**
     * 子类补充特有输入校验。
     */
    protected abstract void validateSpecificInputs(MarketData md);

    // ===== 公共字段转发（由子类适配自己的 JSON Info） =====
    protected abstract String getInstrumentId();

    protected abstract String getProductCode();

    protected abstract String getCallOrPut();

    protected abstract String getBuyOrSell();

    protected abstract LocalDate getStartDate();

    protected abstract LocalDate getMaturityDate();

    protected abstract LocalDate getFixingDate();

    protected abstract Double getNotional();

    protected abstract String getCurrencyCode();

    protected abstract Double getUpperBarrier();

    protected abstract Double getLowerBarrier();

    protected abstract Double getLowRate();

    protected abstract Double getMidRate();

    protected abstract Double getHighRate();

    protected abstract String getDiscountCurve();

    protected abstract String getFixingId();

    /**
     * 获取定盘曲线 key：仅使用 FIXING_ID。
     */
    protected String resolveFixingKey() {
        return getFixingId();
    }

    protected abstract String getVolatilitySurface();

    protected String getModelType() {
        return readTextField(stepUpInfo, "modelType");
    }

    protected abstract Double getEps();

    protected abstract Boolean getAbsFlag();

    /** VV 开关：子类覆写从 info 读取 VV_FLAG。默认不启用。 */
    protected Boolean getVvFlag() {
        return null;
    }

    protected abstract void setEps(Double eps);

    protected abstract void setAbsFlag(Boolean absFlag);

    protected abstract List<FrtbSenes> getFrtbSensList();

    /**
     * ABS_FLAG 默认值：IR 子类覆写为 true，其它产品默认 false。
     */
    protected boolean defaultAbsFlag() {
        return false;
    }

    /**
     * 对可选输入字段赋默认值，避免外部传空导致估值前置失败。
     */
    protected void applyDefaultInputs() {
        if (getEps() == null) {
            setEps(DEFAULT_EPS);
        }
        if (getAbsFlag() == null) {
            setAbsFlag(defaultAbsFlag());
        }
    }

    protected boolean isCall() {
        return "call".equalsIgnoreCase(getCallOrPut());
    }

    protected boolean inFixingWindow() {
        return (dataDate.isBefore(getMaturityDate()) && dataDate.isAfter(getFixingDate()))
                || dataDate.isEqual(getFixingDate()) || dataDate.isEqual(getMaturityDate());
    }

    protected double yearFrac(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) / YEAR_BASE;
    }

    protected int dayDiff(LocalDate start, LocalDate end) {
        return (int) ChronoUnit.DAYS.between(start, end);
    }

    protected boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    protected void requireText(String value, String fieldName) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("输入字段不能为空: " + fieldName);
        }
    }

    protected void requireNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("输入字段不能为空: " + fieldName);
        }
    }

    protected double getCnyFxRate(MarketData md, String currencyCode) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(currencyCode);
    }

    protected void fillDetail(OptionMeasure measure, double domesticRho, double foreignRho,
            List<Map<String, Object>> d2List) {
        LinkedHashMap<String, Object> detail = measure.detail == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(measure.detail);
        detail.put("DOMESTIC_RHO", domesticRho);
        detail.put("FOREIGN_RHO", foreignRho);
        detail.put("D2_LIST", d2List == null ? new ArrayList<>() : d2List);
        measure.detail = detail;
    }

    private M buildErrorMeasure(Exception e) {
        M errorMeasure = newMeasure();
        errorMeasure.dataDate = dataDate;
        errorMeasure.instrumentId = getInstrumentId();
        errorMeasure.productCode = getProductCode();
        errorMeasure.position = pos == null ? 0.0 : pos;
        errorMeasure.status = "ERROR";
        String message = (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty())
                ? String.valueOf(e)
                : e.getMessage();
        errorMeasure.addErrorLog(message);
        return errorMeasure;
    }

    protected boolean isNoChangeByCny(OptionMeasure result) {
        return Math.abs(result.valuationCny - stepUpMeasure.valuationCny) < FRTB_ZERO_TOL;
    }

    private void appendFrtbSens() {
        List<FrtbSenes> list = getFrtbSensList();
        if (list == null || list.isEmpty()) {
            return;
        }
        stepUpMeasure.sensitivityList.addAll(list);
    }

    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        if (hasText(getDiscountCurve()) && hasText(getCurrencyCode())) {
            map.put(getDiscountCurve(), getCurrencyCode());
        }
        return map;
    }

    protected HashMap<String, List<String>> buildGirrBucketCurveMap(Map<String, String> girrCurveCcyMap) {
        HashMap<String, List<String>> bucketCurveMap = new HashMap<>();
        if (girrCurveCcyMap == null || girrCurveCcyMap.isEmpty()) {
            return bucketCurveMap;
        }
        for (Map.Entry<String, String> entry : girrCurveCcyMap.entrySet()) {
            String curve = entry.getKey();
            String ccy = entry.getValue();
            if (!hasText(curve) || !hasText(ccy)) {
                continue;
            }
            List<String> curves = bucketCurveMap.computeIfAbsent(ccy, k -> new java.util.ArrayList<>());
            if (!curves.contains(curve)) {
                curves.add(curve);
            }
        }
        return bucketCurveMap;
    }

    protected boolean enableGirrDelta() {
        return true;
    }

    protected boolean enableGirrCurvature() {
        return true;
    }

    protected boolean enableGirrVega() {
        return false;
    }

    protected double getGirrDeltaScale() {
        return 10000.0;
    }

    protected String getGirrVegaSurface() {
        return getVolatilitySurface();
    }

    protected LocalDate getFrtbSettleDate() {
        return getMaturityDate();
    }

    protected String getFrtbInstrumentCurrency() {
        return getCurrencyCode();
    }

    protected String getGirrVegaBucket() {
        return getCurrencyCode();
    }

    /**
     * GIRR Vega 的第二维原始输入。
     * 由具体利率产品提供，公共层负责做标准 tenor 映射。
     */
    protected String getGirrVegaSecondaryVertex() {
        return null;
    }

    /**
     * GIRR Delta 依赖由产品侧提供曲线与 bucket 信息。
     */
    protected List<FrtbDependency> collectGirrDeltaDependencies() {
        return FrtbSensitivityBuilder.buildGirrDeltaDependencies(buildGirrCurveCcyMap());
    }

    /**
     * GIRR Vega 仅声明曲面依赖；Curvature 直接复用 Delta 依赖。
     */
    protected List<FrtbDependency> collectGirrVegaDependencies() {
        return FrtbSensitivityBuilder.buildGirrVegaDependencies(
                getGirrVegaSurface(),
                getGirrVegaBucket(),
                getGirrVegaSecondaryVertex());
    }

    protected List<String> getFxRiskCurrencies() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String ccy = getCurrencyCode();
        if (hasText(ccy) && !isDomesticFxCurrency(ccy)) {
            set.add(ccy);
        }
        return new ArrayList<>(set);
    }

    protected boolean enableFxDelta() {
        return true;
    }

    protected boolean enableFxCurvature() {
        return false;
    }

    protected boolean enableFxVega() {
        return false;
    }

    protected double getFxDeltaScale() {
        return 100.0;
    }

    protected String getFxVegaSurface() {
        return getVolatilitySurface();
    }

    protected String getFxVegaBucketCurrency(List<String> fxRiskCurrencies) {
        if (fxRiskCurrencies != null && !fxRiskCurrencies.isEmpty()) {
            return fxRiskCurrencies.get(0);
        }
        return getCurrencyCode();
    }

    protected String getFxVegaCnyCurrency() {
        return getCurrencyCode();
    }

    protected String resolveOptionalBucket(String defaultBucket, String... fieldNames) {
        if (fieldNames == null || fieldNames.length == 0) {
            return defaultBucket;
        }
        for (String fieldName : fieldNames) {
            String value = readTextField(stepUpInfo, fieldName);
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
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
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

    protected List<FrtbSenes> getSensListFXCommon() {
        List<String> fxRiskCurrencies = getFxRiskCurrencies();
        if (fxRiskCurrencies.isEmpty()) {
            return new ArrayList<>();
        }
        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(fxRiskCurrencies);
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                getMaturityDate(),
                fxDeltaDependencies,
                Collections.emptyList(),
                enableFxDelta(),
                enableFxCurvature(),
                stepUpMeasure.instrumentId,
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                MeasureValuation.of(stepUpMeasure.valuation, stepUpMeasure.valuationCny),
                shockedMarketData -> toMeasureValuation(calc(shockedMarketData)));
        return fxSensitivities;
    }

    /**
     * 外汇 StepUp 产品公共 FX FRTB 输出模板。
     * 统一通过公共 builder 构建 FX Delta/Vega/Curvature，再补做产品族后处理。
     */
    protected List<FrtbSenes> buildFxFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String underlyingCurrencyCode,
            String baseCurrencyCode,
            String currencyCode,
            String volatilitySurface,
            Function<MarketData, ? extends OptionMeasure> repriceFunction) {
        List<FrtbSenes> list = new ArrayList<>();
        if (measure == null || repriceFunction == null) {
            return list;
        }
        MeasureValuation baseValuation = toMeasureValuation(measure);
        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                collectFxRiskCurrencies(underlyingCurrencyCode, baseCurrencyCode, currencyCode),
                FrtbSensitivityBuilder.buildFxPair(underlyingCurrencyCode, baseCurrencyCode));
        List<FrtbDependency> fxVegaDependencies = enableFxVega()
                ? buildFxVegaDependencies(
                        underlyingCurrencyCode,
                        baseCurrencyCode,
                        volatilitySurface)
                : new ArrayList<>();
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                settleDate,
                fxDeltaDependencies,
                fxVegaDependencies,
                enableFxDelta(),
                enableFxCurvature(),
                getInstrumentId(),
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)),
                () -> middle.newSigma = true);
        list.addAll(fxSensitivities);
        return list;
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    protected List<FrtbSenes> getSensListGIRRCommon() {
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                getFrtbSettleDate(),
                collectGirrDeltaDependencies(),
                enableGirrVega() ? collectGirrVegaDependencies() : new ArrayList<>(),
                enableGirrDelta(),
                enableGirrCurvature(),
                getInstrumentId(),
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                MeasureValuation.of(
                        stepUpMeasure.valuation,
                        stepUpMeasure.valuationCny),
                shockedMarketData -> {
                    OptionMeasure shockedMeasure = calc(shockedMarketData);
                    return MeasureValuation.of(
                            shockedMeasure.valuation,
                            shockedMeasure.valuationCny);
                },
                null,
                () -> middle.newSigma = true);
        return sensitivities;
    }

    protected void validateRequiredInputs(MarketData md) {
        requireNotNull(stepUpInfo, "TRADE_INFO");
        applyDefaultInputs();
        requireNotNull(md, "marketData");
        requireNotNull(md.irSpot, "marketData.irSpot");
        requireNotNull(md.fixingRate, "marketData.fixingRate");
        requireNotNull(md.fxSpot, "marketData.fxSpot");

        requireText(getInstrumentId(), "INSTRUMENT_ID");
        requireText(getProductCode(), "PRODUCT_CODE");
        requireText(getCallOrPut(), "CALL_OR_PUT");
        requireText(getBuyOrSell(), "BUY_OR_SELL");
        if (!"call".equalsIgnoreCase(getCallOrPut()) && !"put".equalsIgnoreCase(getCallOrPut())) {
            throw new IllegalArgumentException("CALL_OR_PUT 仅支持 call 或 put: " + getCallOrPut());
        }
        if (!"B".equalsIgnoreCase(getBuyOrSell()) && !"S".equalsIgnoreCase(getBuyOrSell())) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B 或 S: " + getBuyOrSell());
        }

        requireNotNull(getStartDate(), "START_DATE");
        requireNotNull(getMaturityDate(), "MATURITY_DATE");
        requireNotNull(getFixingDate(), "FIXING_DATE");
        if (getMaturityDate().isBefore(getStartDate())) {
            throw new IllegalArgumentException("MATURITY_DATE 不能早于 START_DATE");
        }
        if (getFixingDate().isBefore(getStartDate()) || getFixingDate().isAfter(getMaturityDate())) {
            throw new IllegalArgumentException("FIXING_DATE 必须位于 START_DATE 与 MATURITY_DATE 区间内");
        }

        requireNotNull(getNotional(), "NOTIONAL");
        if (!Double.isFinite(getNotional()) || getNotional() < 0) {
            throw new IllegalArgumentException("NOTIONAL 必须为非负有限数: " + getNotional());
        }
        requireText(getCurrencyCode(), "CURRENCY_CODE");
        double fx = getCnyFxRate(md, getCurrencyCode());
        if (!Double.isFinite(fx) || fx <= 0) {
            throw new IllegalArgumentException("估值币种汇率无效: " + getCurrencyCode());
        }

        requireNotNull(getUpperBarrier(), "UPPER_BARRIER");
        requireNotNull(getLowerBarrier(), "LOWER_BARRIER");
        if (getUpperBarrier() <= getLowerBarrier()) {
            throw new IllegalArgumentException("UPPER_BARRIER 必须大于 LOWER_BARRIER");
        }
        requireNotNull(getLowRate(), "LOW_RATE");
        requireNotNull(getMidRate(), "MID_RATE");
        requireNotNull(getHighRate(), "HIGH_RATE");

        requireText(getDiscountCurve(), "DISCOUNT_CURVE");
        requireText(resolveFixingKey(), "FIXING_ID");
        requireText(getVolatilitySurface(), "VOLATILITY_SURFACE");
        if (!md.irSpot.containsKey(getDiscountCurve())) {
            throw new IllegalArgumentException("缺少贴现曲线: " + getDiscountCurve());
        }
        if (!md.fixingRate.containsKey(resolveFixingKey())) {
            throw new IllegalArgumentException("缺少定盘曲线: " + resolveFixingKey());
        }

        requireNotNull(getEps(), "EPS");
        if (getEps() <= 0) {
            throw new IllegalArgumentException("EPS 必须大于 0: " + getEps());
        }
        requireNotNull(getAbsFlag(), "ABS_FLAG");

        validateSpecificInputs(md);
    }

    /**
     * 估值入口。
     */
    public M calc() {
        try {
            this.stepUpMeasure = calc(marketData);
            stepUpMeasure.instrumentId = getInstrumentId();
            stepUpMeasure.dataDate = dataDate;
            stepUpMeasure.productCode = getProductCode();
            stepUpMeasure.position = pos;
            stepUpMeasure.status = "SUCCESS";
            stepUpMeasure.logs = new ArrayList<>();

            List<Map<String, Object>> d2List = new ArrayList<>();
            double domesticRho = 0.0;
            double foreignRho = 0.0;
            if (dataDate.isAfter(getMaturityDate()) || inFixingWindow()) {
                fillDetail(stepUpMeasure, domesticRho, foreignRho, d2List);
                appendFrtbSens();
                return this.stepUpMeasure;
            }
            if (digUtil1 == null || digUtil2 == null) {
                fillDetail(stepUpMeasure, domesticRho, foreignRho, d2List);
                appendFrtbSens();
                return this.stepUpMeasure;
            }

            middle.sigma1 = digUtil1.getSigma();
            middle.sigma2 = digUtil2.getSigma();

            if (Convert.isTrue(getAbsFlag())) {
                stepUpMeasure.delta = digUtil1.Delta(getEps()) + digUtil2.Delta(getEps());
                stepUpMeasure.gamma = digUtil1.Gamma(getEps()) + digUtil2.Gamma(getEps());
            } else {
                stepUpMeasure.delta = digUtil1.DeltaTimes(getEps()) + digUtil2.DeltaTimes(getEps());
                stepUpMeasure.gamma = digUtil1.GammaTimes(getEps()) + digUtil2.GammaTimes(getEps());
            }
            stepUpMeasure.vega = digUtil1.Vega() + digUtil2.Vega();
            stepUpMeasure.theta = digUtil1.Theta() + digUtil2.Theta();
            domesticRho = digUtil1.DRho() + digUtil2.DRho();
            foreignRho = digUtil1.FRho() + digUtil2.FRho();

            Map<String, Object> map = new HashMap<>();
            map.put("DATE", dataDate);
            if (isCall()) {
                map.put("LOW_D2", digUtil2.getD2());
                map.put("UP_D2", digUtil1.getD2());
                map.put("PROB_LOW", cdf(-digUtil2.getD2()));
                map.put("PROB_MID", 1 - cdf(-digUtil2.getD2()) - cdf(digUtil1.getD2()));
                map.put("PROB_HIGH", cdf(digUtil1.getD2()));
            } else {
                map.put("LOW_D2", digUtil1.getD2());
                map.put("UP_D2", digUtil2.getD2());
                map.put("PROB_LOW", cdf(-digUtil1.getD2()));
                map.put("PROB_MID", 1 - cdf(-digUtil1.getD2()) - cdf(digUtil2.getD2()));
                map.put("PROB_HIGH", cdf(digUtil2.getD2()));
            }
            d2List.add(map);
            fillDetail(stepUpMeasure, domesticRho, foreignRho, d2List);
            appendFrtbSens();
        } catch (Exception e) {
            this.stepUpMeasure = buildErrorMeasure(e);
        }
        return this.stepUpMeasure;
    }

    /**
     * 核心估值：定盘后走固定分段，定盘前走二元拆分模型。
     */
    public M calc(MarketData md) {
        validateRequiredInputs(md);
        middle.newSigma = true;
        M measure = newMeasure();
        measure.instrumentId = getInstrumentId();
        measure.productCode = getProductCode();
        measure.dataDate = dataDate;
        measure.position = pos;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        if (dataDate.isAfter(getMaturityDate())) {
            measure.valuationCcy = getCurrencyCode();
            return measure;
        }
        if (inFixingWindow()) {
            double df = new IrSpot(md.irSpot.get(getDiscountCurve())).discount(getMaturityDate());
            double rate = new Fixing(md.fixingRate.get(resolveFixingKey())).getRate(getFixingDate());
            double maturityT = yearFrac(getStartDate(), getMaturityDate());
            if (rate < getLowerBarrier() || rate > getUpperBarrier()) {
                measure.valuationUnit = getNotional() * df * getLowRate() * maturityT;
            } else {
                measure.valuationUnit = getNotional() * df * getMidRate() * maturityT;
            }
            measure.valuation = measure.valuationUnit * pos;
            measure.spotPrice = rate;
            measure.fwdPrice = rate;
            measure.valuationCcy = getCurrencyCode();
            measure.valuationCny = measure.valuation * getCnyFxRate(md, getCurrencyCode());
            return measure;
        }

        PricingContext ctx = buildPricingContext(md);
        String pricingModel = resolvePricingModel();
        EurOptUtil eurUtil1 = null;
        double sigma1;
        if (isBachelierModel(pricingModel)) {
            sigma1 = interpolateAtmVol(ctx.volCur);
        } else {
            eurUtil1 = new EurOptUtil(ctx.call, true, ctx.s, ctx.k1, ctx.rd, ctx.rf, ctx.fixingT, ctx.maturityT,
                    ctx.volCur, "black");
            sigma1 = eurUtil1.getSigma();
        }
        boolean vv = Boolean.TRUE.equals(getVvFlag());
        digUtil1 = new DigOptUtil(ctx.call, true, ctx.s, ctx.k1, ctx.rebate1, ctx.rd, ctx.rf, ctx.rebase,
                ctx.fixingT, ctx.maturityT, ctx.volCur, sigma1, pricingModel, vv);

        EurOptUtil eurUtil2 = null;
        double sigma2;
        if (isBachelierModel(pricingModel)) {
            sigma2 = interpolateAtmVol(ctx.volCur);
        } else {
            eurUtil2 = new EurOptUtil(ctx.call, true, ctx.s, ctx.k2, ctx.rd, ctx.rf, ctx.fixingT, ctx.fixingT,
                    ctx.volCur, "black");
            sigma2 = eurUtil2.getSigma();
        }
        digUtil2 = new DigOptUtil(ctx.call, true, ctx.s, ctx.k2, ctx.rebate2, ctx.rd, ctx.rf, ctx.rebase,
                ctx.fixingT, ctx.maturityT, ctx.volCur, sigma2, pricingModel, vv);
        boolean applyNonNegativeFloor = vv && isNonNegativeFloorEnabled();

        if (middle.newSigma) {
            double v1 = applyFloorIfNeeded(digUtil1.getValue(), applyNonNegativeFloor);
            double v2 = applyFloorIfNeeded(digUtil2.getValue(), applyNonNegativeFloor);
            measure.valuationUnit = v2 + v1
                    + Math.exp(-ctx.rebase * ctx.maturityT) * ctx.rebate3;
        } else {
            double v1 = applyFloorIfNeeded(digUtil1.getValue(middle.sigma1), applyNonNegativeFloor);
            double v2 = applyFloorIfNeeded(digUtil2.getValue(middle.sigma2), applyNonNegativeFloor);
            // 场景估值不叠加 VV
            measure.valuationUnit = v2 + v1
                    + Math.exp(-ctx.rebase * ctx.maturityT) * ctx.rebate3;
        }
        middle.newSigma = false;
        measure.valuation = measure.valuationUnit * pos;
        measure.spotPrice = ctx.s;
        measure.fwdPrice = ctx.f;
        measure.valuationCcy = getCurrencyCode();
        measure.valuationCny = measure.valuation * getCnyFxRate(md, getCurrencyCode());

        double sigmaN1 = isBachelierModel(pricingModel)
                ? sigma1
                : eurUtil1.goalSeek(ctx.rd + 0.0001, ctx.rf + 0.0001, ctx.volCur);
        double sigmaN2 = isBachelierModel(pricingModel)
                ? sigma2
                : eurUtil2.goalSeek(ctx.rd + 0.0001, ctx.rf + 0.0001, ctx.volCur);
        double pv01 = (applyFloorIfNeeded(digUtil1.getValue(ctx.rd + 0.0001, ctx.rf + 0.0001, ctx.rebase + 0.0001,
                sigmaN1), applyNonNegativeFloor)
                - applyFloorIfNeeded(digUtil1.getValue(), applyNonNegativeFloor)) * pos;
        double pv02 = (applyFloorIfNeeded(digUtil2.getValue(ctx.rd + 0.0001, ctx.rf + 0.0001, ctx.rebase + 0.0001,
                sigmaN2), applyNonNegativeFloor)
                - applyFloorIfNeeded(digUtil2.getValue(), applyNonNegativeFloor)) * pos;

        return measure;
    }

    protected String resolvePricingModel() {
        String modelType = getModelType();
        if ("bachelier".equalsIgnoreCase(modelType == null ? "" : modelType.trim())) {
            return "bachelier";
        }
        return "black";
    }

    private boolean isBachelierModel(String modelType) {
        return "bachelier".equalsIgnoreCase(modelType == null ? "" : modelType.trim());
    }

    private double interpolateAtmVol(List<Map<String, Object>> volCur) {
        if (volCur == null || volCur.isEmpty()) {
            throw new IllegalArgumentException("bachelier 模型缺少波动率曲线");
        }
        Double[] deltas = volCur.stream()
                .map(e -> Convert.toDouble(e.get("DELTA")))
                .toArray(Double[]::new);
        Double[] vols = volCur.stream()
                .map(e -> Convert.toDouble(e.get("VOLATILITY_RATE")))
                .toArray(Double[]::new);
        double sigma = Interpolation.interpolate(deltas, vols, 0.5, VolUtil.requireAxis2InterpolateType(volCur));
        if (!Double.isFinite(sigma) || sigma <= 0.0) {
            throw new IllegalArgumentException("bachelier 模型 ATM 波动率无效: " + sigma);
        }
        return sigma;
    }

    /**
     * VV 开启时统一读取非负兜底开关。
     */
    protected boolean isNonNegativeFloorEnabled() {
        return EngineConfiguration.getInstance()
                .getRequiredBoolean(EngineConstants.CFG.VV_NON_NEGATIVE_FLOOR_ENABLED);
    }

    /**
     * 腿级非负兜底，避免 VV 调整在极端场景下产生负的期权腿价格。
     */
    protected double applyFloorIfNeeded(double value, boolean enabled) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        if (!enabled) {
            return value;
        }
        return Math.max(0.0, value);
    }

    /**
     * 估值上下文。
     */
    public static class PricingContext {
        public boolean call;
        public int days;
        public double t;
        public double fixingT;
        public double maturityT;
        public double s;
        public double f;
        public double rd;
        public double rf;
        public double rebase;
        public double k1;
        public double k2;
        public double rebate1;
        public double rebate2;
        public double rebate3;
        public List<Map<String, Object>> volCur;
    }

    /**
     * 统一中间缓存。
     */
    protected static class Middle {
        public double sigma1 = 0.0;
        public double sigma2 = 0.0;
        public boolean newSigma = true;
    }

    /**
     * StepUp 期权产品公共输入信息基类，子类 Info 继承此类。
     */
    public static class StepUpBaseInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, allowedValues = {"Call", "Put"}, ignoreCase = true)
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true)
        @JSONField(name = "START_DATE", format = "yyyyMMdd")
        public LocalDate startDate;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
        @ProductInputField(required = true)
        @JSONField(name = "FIXING_DATE", format = "yyyyMMdd")
        public LocalDate fixingDate;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @ProductInputField(required = true)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "UPPER_BARRIER")
        public Double upperBarrier;
        @ProductInputField(required = true)
        @JSONField(name = "LOWER_BARRIER")
        public Double lowerBarrier;
        @ProductInputField(required = true)
        @JSONField(name = "LOW_RATE")
        public Double lowRate;
        @ProductInputField(required = true)
        @JSONField(name = "MID_RATE")
        public Double midRate;
        @ProductInputField(required = true)
        @JSONField(name = "HIGH_RATE")
        public Double highRate;
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @JSONField(name = "MODEL_TYPE")
        public String modelType;
        @ProductInputField(finite = true, min = "0", minInclusive = false)
        @JSONField(name = "EPS")
        public Double eps;
        @JSONField(name = "ABS_FLAG")
        public Boolean absFlag;
        @ProductInputField(required = true)
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        /** VV 开关：true 时启用 Vanna-Volga overhedge 调整 */
        @JSONField(name = "VV_FLAG")
        public Boolean vvFlag;
    }
}

