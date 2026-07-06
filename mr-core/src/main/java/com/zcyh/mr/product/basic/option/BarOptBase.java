package com.zcyh.mr.product.basic.option;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolUtil;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 障碍期权产品基类。
 * 提取 Ir/Eq/Fx/Comm BarOpt 的公共估值逻辑。
 * OPTION_TYPE 驱动单双障碍判定：Call→Up单障碍，Put→Down单障碍，Double→双障碍。
 *
 * @param <I> 子类 Info 类型，必须继承 BarOptBaseInfo
 */
public abstract class BarOptBase<I extends BarOptBase.BarOptBaseInfo> {

    protected final LocalDate dataDate;
    protected final I info;
    protected final MarketData marketData;
    protected final double pos;

    protected BarOptBase(LocalDate dataDate, I info, MarketData marketData) {
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
     * 根据 OPTION_TYPE 判断是否为双障碍。
     */
    protected boolean isDoubleBarrier() {
        return "Double".equalsIgnoreCase(info.optionType);
    }

    /**
     * 根据 OPTION_TYPE 推导障碍方向。
     * Call→Up，Put→Down，Double 时返回 null。
     */
    protected String resolveBarrierDirection() {
        if ("Call".equalsIgnoreCase(info.optionType)) {
            return "Up";
        } else if ("Put".equalsIgnoreCase(info.optionType)) {
            return "Down";
        }
        return null;
    }

    /**
     * 解析障碍类型字符串，供 BarOptUtil 使用。
     */
    protected String resolveBarrierType() {
        return isDoubleBarrier() ? "Double_Barrier" : "Single_Barrier";
    }

    /**
     * 基准估值入口：校验 + 核心估值。
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
        double rd = getDiscountRate(md);
        double rebase = getRebaseRate(md);
        double rf = getRf(md, s, rd, t);
        double fwd = getFwdPrice(md, s, rd, rf, t);

        double l = info.downBarrierPrice == null ? Double.NaN : info.downBarrierPrice;
        double u = info.upBarrierPrice == null ? Double.NaN : info.upBarrierPrice;
        String barrierDirection = resolveBarrierDirection();
        double h;
        if (isDoubleBarrier()) {
            h = Double.NaN;
        } else {
            h = "Down".equalsIgnoreCase(barrierDirection) ? l : u;
        }
        boolean knockout = "true".equalsIgnoreCase(info.knockOutFlag);
        boolean barrierHit = "true".equalsIgnoreCase(info.touchBeforeFlag);
        double rebate = info.payoffLower;
        String type = resolveBarrierType();
        double k = "Single_Barrier".equalsIgnoreCase(type) ? h : fwd;

        List<Map<String, Object>> volCur = getVolCur(md, days);
        double sigma;
        if (isDoubleBarrier()) {
            sigma = interpolateAtmVol(volCur);
        } else {
            EurOptUtil optUtil = new EurOptUtil(true, true, s, k, rd, rf, t, t, volCur, "black");
            sigma = optUtil.getSigma();
        }

        boolean vv = Boolean.TRUE.equals(info.vvFlag);
        BarOptUtil barUtil = new BarOptUtil(s, rebate, h, l, u, rd, rf, rebase, sigma, t,
                barrierDirection, knockout, barrierHit, type,
                vv, volCur, k, isDoubleBarrier());

        boolean applyNonNegativeFloor = vv && isNonNegativeFloorEnabled();
        BarOptUtil.PricingResult pricingResult = barUtil.evaluate();
        double value = applyFloorIfNeeded(pricingResult.value, applyNonNegativeFloor);

        OptionMeasure measure = buildMeasure(barUtil, pricingResult, s, fwd, sigma, value, md);
        return measure;
    }

    /**
     * VV 开启时统一读取非负兜底开关。
     */
    private boolean isNonNegativeFloorEnabled() {
        String value = Configure.getInstance().getValue(Constants.CFG.NON_NEGATIVE_FLOOR_ENABLED);
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        return Boolean.parseBoolean(value.trim());
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

    protected OptionMeasure buildMeasure(BarOptUtil barUtil, BarOptUtil.PricingResult pricingResult,
            double s, double fwd, double sigma, double value, MarketData md) {
        double fxRate = getFxRate(md);
        OptionMeasure measure = new OptionMeasure();
        measure.valuationUnit = value + info.basePayoff;
        measure.valuation = measure.valuationUnit * pos;
        measure.valuationCny = measure.valuation * fxRate;
        measure.spotPrice = s;
        measure.fwdPrice = fwd;
        measure.impliedVol = sigma;
        measure.delta = barUtil.Delta();
        measure.gamma = barUtil.Gamma();
        measure.vega = barUtil.Vega();
        measure.theta = barUtil.Theta();
        measure.instrumentId = info.instrumentId;
        measure.productCode = info.productCode;
        measure.dataDate = dataDate;
        measure.position = pos;
        measure.valuationCcy = getCurrencyCode();
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        measure.detail = buildDetail(pricingResult, sigma);
        return measure;
    }

    /**
     * 直接从波动率曲线按 Delta=0.5 插值取 ATM vol，不进行 goalSeek 迭代。
     * 双障碍产品和三层蛋糕产品使用此方法。
     */
    protected static double interpolateAtmVol(List<Map<String, Object>> volCur) {
        if (volCur == null || volCur.isEmpty()) {
            return 0.2;
        }
        Double[] deltas = volCur.stream()
                .map(e -> com.zcyh.mr.core.Convert.toDouble(e.get("DELTA")))
                .toArray(Double[]::new);
        Double[] vols = volCur.stream()
                .map(e -> com.zcyh.mr.core.Convert.toDouble(e.get("VOLATILITY_RATE")))
                .toArray(Double[]::new);
        double sigma = com.zcyh.mr.core.Interpolation.interpolate(deltas, vols, 0.5,
                VolUtil.requireAxis2InterpolateType(volCur));
        return Double.isFinite(sigma) && sigma > 0.0 ? sigma : 0.2;
    }
    /**
     * 构建附加明细。
     */
    protected Map<String, Object> buildDetail(BarOptUtil.PricingResult pricingResult, double usedSigma) {
        LinkedHashMap<String, Object> d = new LinkedHashMap<>();
        d.put("OPTION_TYPE", info.optionType);
        d.put("KNOCK_OUT_FLAG", info.knockOutFlag);
        d.put("TOUCH_BEFORE_FLAG", info.touchBeforeFlag);
        d.put("DOWN_BARRIER", info.downBarrierPrice);
        d.put("UPPER_BARRIER", info.upBarrierPrice);
        d.put("BASE_PAYOFF", info.basePayoff);
        d.put("PAYOFF_LOWER", info.payoffLower);
        d.put("USED_SIGMA", usedSigma);
        d.put("NO_TOUCH_PROB", pricingResult.noTouchProb);
        if (Boolean.TRUE.equals(info.vvFlag)) {
            d.put("VV_ADJ", pricingResult.vvAdjustment);
        }
        return d;
    }

    /**
     * 外汇障碍期权公共 FRTB 输出模板。
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
     * 权益障碍期权公共 FRTB 输出模板。
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
     * 利率障碍期权公共 FRTB 输出模板。
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
     * 商品障碍期权公共 FRTB 输出模板。
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

        if (!hasText(cmtyBucket) || !hasText(cmtyRiskFactorId) || !hasText(cmtyRiskFactorIdVega)) {
            if (!hasText(cmtyBucket)) {
                measure.addWarningLog("FRTB_COMM_BUCKET为空，跳过CMTY敏感性计算(INSTRUMENT_ID="
                        + (info.instrumentId == null ? "" : info.instrumentId) + ")");
            }
            if (!hasText(cmtyRiskFactorId) || !hasText(cmtyRiskFactorIdVega)) {
                measure.addWarningLog("FRTB_COMM_ASSET为空，跳过CMTY敏感性计算(INSTRUMENT_ID="
                        + (info.instrumentId == null ? "" : info.instrumentId) + ")");
            }
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

    private MeasureValuation toMeasureValuation(OptionMeasure measure) {
        if (measure == null) {
            return null;
        }
        return MeasureValuation.of(measure.valuation, measure.valuationCny);
    }

    private List<String> collectFxRiskCurrencies(String... currencies) {
        LinkedHashSet<String> fxRiskCurrencies = new LinkedHashSet<>();
        for (String currency : currencies) {
            if (currency == null || currency.trim().isEmpty()) {
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
        return ccy == null ? "" : ccy.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
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

    protected void validateCommon() {
        if (info.maturityDate == null || !info.maturityDate.isAfter(dataDate))
            throw new IllegalArgumentException("MATURITY_DATE 必须晚于 DATA_DATE");
        if (info.optionType == null || info.optionType.trim().isEmpty())
            throw new IllegalArgumentException("OPTION_TYPE 不能为空");
        String ot = info.optionType.trim().toLowerCase();
        if (!"call".equals(ot) && !"put".equals(ot) && !"double".equals(ot))
            throw new IllegalArgumentException("OPTION_TYPE 仅支持 Call/Put/Double: " + info.optionType);
        if (isDoubleBarrier()) {
            if (info.downBarrierPrice == null)
                throw new IllegalArgumentException("Double 障碍必须提供 DOWN_BARRIER");
            if (info.upBarrierPrice == null)
                throw new IllegalArgumentException("Double 障碍必须提供 UPPER_BARRIER");
            if (info.downBarrierPrice >= info.upBarrierPrice)
                throw new IllegalArgumentException("Double 障碍要求 UPPER_BARRIER > DOWN_BARRIER");
        } else {
            String dir = resolveBarrierDirection();
            if ("Down".equals(dir) && info.downBarrierPrice == null)
                throw new IllegalArgumentException("Put(Down) 必须提供 DOWN_BARRIER");
            if ("Up".equals(dir) && info.upBarrierPrice == null)
                throw new IllegalArgumentException("Call(Up) 必须提供 UPPER_BARRIER");
        }
    }

    protected static String textOrDefault(String text, String dft) {
        if (text == null || text.trim().isEmpty())
            return dft;
        return text.trim();
    }

    protected static boolean hasText(String text) {
        return text != null && !text.trim().isEmpty();
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

    /** 获取结算折现利率（用于 rebate 折现） */
    protected abstract double getRebaseRate(MarketData md);

    /** 获取币种代码 */
    protected abstract String getCurrencyCode();

    /** 获取 CNY 汇率转换系数 */
    protected abstract double getFxRate(MarketData md);

    /** 子类特有输入校验 */
    protected abstract void validateSpecific(MarketData md);

    /**
     * 障碍期权公共输入信息基类。
     */
    public static class BarOptBaseInfo {
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "OPTION_TYPE")
        public String optionType;
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
        @JSONField(name = "BASE_PAYOFF")
        public Double basePayoff;
        @JSONField(name = "PAYOFF_LOWER")
        public Double payoffLower;
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "KNOCK_OUT_FLAG")
        public String knockOutFlag;
        @JSONField(name = "DOWN_BARRIER")
        public Double downBarrierPrice;
        @JSONField(name = "UPPER_BARRIER")
        public Double upBarrierPrice;
        @JSONField(name = "TOUCH_BEFORE_FLAG")
        public String touchBeforeFlag;
        /** VV 开关：true 时启用 Vanna-Volga overhedge 调整 */
        @JSONField(name = "VV_FLAG")
        public Boolean vvFlag;
    }
}


