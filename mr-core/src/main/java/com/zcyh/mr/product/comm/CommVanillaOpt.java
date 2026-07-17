package com.zcyh.mr.product.comm;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
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

/**
 * @author xujg
 * @desc 商品香草期权
 * @date 2024-07-24 09:15
 */
public class CommVanillaOpt {

    private final LocalDate dataDate;
    private final CommVanillaOpt.CommOptInfo commOptInfo;
    private final MarketData marketData;
    private CommOptMeasure measure = new CommOptMeasure();
    private double fxRate = 1.0;
    private final double pos;
    private EurOptUtil eurOptUtil;
    private AmOptUtil amOptUtil;
    private final boolean isAmerican;
    private final Middle middle = new Middle();

    public CommVanillaOpt(LocalDate dataDate, CommVanillaOpt.CommOptInfo tradeInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.commOptInfo = tradeInfo;
        this.marketData = marketData;
        double sign = "B".equalsIgnoreCase(getText(tradeInfo == null ? null : tradeInfo.buyOrSell)) ? 1.0 : -1.0;
        double contractSize = tradeInfo == null || tradeInfo.contractSize == null ? 0.0 : tradeInfo.contractSize;
        this.pos = sign * contractSize;
        this.isAmerican = tradeInfo != null && "AMERICAN".equalsIgnoreCase(tradeInfo.optionType);
    }

