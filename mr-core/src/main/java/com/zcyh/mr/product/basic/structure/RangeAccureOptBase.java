package com.zcyh.mr.product.basic.structure;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Convert;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.option.RangeAccureUtil;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

import static com.zcyh.mr.product.basic.option.EurOptUtil.cdf;
import static com.zcyh.mr.product.basic.frtb.OptionBaseFrtbSupport.*;

/**
 * 区间累计期权抽象基类，封装估值循环、Greeks 计算及 FRTB 敏感性等公共逻辑。
 * 各标的子类（商品/外汇/利率/指数）仅需实现参数获取的差异化方法。
 */
public abstract class RangeAccureOptBase<T extends RangeAccureOptBase.RangeAccureBaseInfo> {
    protected static final double FRTB_ZERO_TOL = 1e-12;
    protected static final double DEFAULT_EPS = 0.0001;
    protected LocalDate dataDate;
    protected T rangeAccureInfo;
    protected MarketData marketData;
    protected OptionMeasure rangeAccureMeasure;
    protected Double pos;
    protected final Middle middle = new Middle();

    public RangeAccureOptBase(LocalDate dataDate, T rangeAccureInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.rangeAccureInfo = rangeAccureInfo;
        this.marketData = marketData;
        this.pos = "B".equals(rangeAccureInfo.buyOrSell) ? 1.0 : -1.0;
    }

    // ===== 子类必须实现的差异化方法 =====

    /**
     * 每个观察日的市场参数：远期价格 f、本币利率 rd、外币利率 rf
     */
    public static class ObsParams {
        public double f;
        public double rd;
        public double rf;
    }

    /**
     * 获取标的即期价格。在估值循环开始前调用一次。
     */
    protected abstract double getSpotPrice(MarketData md);

    /**
     * 获取指定天数对应的波动率曲线
     */
    protected abstract List<Map<String, Object>> getVolCurve(MarketData md, int days);

    /**
     * 构建每个观察日的差异化参数（rd, rf, f）
     */
    protected abstract ObsParams buildObsParams(MarketData md, LocalDate obsDate, int days, double t,
            double s, double rebase, double discount);

    protected abstract List<FrtbSenes> getFrtbSensList();

    /**
     * 是否使用 IR 口径的 Delta/Gamma 计算。默认 false，利率标的可覆写为 true。
     */
    protected boolean useIrGreeks() {
        return false;
    }

    /**
     * 子类可在估值循环前执行额外初始化（例如预计算 diff）。
     */
    protected void onBeforeCalcLoop(MarketData md, double s) {
        // 默认空实现
    }

