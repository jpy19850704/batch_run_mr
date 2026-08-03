package com.zcyh.mr.product.fx;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Convert;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;
import com.zcyh.mr.product.basic.option.AmOptUtil;
import com.zcyh.mr.product.basic.option.EurOptUtil;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * FX 香草期权统一产品（支持欧式和美式）
 * 通过 OPTION_TYPE 字段区分定价模型：
 * - EUROPEAN（默认）：Black-Scholes 模型
 * - AMERICAN：Bjerksund-Stensland 近似模型
 */
public class FxVanillaOpt {

    private final LocalDate dataDate;
    private final VanillaOptTradeInfo info;
    private final MarketData marketData;
    private VanillaOptMeasure measure;
    private final double pos;

    /** 欧式定价工具，美式时为 null */
    private EurOptUtil eurUtil;
    /** 美式定价工具，欧式时为 null */
    private AmOptUtil amUtil;

    private final Middle middle = new Middle();

    /** 是否美式期权 */
    private final boolean isAmerican;

    public FxVanillaOpt(LocalDate dataDate, VanillaOptTradeInfo tradeInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.info = tradeInfo;
        this.marketData = marketData;
        this.pos = ("B".equals(info.buyOrSell) ? 1 : -1) * info.contractSize;
        this.isAmerican = "AMERICAN".equalsIgnoreCase(info.optionType);
    }

    // ========== 核心计算 ==========

    /**
     * 完整估值（含 Greeks 和 FRTB 敏感性）
     * 先执行输入校验，校验通过后进行计量并填充中间明细
     */
    public VanillaOptMeasure calc() {
        // 输入校验
        List<String> validationErrors = validate();
        if (!validationErrors.isEmpty()) {
            VanillaOptMeasure m = new VanillaOptMeasure();
            m.instrumentId = info.instrumentId;
            m.productCode = info.productCode;
            m.dataDate = dataDate;
            m.status = "ERROR";
            m.logs = Measure.errorLogs(validationErrors);
            this.measure = m;
            return measure;
        }

        VanillaOptMeasure m = calc(this.marketData);
        m.impliedVol = getSigma();
        middle.sigma = getSigma();
        m.delta = getDelta();
        m.gamma = getGamma();
        m.theta = getTheta();
        m.vega = getVega();

        m.productCode = info.productCode;
        m.dataDate = dataDate;
        m.status = "SUCCESS";
        m.logs = new ArrayList<>();

        // 填充中间计算明细（仅首次完整计量时生成）
        Map<String, Object> detail = new LinkedHashMap<>();
        int days = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        double[] nd = getNdPair(this.marketData, m.impliedVol, m.spotPrice);
        detail.put("定价模型", isAmerican ? "Bjerksund-Stensland" : "Black-Scholes");
        detail.put("到期天数", days);
        if (nd != null) {
            detail.put("N(d1)", nd[0]);
            detail.put("N(d2)", nd[1]);
        }
        m.detail = detail;

        this.measure = m;
        calcFrtbSens();
        return measure;
    }

    /**
     * 场景估值（仅计算估值，不计算 Greeks）
     */
    public VanillaOptMeasure calc(MarketData md) {
        middle.newSigma = true;
        VanillaOptMeasure m = new VanillaOptMeasure();
        // 获取利率
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(info.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(info.baseDiscountCurve));
        int days = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        int days2 = (int) ChronoUnit.DAYS.between(dataDate, info.settleDate);
        double rd, rf;
        if ("Cash".equalsIgnoreCase(info.settleType)) {
            rd = bIrSpot.spotRate(info.maturityDate);
            rf = uIrSpot.spotRate(info.maturityDate);
        } else {
            rd = bIrSpot.spotRate(info.settleDate);
            rf = uIrSpot.spotRate(info.settleDate);
        }
        // 获取汇率
        FxSpot fxSpotNew = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        double s = fxSpotNew.getFxrate(info.baseCurrencyCode, info.underlyingCurrencyCode);
        double k = info.strikePrice;
        boolean call = "CALL".equalsIgnoreCase(info.callOrPut);
        boolean cash = "CASH".equalsIgnoreCase(info.settleType);
        double maturityT = days / 365.0;
        double settleT = days2 / 365.0;
        double americanRd = cash ? rd : bIrSpot.spotRate(info.maturityDate);
        double americanRf = cash ? rf : uIrSpot.spotRate(info.maturityDate);
        double physicalDiscountFactor = 1.0;
        double physicalForwardRatio = 1.0;
        if (!cash) {
            physicalDiscountFactor = bIrSpot.fwdDiscount(info.maturityDate, info.settleDate);
            double underlyingForwardDiscount = uIrSpot.fwdDiscount(info.maturityDate, info.settleDate);
            physicalForwardRatio = underlyingForwardDiscount / physicalDiscountFactor;
        }
        // 获取波动率
        FxVol fxVol = new FxVol(md.fxVol.get(info.volatilitySurface));

        // 根据期权类型选择定价模型
        if (isAmerican) {
            if (middle.newSigma) {
                amUtil = new AmOptUtil(call, cash, s, k, americanRd, americanRf,
                        maturityT, settleT, physicalDiscountFactor, physicalForwardRatio,
                        fxVol.getVolCur(days));
            } else {
                amUtil = new AmOptUtil(call, cash, s, k, americanRd, americanRf,
                        middle.sigma, maturityT, settleT,
                        physicalDiscountFactor, physicalForwardRatio);
            }
            m.valuationUnit = amUtil.getValue();
        } else {
            eurUtil = new EurOptUtil(call, cash, s, k, rd, rf, maturityT, settleT, fxVol.getVolCur(days), "black");
            if (middle.newSigma) {
                m.valuationUnit = eurUtil.getValue();
            } else {
                m.valuationUnit = eurUtil.getValue(middle.sigma);
            }
        }
        middle.newSigma = false;

        m.spotPrice = s;
        m.instrumentId = info.instrumentId;
        m.position = pos;
        m.valuation = m.valuationUnit * pos;
        m.valuationCcy = info.baseCurrencyCode;
        m.valuationCny = m.valuation * fxSpotNew.getFxrate(info.baseCurrencyCode);
        return m;
    }