    /**
     * 方法说明
     * @date 2024-07-24 09:30:25
     * @author xujg
     */
    public CommOptMeasure calc() {
        validateCmtyRiskFactorInputs();
        List<String> validationErrors = validate();
        if (!validationErrors.isEmpty()) {
            CommOptMeasure err = buildErrorMeasure(validationErrors);
            this.measure = err;
            return err;
        }

        CommOptMeasure measure = calc(this.marketData);
        double pricingToValuationFx = resolvePricingToValuationFx(this.marketData);
        middle.sigma = getSigma();
        measure.delta = getDelta() * pricingToValuationFx;
        measure.impliedVol = middle.sigma;
        measure.gamma = getGamma() * pricingToValuationFx;
        if (isAmerican) {
            measure.theta = amOptUtil.getTheta() * pricingToValuationFx;
        } else {
            measure.theta = eurOptUtil.getThetaByFwdPrice(middle.fwdPrice) * pricingToValuationFx;
        }
        measure.vega = getVega() * pricingToValuationFx;

        measure.position = pos;
        measure.valuationCcy = resolveValuationCurrency();
        measure.dataDate = dataDate;
        measure.productCode = commOptInfo.productCode;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();

        Map<String, Object> detail = new LinkedHashMap<>();
        int days = (int) ChronoUnit.DAYS.between(dataDate, commOptInfo.maturityDate);
        double[] nd = getNdPair(this.marketData, middle.sigma, measure.spotPrice);
        detail.put("定价模型", isAmerican ? "Bjerksund-Stensland" : "Black-Scholes");
        detail.put("到期天数", days);
        if (nd != null) {
            detail.put("N(d1)", nd[0]);
            detail.put("N(d2)", nd[1]);
        }
        String pricingCcy = resolvePricingCurrency();
        String outputCcy = measure.valuationCcy;
        if (hasText(pricingCcy) && hasText(outputCcy) && !pricingCcy.equalsIgnoreCase(outputCcy)) {
            FxSpot fxSpot = null;
            if (this.marketData != null && this.marketData.fxSpot != null) {
                fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE),
                        this.marketData.fxSpot);
            }
            detail.put("PRICING_CCY", pricingCcy);
            detail.put("OUTPUT_CCY", outputCcy);
            detail.put("FX_CONV_RATE", getFxConversionRate(fxSpot, pricingCcy, outputCcy));
        }
        measure.detail = detail;

        this.measure = measure;
        this.measure.sensitivityList = new ArrayList<>();
        this.measure.sensitivityList.addAll(getSensListCMTY());
        this.measure.sensitivityList.addAll(getSensListFx());
        this.measure.sensitivityList.addAll(getSensListGIRR());
        this.measure.sensitivityList.removeIf(e -> Math.abs(e.sensitivityValInstCurrCny) < 1e-12);
        return measure;
    }

    public CommOptMeasure calc(MarketData marketData) {
        validateCmtyRiskFactorInputs();
        if (marketData == null) {
            throw new IllegalArgumentException("市场数据为空");
        }
        middle.newSigma = true;
        CommOptMeasure measure = new CommOptMeasure();
        String pricingCurrency = resolvePricingCurrency();
        String valuationCurrency = resolveValuationCurrency();
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        double valuationFxRate = getRequiredFxRate(fxSpot, valuationCurrency);
        this.fxRate = valuationFxRate;
        IrSpot irSpot = new IrSpot(getDiscountCurveInfo(marketData));
        int days = (int) ChronoUnit.DAYS.between(dataDate, commOptInfo.maturityDate);
        int days2 = (int) ChronoUnit.DAYS.between(dataDate, commOptInfo.settleDate);
        int days1;
        double rd;
        if ("Cash".equalsIgnoreCase(commOptInfo.settleType)) {
            days1 = days;
            rd = irSpot.spotRate(commOptInfo.maturityDate);
        } else {
            days1 = days2;
            rd = irSpot.spotRate(commOptInfo.settleDate);
        }

        double k = commOptInfo.strikePrice;
        boolean call = "CALL".equalsIgnoreCase(commOptInfo.callOrPut);
        boolean cash = "CASH".equalsIgnoreCase(commOptInfo.settleType);
        CommVol commVol = new CommVol(getVolSurfaceInfo(marketData));
        double t = days1 / 365.0,
                maturityT = days / 365.0,
                settleT = days2 / 365.0;
        /* 调用公共类中的bs函数需要即期价格和rf计算出远期价格，所以为调用公共函数到推出rf */
        CommSpot.CommSpotInfo commSpotInfo = getPriceCurveInfo(marketData);
        double spotPrice = resolveNearestSpotPrice(commSpotInfo);
        CommSpot commSpot = new CommSpot(commSpotInfo);
        middle.fwdPrice = "CASH".equalsIgnoreCase(commOptInfo.settleType)
                ? commSpot.fwdPrice(commOptInfo.maturityDate)
                : commSpot.fwdPrice(commOptInfo.settleDate);

        double rf;
        if (t <= 0 || spotPrice <= 0 || middle.fwdPrice <= 0) {
            rf = rd;
        } else {
            rf = rd - 1 / t * Math.log(middle.fwdPrice / spotPrice);
        }
        if (isAmerican) {
            if (middle.newSigma) {
                amOptUtil = new AmOptUtil(call, cash, spotPrice, k, rd, rf, maturityT, settleT,
                        commVol.getVolCur(days));
            } else {
                amOptUtil = new AmOptUtil(call, cash, spotPrice, k, rd, rf, middle.sigma, maturityT, settleT);
            }
            measure.valuationUnit = amOptUtil.getValue();
        } else {
            eurOptUtil = new EurOptUtil(call, cash, spotPrice, k, rd, rf, maturityT, settleT, commVol.getVolCur(days),
                    "black");
            if (middle.newSigma) {
                measure.valuationUnit = eurOptUtil.getValue();
            } else {
                measure.valuationUnit = eurOptUtil.getValue(middle.sigma);
            }
        }
        middle.newSigma = false;

        measure.instrumentId = commOptInfo.instrumentId;
        measure.position = pos;
        measure.spotPrice = spotPrice;
        measure.fwdPrice = middle.fwdPrice;
        double rawValuation = measure.valuationUnit * pos;
        double fxConvRate = getFxConversionRate(fxSpot, pricingCurrency, valuationCurrency);
        measure.valuation = rawValuation * fxConvRate;
        if (pos != 0) {
            measure.valuationUnit = measure.valuation / pos;
        }
        measure.valuationCcy = valuationCurrency;
        measure.valuationCny = measure.valuation * valuationFxRate;

        return measure;
    }

    private double getSigma() {
        return isAmerican ? amOptUtil.getSigma() : eurOptUtil.getSigma();
    }

    private double getDelta() {
        return isAmerican ? amOptUtil.getDelta() : eurOptUtil.getDelta();
    }

    private double getGamma() {
        return isAmerican ? amOptUtil.getGamma() : eurOptUtil.getGamma();
    }

    private double getVega() {
        return isAmerican ? amOptUtil.getVega() : eurOptUtil.getVega();
    }

    private double[] getNdPair(MarketData md, double sigma, double spot) {
        if (isAmerican) {
            return null;
        }
        if (sigma <= 0 || spot <= 0 || commOptInfo.strikePrice == null || commOptInfo.strikePrice <= 0) {
            return null;
        }
        if (md == null || md.irSpot == null || !md.irSpot.containsKey(commOptInfo.discountCurve)) {
            return null;
        }
        int days = (int) ChronoUnit.DAYS.between(dataDate, commOptInfo.maturityDate);
        int days2 = (int) ChronoUnit.DAYS.between(dataDate, commOptInfo.settleDate);
        if (days <= 0) {
            return null;
        }
        IrSpot irSpot = new IrSpot(md.irSpot.get(commOptInfo.discountCurve));
        double rd;
        if ("Cash".equalsIgnoreCase(commOptInfo.settleType)) {
            rd = irSpot.spotRate(commOptInfo.maturityDate);
        } else {
            rd = irSpot.spotRate(commOptInfo.settleDate);
        }
        double maturityT = days / 365.0;
        double settleT = days2 / 365.0;
        double rf;
        if (settleT <= 0 || spot <= 0 || middle.fwdPrice <= 0) {
            rf = rd;
        } else {
            rf = rd - 1 / settleT * Math.log(middle.fwdPrice / spot);
        }
        boolean cash = "CASH".equalsIgnoreCase(commOptInfo.settleType);
        double f = cash ? spot * Math.exp((rd - rf) * maturityT) : spot * Math.exp((rd - rf) * settleT);
        double rootT = Math.sqrt(maturityT);
        double d1 = (Math.log(f / commOptInfo.strikePrice) + 0.5 * sigma * sigma * maturityT) / (sigma * rootT);
        double d2 = d1 - sigma * rootT;
        return new double[] { EurOptUtil.cdf(d1), EurOptUtil.cdf(d2) };
    }

    private List<FrtbSenes> getSensListFx() {
        String sensitivityCurrency = resolveSensitivityCurrency();
        List<FrtbDependency> deltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                new ArrayList<>(getFxDeltaCurrencies()));
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                commOptInfo.settleDate,
                deltaDependencies,
                Collections.emptyList(),
                true,
                false,
                measure.instrumentId,
                sensitivityCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    CommOptMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                });
        return sensitivities;
    }

    private List<FrtbSenes> getSensListGIRR() {
        String sensitivityCurrency = resolveSensitivityCurrency();
        HashMap<String, String> map = new HashMap<>();
        map.put(commOptInfo.discountCurve, commOptInfo.strikeCurrencyCode);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                commOptInfo.settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(map),
                Collections.emptyList(),
                true,
                false,
                measure.instrumentId,
                sensitivityCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    CommOptMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        return sensitivities;
    }

    private List<FrtbSenes> getSensListCMTY() {
        String commBucket = getText(commOptInfo == null ? null : commOptInfo.frtbCommBucket);
        String commAsset = resolveCmtyRiskFactorIdBase();
        if (FrtbSensitivityBuilder.warnMissingCmtySensitivityInputs(
                measure,
                getText(commOptInfo == null ? null : commOptInfo.instrumentId),
                commBucket,
                commAsset)) {
            return new ArrayList<>();
        }
        String sensitivityCurrency = resolveSensitivityCurrency();
        List<FrtbDependency> deltaDependencies = FrtbSensitivityBuilder.buildCmtyDeltaDependencies(
                commOptInfo.referenceCurve,
                resolveCmtyRiskFactorId(),
                commBucket);
        List<FrtbDependency> vegaDependencies = FrtbSensitivityBuilder.buildCmtyVegaDependencies(
                commOptInfo.volatilitySurface,
                resolveCmtyRiskFactorIdVega(),
                commBucket);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildCmtySensitivities(
                marketData,
                dataDate,
                commOptInfo.settleDate,
                deltaDependencies,
                vegaDependencies,
                true,
                true,
                measure.instrumentId,
                sensitivityCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    CommOptMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                () -> middle.newSigma = true);
        return sensitivities;
    }

    private void validateCmtyRiskFactorInputs() {
        resolveUnderlyingForCalc();
    }

    private String resolveCmtyRiskFactorId() {
        String base = resolveCmtyRiskFactorIdBase();
        if (!hasText(base)) {
            return null;
        }
        String location = getText(commOptInfo == null ? null : commOptInfo.frtbCommLocation);
        if (!hasText(location)) {
            return base;
        }
        return base + "&" + location;
    }

    private String resolveCmtyRiskFactorIdBase() {
        String asset = getText(commOptInfo == null ? null : commOptInfo.frtbCommAsset);
        if (hasText(asset)) {
            return asset;
        }
        return null;
    }

    private String resolveCmtyRiskFactorIdVega() {
        return resolveCmtyRiskFactorIdBase();
    }

    private String resolveUnderlyingForCalc() {
        String underlying = getText(commOptInfo == null ? null : commOptInfo.underlyingCode);
        if (hasText(underlying)) {
            return underlying;
        }
        throw new IllegalArgumentException("缺少UNDERLYING_CODE，无法进行商品交易计量");
    }

    static public class CommOptMeasure extends OptionMeasure {
    }

    public static class CommOptInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "OPTION_TYPE")
        public String optionType;
        @ProductInputField(required = true, allowedValues = {"Call", "Put"}, ignoreCase = true)
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @JSONField(name = "UNDERLYING_CODE")
        public String underlyingCode;
        @JSONField(name = "STRIKE_CURRENCY_CODE")
        public String strikeCurrencyCode;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
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
        @JSONField(name = "SETTLE_TYPE")
        public String settleType;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @JSONField(name = "FRTB_COMM_ASSET")
        public String frtbCommAsset;
        @JSONField(name = "FRTB_COMM_LOCATION")
        public String frtbCommLocation;
        @JSONField(name = "FRTB_COMM_BUCKET")
        public String frtbCommBucket;
        @ProductInputField(required = true)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
    }

    private final class Middle {
        public double sigma = 0.0;
        public boolean newSigma = true;
        public double fwdPrice = 0.0;
    }

    private String resolveValuationCurrency() {
        if (commOptInfo == null) {
            return null;
        }
        if (hasText(commOptInfo.currencyCode)) {
            return commOptInfo.currencyCode;
        }
        return commOptInfo.strikeCurrencyCode;
    }

    private String resolvePricingCurrency() {
        if (commOptInfo == null) {
            return null;
        }
        if (hasText(commOptInfo.currencyCode)) {
            return commOptInfo.currencyCode;
        }
        if (hasText(commOptInfo.strikeCurrencyCode)) {
            return commOptInfo.strikeCurrencyCode;
        }
        return resolveValuationCurrency();
    }

    private double getRequiredFxRate(FxSpot fxSpot, String currency) {
        if (!hasText(currency)) {
            throw new IllegalArgumentException("汇率币种为空");
        }
        if ("CNY".equalsIgnoreCase(currency)) {
            return 1.0;
        }
        if (fxSpot == null) {
            throw new IllegalArgumentException("市场数据缺少汇率曲线");
        }
        try {
            double fx = fxSpot.getFxrate(currency);
            if (!Double.isFinite(fx) || fx <= 0) {
                throw new IllegalArgumentException("汇率无效: " + currency + "=" + fx);
            }
            return fx;
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) ex;
            }
            throw new IllegalArgumentException("无法获取汇率: " + currency, ex);
        }
    }

    private double getFxConversionRate(FxSpot fxSpot, String sourceCurrency, String targetCurrency) {
        if (!hasText(sourceCurrency) || !hasText(targetCurrency)) {
            throw new IllegalArgumentException("转换币种为空: source=" + sourceCurrency + ", target=" + targetCurrency);
        }
        if (sourceCurrency.equalsIgnoreCase(targetCurrency)) {
            return 1.0;
        }
        double sourceToCny = getRequiredFxRate(fxSpot, sourceCurrency);
        double targetToCny = getRequiredFxRate(fxSpot, targetCurrency);
        return sourceToCny / targetToCny;
    }

    private double resolvePricingToValuationFx(MarketData md) {
        String pricingCurrency = resolvePricingCurrency();
        String valuationCurrency = resolveValuationCurrency();
        if (!hasText(pricingCurrency) || !hasText(valuationCurrency)
                || pricingCurrency.equalsIgnoreCase(valuationCurrency)) {
            return 1.0;
        }
        if (md == null || md.fxSpot == null) {
            throw new IllegalArgumentException("市场数据缺少汇率曲线");
        }
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return getFxConversionRate(fxSpot, pricingCurrency, valuationCurrency);
    }

    private Set<String> getFxDeltaCurrencies() {
        LinkedHashSet<String> currencies = new LinkedHashSet<>();
        addFxDeltaCurrency(currencies, commOptInfo.strikeCurrencyCode);
        addFxDeltaCurrency(currencies, commOptInfo.currencyCode);
        return currencies;
    }

    private void addFxDeltaCurrency(Set<String> currencies, String currency) {
        if (!hasText(currency)) {
            return;
        }
        String c = currency.trim().toUpperCase(Locale.ROOT);
        if ("CNY".equals(c)) {
            return;
        }
        currencies.add(c);
    }

    private String resolveSensitivityCurrency() {
        if (measure != null && hasText(measure.valuationCcy)) {
            return measure.valuationCcy;
        }
        return resolveValuationCurrency();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String getText(String value) {
        return value == null ? "" : value.trim();
    }

    private List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (commOptInfo == null) {
            errors.add("交易数据为空");
            return errors;
        }
        if (dataDate == null) {
            errors.add("DATA_DATE 为空");
        }
        if (marketData == null) {
            errors.add("市场数据为空");
            return errors;
        }
        if (commOptInfo.contractSize == null || !Double.isFinite(commOptInfo.contractSize)
                || commOptInfo.contractSize <= 0.0) {
            errors.add("CONTRACT_SIZE 必须为正有限数: " + commOptInfo.contractSize);
        }
        if (!hasText(commOptInfo.callOrPut)) {
            errors.add("CALL_OR_PUT 未设置");
        }
        if (!hasText(commOptInfo.buyOrSell)
                || (!"B".equalsIgnoreCase(commOptInfo.buyOrSell) && !"S".equalsIgnoreCase(commOptInfo.buyOrSell))) {
            errors.add("BUY_OR_SELL 仅支持 B/S: " + commOptInfo.buyOrSell);
        }
        if (commOptInfo.strikePrice == null || commOptInfo.strikePrice <= 0) {
            errors.add("STRIKE_PRICE 无效: " + commOptInfo.strikePrice);
        }
        if (commOptInfo.maturityDate == null) {
            errors.add("MATURITY_DATE 未设置");
        }
        if (commOptInfo.settleDate == null) {
            errors.add("SETTLE_DATE 未设置");
        }
        if (!hasText(commOptInfo.discountCurve)) {
            errors.add("DISCOUNT_CURVE 未设置");
        }
        if (!hasText(commOptInfo.referenceCurve)) {
            errors.add("REFERENCE_CURVE 未设置");
        }
        if (!hasText(commOptInfo.volatilitySurface)) {
            errors.add("VOLATILITY_SURFACE 未设置");
        }
        if (!hasText(resolveValuationCurrency())) {
            errors.add("估值币种缺失（CURRENCY_CODE/STRIKE_CURRENCY_CODE）");
        }
        if (!hasText(resolvePricingCurrency())) {
            errors.add("定价币种缺失（CURRENCY_CODE/STRIKE_CURRENCY_CODE）");
        }
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(commOptInfo.discountCurve)
                || marketData.irSpot.get(commOptInfo.discountCurve) == null) {
            errors.add("市场数据缺少利率曲线: " + commOptInfo.discountCurve);
        }
        if (marketData.commSpot == null || !marketData.commSpot.containsKey(commOptInfo.referenceCurve)
                || marketData.commSpot.get(commOptInfo.referenceCurve) == null
                || marketData.commSpot.get(commOptInfo.referenceCurve).curveData == null
                || marketData.commSpot.get(commOptInfo.referenceCurve).curveData.isEmpty()) {
            errors.add("市场数据缺少商品价格曲线: " + commOptInfo.referenceCurve);
        }
        if (marketData.commVol == null || !marketData.commVol.containsKey(commOptInfo.volatilitySurface)
                || marketData.commVol.get(commOptInfo.volatilitySurface) == null
                || marketData.commVol.get(commOptInfo.volatilitySurface).curveData == null
                || marketData.commVol.get(commOptInfo.volatilitySurface).curveData.isEmpty()) {
            errors.add("市场数据缺少商品波动率曲面: " + commOptInfo.volatilitySurface);
        }
        if (marketData.fxSpot == null || marketData.fxSpot.curveData == null || marketData.fxSpot.curveData.isEmpty()) {
            errors.add("市场数据缺少汇率曲线");
        }
        if (errors.isEmpty()) {
            FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
            checkFxRate(errors, fxSpot, resolveValuationCurrency(), "VALUATION_CCY");
            checkFxRate(errors, fxSpot, resolvePricingCurrency(), "PRICING_CCY");
        }
        return errors;
    }

    private void checkFxRate(List<String> errors, FxSpot fxSpot, String currency, String fieldName) {
        if (!hasText(currency) || "CNY".equalsIgnoreCase(currency)) {
            return;
        }
        try {
            double fx = fxSpot.getFxrate(currency);
            if (!Double.isFinite(fx) || fx <= 0) {
                errors.add(fieldName + " 汇率无效: " + currency + "=" + fx);
            }
        } catch (Exception ex) {
            errors.add(fieldName + " 汇率不存在: " + currency);
        }
    }

    private CommOptMeasure buildErrorMeasure(List<String> errors) {
        CommOptMeasure err = new CommOptMeasure();
        if (commOptInfo != null) {
            err.instrumentId = commOptInfo.instrumentId;
            err.productCode = commOptInfo.productCode;
        }
        err.dataDate = dataDate;
        err.position = pos;
        err.status = "ERROR";
        err.logs = Measure.errorLogs(errors);
        return err;
    }

    private IrSpot.IrSpotInfo getDiscountCurveInfo(MarketData md) {
        if (md.irSpot == null) {
            throw new IllegalArgumentException("市场数据缺少利率曲线集合");
        }
        IrSpot.IrSpotInfo info = md.irSpot.get(commOptInfo.discountCurve);
        if (info == null) {
            throw new IllegalArgumentException("市场数据缺少利率曲线: " + commOptInfo.discountCurve);
        }
        return info;
    }

    private CommSpot.CommSpotInfo getPriceCurveInfo(MarketData md) {
        if (md.commSpot == null) {
            throw new IllegalArgumentException("市场数据缺少商品价格曲线集合");
        }
        CommSpot.CommSpotInfo info = md.commSpot.get(commOptInfo.referenceCurve);
        if (info == null || info.curveData == null || info.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少商品价格曲线: " + commOptInfo.referenceCurve);
        }
        return info;
    }

    private double resolveNearestSpotPrice(CommSpot.CommSpotInfo info) {
        Integer nearestDays = null;
        Double nearestPrice = null;
        for (Integer days : info.curveData.keySet()) {
            if (days == null || days < 0) {
                continue;
            }
            Double price = info.curveData.get(days);
            if (price == null) {
                continue;
            }
            if (nearestDays == null || days < nearestDays) {
                nearestDays = days;
                nearestPrice = price;
            }
        }
        if (nearestPrice == null) {
        throw new IllegalArgumentException("价格曲线缺少可用即期/近端点: " + commOptInfo.referenceCurve);
        }
        return nearestPrice;
    }

    private CommVol.CommVolInfo getVolSurfaceInfo(MarketData md) {
        if (md.commVol == null) {
            throw new IllegalArgumentException("市场数据缺少商品波动率曲面集合");
        }
        CommVol.CommVolInfo info = md.commVol.get(commOptInfo.volatilitySurface);
        if (info == null || info.curveData == null || info.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少商品波动率曲面: " + commOptInfo.volatilitySurface);
        }
        return info;
    }

    private double getFxRate(String currency) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        return getRequiredFxRate(fxSpot, currency);
    }
}