    /**
     * 获取折现曲线名称。默认统一取 DISCOUNT_CURVE。
     */
    protected String getDiscountCurveName() {
        return rangeAccureInfo.discountCurve;
    }

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
        if (rangeAccureInfo == null) {
            return;
        }
        if (rangeAccureInfo.eps == null) {
            rangeAccureInfo.eps = DEFAULT_EPS;
        }
        if (rangeAccureInfo.absFlag == null) {
            rangeAccureInfo.absFlag = defaultAbsFlag();
        }
    }

    /**
     * 获取定盘曲线 key：仅使用 FIXING_ID。
     */
    protected String resolveFixingKey() {
        return rangeAccureInfo.fixingId == null ? null : rangeAccureInfo.fixingId.trim();
    }

    /**
     * 子类可覆写补充特有校验逻辑。
     */
    protected void validateSpecificInputs(MarketData md) {
        // 默认空实现
    }

    /**
     * 统一基础校验，避免在深层公式中出现空指针或非法输入。
     */
    protected void validateRequiredInputs(MarketData md) {
        requireNotNull(rangeAccureInfo, "TRADE_INFO");
        applyDefaultInputs();
        requireNotNull(md, "marketData");
        requireNotNull(md.irSpot, "marketData.irSpot");
        requireNotNull(md.fixingRate, "marketData.fixingRate");
        requireNotNull(md.fxSpot, "marketData.fxSpot");

        requireText(rangeAccureInfo.instrumentId, "INSTRUMENT_ID");
        requireText(rangeAccureInfo.productCode, "PRODUCT_CODE");
        requireText(rangeAccureInfo.buyOrSell, "BUY_OR_SELL");
        if (!"B".equalsIgnoreCase(rangeAccureInfo.buyOrSell) && !"S".equalsIgnoreCase(rangeAccureInfo.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B 或 S: " + rangeAccureInfo.buyOrSell);
        }

        requireNotNull(rangeAccureInfo.startDate, "START_DATE");
        requireNotNull(rangeAccureInfo.maturityDate, "MATURITY_DATE");
        if (rangeAccureInfo.maturityDate.isBefore(rangeAccureInfo.startDate)) {
            throw new IllegalArgumentException("MATURITY_DATE 不能早于 START_DATE");
        }

        List<LocalDate> obsDates = parseObsDates(rangeAccureInfo.obsDates);
        LocalDate obsMin = obsDates.get(0);
        LocalDate obsMax = obsDates.get(obsDates.size() - 1);
        if (obsMin.isBefore(rangeAccureInfo.startDate) || obsMax.isAfter(rangeAccureInfo.maturityDate)) {
            throw new IllegalArgumentException("OBS_DATES 必须位于 START_DATE 与 MATURITY_DATE 区间内");
        }

        requireNotNull(rangeAccureInfo.notional, "NOTIONAL");
        if (rangeAccureInfo.notional <= 0) {
            throw new IllegalArgumentException("NOTIONAL 必须大于 0: " + rangeAccureInfo.notional);
        }
        String valuationCurrency = getValuationCurrency();
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);
        try {
            double fx = fxSpot.getFxrate(valuationCurrency);
            if (!Double.isFinite(fx) || fx <= 0) {
                throw new IllegalArgumentException("估值币种汇率无效: " + valuationCurrency);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("缺少估值币种汇率: " + valuationCurrency);
        }

        requireNotNull(rangeAccureInfo.upperBarrier, "UPPER_BARRIER");
        requireNotNull(rangeAccureInfo.lowerBarrier, "LOWER_BARRIER");
        if (rangeAccureInfo.upperBarrier <= rangeAccureInfo.lowerBarrier) {
            throw new IllegalArgumentException("UPPER_BARRIER 必须大于 LOWER_BARRIER");
        }

        requireNotNull(rangeAccureInfo.rangeAccureRate, "RANGE_ACCURE_RATE");
        if (rangeAccureInfo.rangeAccureRate < 0) {
            throw new IllegalArgumentException("RANGE_ACCURE_RATE 不能小于 0");
        }
        requireText(rangeAccureInfo.rangeDirection, "RANGE_DIRECTION");
        if (!"in".equalsIgnoreCase(rangeAccureInfo.rangeDirection)
                && !"out".equalsIgnoreCase(rangeAccureInfo.rangeDirection)) {
            throw new IllegalArgumentException("RANGE_DIRECTION 仅支持 in 或 out: " + rangeAccureInfo.rangeDirection);
        }

        requireText(rangeAccureInfo.discountCurve, "DISCOUNT_CURVE");
        String fixingKey = resolveFixingKey();
        requireText(fixingKey, "FIXING_ID");
        requireText(rangeAccureInfo.volatilitySurface, "VOLATILITY_SURFACE");
        if (!md.irSpot.containsKey(rangeAccureInfo.discountCurve)) {
            throw new IllegalArgumentException("缺少基础市场曲线: " + rangeAccureInfo.discountCurve);
        }
        if (!md.fixingRate.containsKey(fixingKey)) {
            throw new IllegalArgumentException("缺少定盘曲线: " + fixingKey);
        }

        requireNotNull(rangeAccureInfo.eps, "EPS");
        if (rangeAccureInfo.eps <= 0) {
            throw new IllegalArgumentException("EPS 必须大于 0: " + rangeAccureInfo.eps);
        }
        requireNotNull(rangeAccureInfo.absFlag, "ABS_FLAG");

        validateSpecificInputs(md);
    }

    /**
     * 解析观察日并做去重和升序，避免输入顺序影响结果。
     */
    protected List<LocalDate> parseObsDates(String obsDateText) {
        requireText(obsDateText, "OBS_DATES");
        TreeSet<LocalDate> set = new TreeSet<>();
        String[] arr = obsDateText.split(",");
        for (String raw : arr) {
            String dateText = raw == null ? "" : raw.trim();
            if (dateText.isEmpty()) {
                continue;
            }
            try {
                set.add(LocalDate.parse(dateText, DateTimeFormatter.ofPattern("yyyyMMdd")));
            } catch (Exception e) {
                throw new IllegalArgumentException("OBS_DATES 日期格式错误(yyyyMMdd): " + dateText);
            }
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("OBS_DATES 不能为空");
        }
        return new ArrayList<>(set);
    }

    /**
     * 场景估值前重置中间缓存，避免跨 market 复用旧缓存。
     */
    protected void resetMiddleForMarketCalc() {
        middle.map.clear();
        middle.map1.clear();
        middle.newSigma = true;
    }

    /**
     * FRTB 场景结果是否可视为无变化（人民币口径）。
     */
    protected boolean isNoChangeByCny(OptionMeasure result) {
        return Math.abs(result.valuationCny - rangeAccureMeasure.valuationCny) < FRTB_ZERO_TOL;
    }

    /**
     * 估值币种金额转人民币金额。
     */
    protected double toCnyByValuationCurrency(double value) {
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        return value * fxSpot.getFxrate(getValuationCurrency());
    }

    /**
     * FX 风险币种集合，默认仅使用估值币种（剔除 CNY）。
     * 子类可覆写补充更多币种（如外汇产品的基础币种与标的币种）。
     */
    protected List<String> getFxRiskCurrencies() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String ccy = getValuationCurrency();
        if (hasText(ccy) && !isDomesticFxCurrency(ccy)) {
            set.add(ccy);
        }
        return new ArrayList<>(set);
    }

    /**
     * FX Vega 默认使用第一个 FX 风险币种作为桶。
     */
    protected String getFxVegaBucketCurrency(List<String> fxRiskCurrencies) {
        if (fxRiskCurrencies != null && !fxRiskCurrencies.isEmpty()) {
            return fxRiskCurrencies.get(0);
        }
        return getValuationCurrency();
    }

    /**
     * 从现有 info 字段读取 bucket，若不存在或为空则返回默认值。
     * 不新增输入字段，仅复用已有字段名。
     */
    protected String resolveOptionalBucket(String defaultBucket, String... fieldNames) {
        if (fieldNames == null || fieldNames.length == 0) {
            return defaultBucket;
        }
        for (String fieldName : fieldNames) {
            String value = readTextField(rangeAccureInfo, fieldName);
            if (hasText(value)) {
                return value.trim();
            }
        }
        return defaultBucket;
    }

    /**
     * GIRR 曲线与币种映射。默认使用估值折现曲线。
     * 子类覆写后可声明本交易需要参与 GIRR 计量的全部曲线。
     */
    protected Map<String, String> buildGirrCurveCcyMap() {
        HashMap<String, String> map = new HashMap<>();
        String curve = getDiscountCurveName();
        String ccy = getValuationCurrency();
        if (hasText(curve) && hasText(ccy)) {
            map.put(curve, ccy);
        }
        return map;
    }

    /**
     * GIRR Delta 开关。默认开启。
     */
    protected boolean enableGirrDelta() {
        return true;
    }

    /**
     * GIRR Curvature 开关。默认开启。
     */
    protected boolean enableGirrCurvature() {
        return true;
    }

    /**
     * GIRR Vega 开关。默认关闭，子类按需开启。
     */
    protected boolean enableGirrVega() {
        return false;
    }

    /**
     * GIRR Vega 对应波动率曲面，默认使用交易波动率曲面。
     */
    protected String getGirrVegaSurface() {
        return rangeAccureInfo.volatilitySurface;
    }

    /**
     * GIRR Vega 默认桶，默认使用估值币种。
     */
    protected String getGirrVegaBucket() {
        return getValuationCurrency();
    }

    /**
     * GIRR Vega 的第二维原始输入。
     * 由具体利率产品提供，公共层仅负责映射到标准 tenor。
     */
    protected String getGirrVegaSecondaryVertex() {
        return null;
    }

    /**
     * GIRR Delta 的缩放因子，默认 10000（BP 口径）。
     */
    protected double getGirrDeltaScale() {
        return 10000.0;
    }

    /**
     * 将 GIRR 曲线-币种映射转换为 Curvature 所需的币种-曲线列表映射。
     */
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
            List<String> curves = bucketCurveMap.computeIfAbsent(ccy, k -> new ArrayList<>());
            if (!curves.contains(curve)) {
                curves.add(curve);
            }
        }
        return bucketCurveMap;
    }

    /**
     * GIRR Delta 依赖由产品侧显式声明多条曲线。
     */
    protected List<FrtbDependency> collectGirrDeltaDependencies() {
        return FrtbSensitivityBuilder.buildGirrDeltaDependencies(buildGirrCurveCcyMap());
    }

    /**
     * GIRR Vega 仅声明曲面依赖；Curvature 直接复用 Delta 依赖识别曲线集合。
     */
    protected List<FrtbDependency> collectGirrVegaDependencies() {
        return FrtbSensitivityBuilder.buildGirrVegaDependencies(
                getGirrVegaSurface(),
                getGirrVegaBucket(),
                getGirrVegaSecondaryVertex());
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

    /**
     * GIRR 敏感性（Delta/Curvature/Vega）通用实现。
     * 具体曲线映射与开关由子类覆写控制。
     */
    protected List<FrtbSenes> getSensListGIRR() {
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                getFrtbSettleDate(),
                collectGirrDeltaDependencies(),
                enableGirrVega() ? collectGirrVegaDependencies() : new ArrayList<>(),
                enableGirrDelta(),
                enableGirrCurvature(),
                rangeAccureMeasure.instrumentId,
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                MeasureValuation.of(
                        rangeAccureMeasure.valuation,
                        rangeAccureMeasure.valuationCny),
                shockedMarketData -> {
                    OptionMeasure shockedMeasure = calc(shockedMarketData);
                    return MeasureValuation.of(
                            shockedMeasure.valuation,
                            shockedMeasure.valuationCny);
                },
                null,
                () -> middle.newSigma = true);
        // 敏感性已通过 valuationCny 插值变化自动包含 pos，无需额外乘以 pos
        return sensitivities;
    }

    /**
     * FX 敏感性（Delta/Curvature/Vega）通用实现。
     */
    protected List<FrtbSenes> getSensListFX() {
        List<String> fxRiskCurrencies = getFxRiskCurrencies();
        if (fxRiskCurrencies.isEmpty()) {
            return new ArrayList<>();
        }
        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(fxRiskCurrencies);
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                getFrtbSettleDate(),
                fxDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                rangeAccureMeasure.instrumentId,
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                MeasureValuation.of(rangeAccureMeasure.valuation, rangeAccureMeasure.valuationCny),
                shockedMarketData -> toMeasureValuation(calc(shockedMarketData)));
        return fxSensitivities;
    }

    /**
     * 外汇区间累计产品公共 FX FRTB 输出模板。
     * 统一通过公共 builder 构建 FX Delta/Vega/Curvature，再补做产品族后处理。
     */
    protected List<FrtbSenes> buildFxFrtbSensListCommon(
            OptionMeasure measure,
            LocalDate settleDate,
            String underlyingCurrencyCode,
            String baseCurrencyCode,
            String valuationCurrency,
            String volatilitySurface,
            Function<MarketData, ? extends OptionMeasure> repriceFunction) {
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
                rangeAccureInfo.instrumentId,
                getFrtbInstrumentCurrency(),
                FRTB_ZERO_TOL,
                baseValuation,
                shockedMarketData -> toMeasureValuation(repriceFunction.apply(shockedMarketData)));
        list.addAll(fxSensitivities);
        return list;
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    /**
     * FRTB 结算日，未配置时回退到到期日。
     */
    protected LocalDate getFrtbSettleDate() {
        if (rangeAccureInfo instanceof RangeAccureFrtbInfo) {
            RangeAccureFrtbInfo frtbInfo = (RangeAccureFrtbInfo) rangeAccureInfo;
            if (frtbInfo.settleDate != null) {
                return frtbInfo.settleDate;
            }
        }
        return rangeAccureInfo.maturityDate;
    }

    /**
     * 估值币种：统一使用 CURRENCY_CODE。
     */
    protected String getValuationCurrency() {
        if (hasText(rangeAccureInfo.currencyCode)) {
            return rangeAccureInfo.currencyCode;
        }
        throw new IllegalArgumentException("缺少估值币种，请配置 CURRENCY_CODE");
    }

    /**
     * FRTB 计量币种：统一使用 CURRENCY_CODE。
     */
    protected String getFrtbInstrumentCurrency() {
        return getValuationCurrency();
    }

    protected boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void appendFrtbSens() {
        List<FrtbSenes> list = getFrtbSensList();
        if (list == null || list.isEmpty()) {
            return;
        }
        rangeAccureMeasure.sensitivityList.addAll(list);
    }

    protected static void requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("输入字段不能为空: " + fieldName);
        }
    }

    protected static void requireNotNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("输入字段不能为空: " + fieldName);
        }
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

    private OptionMeasure buildErrorMeasure(Exception e) {
        OptionMeasure errorMeasure = new OptionMeasure();
        errorMeasure.dataDate = dataDate;
        if (rangeAccureInfo != null) {
            errorMeasure.instrumentId = rangeAccureInfo.instrumentId;
            errorMeasure.productCode = rangeAccureInfo.productCode;
        }
        errorMeasure.position = pos == null ? 0.0 : pos;
        errorMeasure.status = "ERROR";
        String message = (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty())
                ? String.valueOf(e)
                : e.getMessage();
        errorMeasure.addErrorLog(message);
        return errorMeasure;
    }

    // ===== 公共估值逻辑 =====

    /**
     * 估值入口
     */
    public OptionMeasure calc() {
        try {
            this.rangeAccureMeasure = calc(marketData);
            rangeAccureMeasure.instrumentId = rangeAccureInfo.instrumentId;
            rangeAccureMeasure.dataDate = dataDate;
            rangeAccureMeasure.productCode = rangeAccureInfo.productCode;
            rangeAccureMeasure.position = pos;
            rangeAccureMeasure.status = "SUCCESS";
            rangeAccureMeasure.logs = new ArrayList<>();
            appendFrtbSens();
        } catch (Exception e) {
            this.rangeAccureMeasure = buildErrorMeasure(e);
        }
        return this.rangeAccureMeasure;
    }

    /**
     * 区间判断：价格是否落在有效区间内
     */
    public boolean checkValue(double price) {
        if ("in".equalsIgnoreCase(rangeAccureInfo.rangeDirection)
                && price >= rangeAccureInfo.lowerBarrier && price <= rangeAccureInfo.upperBarrier)
            return true;
        if ("out".equalsIgnoreCase(rangeAccureInfo.rangeDirection)
                && (price < rangeAccureInfo.lowerBarrier || price > rangeAccureInfo.upperBarrier))
            return true;
        return false;
    }

    /**
     * 核心估值方法（模板方法）：估值日之前使用定盘价，之后使用模型价格
     */
    public OptionMeasure calc(MarketData marketData) {
        validateRequiredInputs(marketData);
        resetMiddleForMarketCalc();

        OptionMeasure measure = new OptionMeasure();
        measure.instrumentId = rangeAccureInfo.instrumentId;
        measure.productCode = rangeAccureInfo.productCode;
        measure.dataDate = dataDate;
        measure.position = pos;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(getDiscountCurveName()));
        double df = irSpot.discount(rangeAccureInfo.maturityDate);
        Fixing fixing = new Fixing(marketData.fixingRate.get(resolveFixingKey()));
        double maturityT = ChronoUnit.DAYS.between(rangeAccureInfo.startDate, rangeAccureInfo.maturityDate) / 365.0;
        List<LocalDate> obsDates = parseObsDates(rangeAccureInfo.obsDates);
        double rebate = rangeAccureInfo.notional * rangeAccureInfo.rangeAccureRate * maturityT / obsDates.size();
        double s = getSpotPrice(marketData);
        Double fwd = null;
        double domesticRho = 0.0;
        double foreignRho = 0.0;
        List<Map<String, Object>> d2List = new ArrayList<>();
        double impliedVolSum = 0.0;
        int impliedVolCount = 0;
        String pricingModel = resolvePricingModel();
        onBeforeCalcLoop(marketData, s);

        for (LocalDate obsDate : obsDates) {
            String dateKey = obsDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            if (obsDate.isAfter(this.dataDate)) {
                double k = rangeAccureInfo.upperBarrier;
                int days = (int) ChronoUnit.DAYS.between(dataDate, obsDate);
                double t = days / 365.0;
                double rebase = irSpot.spotRate(obsDate);
                double discount = irSpot.discount(obsDate);
                double k2 = rangeAccureInfo.lowerBarrier;

                ObsParams p = buildObsParams(marketData, obsDate, days, t, s, rebase, discount);
                if (fwd == null) {
                    fwd = p.f;
                }
                List<Map<String, Object>> volCur = getVolCurve(marketData, days);

                RangeAccureUtil util1, util2;
                double direction = 1.0;
                if ("in".equalsIgnoreCase(rangeAccureInfo.rangeDirection)) {
                    util1 = new RangeAccureUtil(true, true, s, k, rebate, p.rd, p.rf, rebase, t, t, df, discount, p.f,
                            volCur, pricingModel);
                    util2 = new RangeAccureUtil(true, true, s, k2, rebate, p.rd, p.rf, rebase, t, t, df, discount, p.f,
                            volCur, pricingModel);
                } else {
                    direction = -1.0;
                    util1 = new RangeAccureUtil(true, true, s, k, rebate, p.rd, p.rf, rebase, t, t, df, discount, p.f,
                            volCur, pricingModel);
                    util2 = new RangeAccureUtil(false, true, s, k2, rebate, p.rd, p.rf, rebase, t, t, df, discount, p.f,
                            volCur, pricingModel);
                }

                // 每次 calc() 入口已清空 map 且 dateKey 唯一，直接校准并记录
                util1.calibrate();
                middle.map.put(dateKey, util1.getSigmas());
                util2.calibrate();
                middle.map1.put(dateKey, util2.getSigmas());
                impliedVolSum += (middle.map.get(dateKey)[0] + middle.map1.get(dateKey)[0]) * 0.5;
                impliedVolCount++;

                // Greeks 累加：根据标的类型选择 IR 或非IR口径
                if (useIrGreeks()) {
                    if (Convert.isTrue(rangeAccureInfo.absFlag)) {
                        measure.delta += util2.getDelta(rangeAccureInfo.eps)
                                - util1.getDelta(rangeAccureInfo.eps) * direction;
                        measure.gamma += util2.getGamma(rangeAccureInfo.eps)
                                - util1.getGamma(rangeAccureInfo.eps) * direction;
                    } else {
                        measure.delta += util2.getDeltaTimes(rangeAccureInfo.eps)
                                - util1.getDeltaTimes(rangeAccureInfo.eps) * direction;
                        measure.gamma += util2.getGammaTimes(rangeAccureInfo.eps)
                                - util1.getGammaTimes(rangeAccureInfo.eps) * direction;
                    }
                } else {
                    if (Convert.isTrue(rangeAccureInfo.absFlag)) {
                        measure.delta += util2.getDeltaFx(rangeAccureInfo.eps)
                                - util1.getDeltaFx(rangeAccureInfo.eps) * direction;
                        measure.gamma += util2.getGammaFx(rangeAccureInfo.eps)
                                - util1.getGammaFx(rangeAccureInfo.eps) * direction;
                    } else {
                        measure.delta += util2.getDeltaTimesFx(rangeAccureInfo.eps)
                                - util1.getDeltaTimesFx(rangeAccureInfo.eps) * direction;
                        measure.gamma += util2.getGammaTimesFx(rangeAccureInfo.eps)
                                - util1.getGammaTimesFx(rangeAccureInfo.eps) * direction;
                    }
                }

                measure.vega += util2.getVega() - util1.getVega() * direction;
                domesticRho += util2.getDRho() - util1.getDRho() * direction;
                foreignRho += util2.getFRho() - util1.getFRho() * direction;
                measure.theta += util2.getTheta() - util1.getTheta() * direction;

                Map<String, Object> map = new HashMap<>();
                map.put("DATE", obsDate);
                map.put("LOW_D2", util2.getD2());
                map.put("UP_D2", util1.getD2());
                map.put("PROB_MID", 1 - cdf(-util2.getD2()) - cdf(util1.getD2()));
                if (d2List.isEmpty()) {
                    map.put("PROB_TOTAL", map.get("PROB_MID"));
                } else {
                    double total = Convert.toDouble(d2List.get(d2List.size() - 1).get("PROB_TOTAL"));
                    map.put("PROB_TOTAL", total * (1 - cdf(-util2.getD2()) - cdf(util1.getD2())));
                }
                d2List.add(map);

                // sigma 已通过 calibrate/setSigmas 注入，统一使用 getValue()
                measure.valuationUnit += util2.getValue() - util1.getValue() * direction;
            } else {
                double barrier = fixing.getRate(obsDate);
                if (checkValue(barrier)) {
                    measure.valuationUnit += rebate * df;
                }
            }
        }
        if (impliedVolCount > 0) {
            measure.impliedVol = impliedVolSum / impliedVolCount;
        }
        middle.newSigma = false;
        measure.valuation = measure.valuationUnit * pos;
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        measure.spotPrice = s;
        measure.fwdPrice = (fwd != null) ? fwd : s;
        measure.valuationCcy = getValuationCurrency();
        measure.valuationCny = measure.valuation * fxSpot.getFxrate(measure.valuationCcy);
        // Greeks 与 valuationUnit 口径一致，不乘 pos
        fillDetail(measure, domesticRho, foreignRho, d2List);
        return measure;
    }

    protected String resolvePricingModel() {
        String modelType = rangeAccureInfo == null ? null : rangeAccureInfo.modelType;
        if ("bachelier".equalsIgnoreCase(modelType == null ? "" : modelType.trim())) {
            return "bachelier";
        }
        return "black";
    }

    // ===== 公共内部类 =====

    /**
     * 区间累计估值结果
     */
    /**
     * 区间累计基础字段（估值主流程通用）。
     */
    public static class RangeAccureBaseInfo {
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @JSONField(name = "START_DATE", format = "yyyyMMdd")
        public LocalDate startDate;
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @JSONField(name = "OBS_DATES")
        public String obsDates;
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "UPPER_BARRIER")
        public Double upperBarrier;
        @JSONField(name = "LOWER_BARRIER")
        public Double lowerBarrier;
        @JSONField(name = "RANGE_ACCURE_RATE")
        public Double rangeAccureRate;
        @JSONField(name = "RANGE_DIRECTION")
        public String rangeDirection;
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "MODEL_TYPE")
        public String modelType;
        @JSONField(name = "EPS")
        public Double eps;
        @JSONField(name = "ABS_FLAG")
        public Boolean absFlag;
    }

    /**
     * 区间累计FRTB字段（四类产品通用）。
     */
    public static class RangeAccureFrtbInfo extends RangeAccureBaseInfo {
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
    }

    /**
     * 缓存每个观察日的 sigma 参数，避免重复校准
     */
    protected final class Middle {
        public Map<String, double[]> map1 = new HashMap<>();
        public Map<String, double[]> map = new HashMap<>();
        public boolean newSigma = true;
    }
}