    // ========== 输入校验 ==========

    /**
     * 校验交易输入字段，返回错误信息列表
     * 列表为空表示校验通过
     */
    private List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (!"B".equalsIgnoreCase(info.buyOrSell) && !"S".equalsIgnoreCase(info.buyOrSell))
            errors.add("BUY_OR_SELL 仅支持 B/S: " + info.buyOrSell);
        if (info.contractSize == null || !Double.isFinite(info.contractSize) || info.contractSize <= 0.0)
            errors.add("CONTRACT_SIZE 必须为正有限数: " + info.contractSize);
        if (info.strikePrice == null || info.strikePrice <= 0)
            errors.add("STRIKE_PRICE 无效: " + info.strikePrice);
        if (info.maturityDate == null)
            errors.add("MATURITY_DATE 未设置");
        else if (!info.maturityDate.isAfter(dataDate))
            errors.add("MATURITY_DATE 已过期: " + info.maturityDate + " <= " + dataDate);
        if (info.settleDate == null)
            errors.add("SETTLE_DATE 未设置");
        else if (!"Cash".equalsIgnoreCase(info.settleType)
                && info.maturityDate != null && info.settleDate.isBefore(info.maturityDate))
            errors.add("实物交割日早于到期日: " + info.settleDate + " < " + info.maturityDate);
        if (info.baseCurrencyCode == null || info.baseCurrencyCode.isEmpty())
            errors.add("BASE_CURRENCY_CODE 未设置");
        if (info.underlyingCurrencyCode == null || info.underlyingCurrencyCode.isEmpty())
            errors.add("UNDERLYING_CURRENCY_CODE 未设置");
        if (info.baseDiscountCurve == null || info.baseDiscountCurve.isEmpty())
            errors.add("BASE_DISCOUNT_CURVE 未设置");
        if (info.underlyingDiscountCurve == null || info.underlyingDiscountCurve.isEmpty())
            errors.add("UNDERLYING_DISCOUNT_CURVE 未设置");
        if (info.volatilitySurface == null || info.volatilitySurface.isEmpty())
            errors.add("VOLATILITY_SURFACE 未设置");
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(info.baseDiscountCurve))
            errors.add("市场数据缺少利率曲线: " + info.baseDiscountCurve);
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(info.underlyingDiscountCurve))
            errors.add("市场数据缺少利率曲线: " + info.underlyingDiscountCurve);
        if (info.volatilitySurface != null
                && (marketData.fxVol == null || !marketData.fxVol.containsKey(info.volatilitySurface)))
            errors.add("市场数据缺少波动率曲面: " + info.volatilitySurface);
        return errors;
    }

    // ========== Greeks 代理 ==========

    private double getSigma() {
        return isAmerican ? amUtil.getSigma() : eurUtil.getSigma();
    }

    private double getDelta() {
        return isAmerican ? amUtil.getDelta() : eurUtil.getDelta();
    }

    private double getGamma() {
        return isAmerican ? amUtil.getGamma() : eurUtil.getGamma();
    }

    private double getTheta() {
        return isAmerican ? amUtil.getTheta() : eurUtil.getTheta();
    }

    private double getVega() {
        return isAmerican ? amUtil.getVega() : eurUtil.getVega();
    }

    private double getDRho() {
        return isAmerican ? amUtil.getDRho() : eurUtil.getDRho();
    }

    private double getFRho() {
        return isAmerican ? amUtil.getFRho() : eurUtil.getFRho();
    }

    private double[] getNdPair(MarketData md, double sigma, double spot) {
        if (isAmerican) {
            return null;
        }
        if (sigma <= 0 || spot <= 0 || info.strikePrice == null || info.strikePrice <= 0) {
            return null;
        }
        int days = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        int days2 = (int) ChronoUnit.DAYS.between(dataDate, info.settleDate);
        if (days <= 0) {
            return null;
        }
        IrSpot uIrSpot = new IrSpot(md.irSpot.get(info.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(md.irSpot.get(info.baseDiscountCurve));
        double rd;
        double rf;
        if ("Cash".equalsIgnoreCase(info.settleType)) {
            rd = bIrSpot.spotRate(info.maturityDate);
            rf = uIrSpot.spotRate(info.maturityDate);
        } else {
            rd = bIrSpot.spotRate(info.settleDate);
            rf = uIrSpot.spotRate(info.settleDate);
        }
        boolean cash = "CASH".equalsIgnoreCase(info.settleType);
        double maturityT = days / 365.0;
        double settleT = days2 / 365.0;
        double f = cash ? spot * Math.exp((rd - rf) * maturityT) : spot * Math.exp((rd - rf) * settleT);
        double rootT = Math.sqrt(maturityT);
        double d1 = (Math.log(f / info.strikePrice) + 0.5 * sigma * sigma * maturityT) / (sigma * rootT);
        double d2 = d1 - sigma * rootT;
        return new double[] { EurOptUtil.cdf(d1), EurOptUtil.cdf(d2) };
    }

    // ========== FRTB 敏感性计算 ==========

    private void calcFrtbSens() {
        String uCurrency = info.underlyingCurrencyCode;
        String bCurrency = info.baseCurrencyCode;
        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(info.underlyingDiscountCurve, info.underlyingCurrencyCode);
        curveMap.put(info.baseDiscountCurve, info.baseCurrencyCode);
        List<FrtbSenes> list = new ArrayList<>();
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                collectFxDeltaDependencies(),
                collectFxVegaDependencies(),
                true,
                true,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    VanillaOptMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                () -> middle.newSigma = true);
        list.addAll(fxSensitivities);

        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                info.settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                Collections.emptyList(),
                true,
                false,
                info.instrumentId,
                info.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    VanillaOptMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);
        measure.sensitivityList = list;
    }

    private List<FrtbDependency> collectFxDeltaDependencies() {
        List<String> riskCurrencies = new ArrayList<>();
        if (hasText(info.underlyingCurrencyCode)) {
            riskCurrencies.add(info.underlyingCurrencyCode);
        }
        if (hasText(info.baseCurrencyCode)) {
            riskCurrencies.add(info.baseCurrencyCode);
        }
        return FrtbSensitivityBuilder.buildFxDeltaDependencies(
                riskCurrencies,
                FrtbSensitivityBuilder.buildFxPair(info.underlyingCurrencyCode, info.baseCurrencyCode));
    }

    private List<FrtbDependency> collectFxVegaDependencies() {
        String undCcy = normalizeCcy(info.underlyingCurrencyCode);
        String baseCcy = normalizeCcy(info.baseCurrencyCode);
        String riskFactorId = "FX_" + undCcy + "_" + baseCcy + "_VOL";
        String bucket = undCcy + "/" + baseCcy;
        return FrtbSensitivityBuilder.buildFxVegaDependencies(info.volatilitySurface, riskFactorId, bucket);
    }

    private String normalizeCcy(String ccy) {
        if (ccy == null) {
            return "";
        }
        return ccy.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isDomesticFxCurrency(String ccy) {
        return "CNY".equalsIgnoreCase(ccy) || "CNH".equalsIgnoreCase(ccy);
    }

    // ========== 数据结构 ==========

    public static class VanillaOptMeasure extends OptionMeasure {
    }

    public static class VanillaOptTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "OPTION_TYPE")
        public String optionType;
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
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyy-MM-dd")
        public LocalDate settleDate;
        @JSONField(name = "SETTLE_TYPE")
        public String settleType;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField(required = true)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
    }

    /** sigma 缓存控制 */
    private final class Middle {
        public double sigma = 0.0;
        public boolean newSigma = true;
    }
}

