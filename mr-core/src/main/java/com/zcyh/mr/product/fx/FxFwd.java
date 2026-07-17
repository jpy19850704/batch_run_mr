package com.zcyh.mr.product.fx;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.Preconditions;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外汇远期估值类。
 */
public class FxFwd {
    private LocalDate dataDate;
    private FxFwdInfo fxFwdInfo;
    private MarketData marketData;
    private FxFwdMeasure fxFwdMeasure = new FxFwdMeasure();

    public FxFwd(LocalDate dataDate, FxFwdInfo tradeInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.fxFwdInfo = tradeInfo;
        this.marketData = marketData;
    }

    /**
     * 外汇远期计量。
     */
    public FxFwdMeasure calc() {
        Preconditions.require(dataDate != null, "dataDate must be set");

        FxFwdMeasure result = calc(marketData);
        LocalDate settleDate = fxFwdInfo.settleDate;

        IrSpot uIrSpot = new IrSpot(marketData.irSpot.get(fxFwdInfo.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(marketData.irSpot.get(fxFwdInfo.baseDiscountCurve));

        String uCurrency = fxFwdInfo.underlyingCurrencyCode;
        String bCurrency = fxFwdInfo.baseCurrencyCode;

        double uRate = uIrSpot.spotRate(settleDate);
        double uDisc = uIrSpot.discount(settleDate);
        double bRate = bIrSpot.spotRate(settleDate);
        double bDisc = bIrSpot.discount(settleDate);

        fxFwdMeasure.valuation = result.valuation;
        fxFwdMeasure.uPv01 = result.uPv01;
        fxFwdMeasure.bPv01 = result.bPv01;
        fxFwdMeasure.pv01 = result.pv01;
        fxFwdMeasure.valuationCny = result.valuationCny;
        fxFwdMeasure.valuationUnit = result.valuationUnit;
        fxFwdMeasure.valuationCcy = result.valuationCcy;
        fxFwdMeasure.position = result.position;
        fxFwdMeasure.instrumentId = fxFwdInfo.instrumentId;
        fxFwdMeasure.productCode = fxFwdInfo.productCode;
        fxFwdMeasure.dataDate = dataDate;
        fxFwdMeasure.status = "SUCCESS";
        fxFwdMeasure.logs = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("UNDERLYING_PV01", fxFwdMeasure.uPv01);
        detail.put("BASE_PV01", fxFwdMeasure.bPv01);
        fxFwdMeasure.detail = detail;

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(fxFwdInfo.underlyingDiscountCurve, uCurrency);
        curveMap.put(fxFwdInfo.baseDiscountCurve, bCurrency);

        getFrtbSenesList(uCurrency, bCurrency, curveMap);
        getCashFlowList(uRate, bRate, uDisc, bDisc);
        return fxFwdMeasure;
    }

    public FxFwdMeasure calc(MarketData newMarketData) {
        Preconditions.require(dataDate != null, "dataDate must be set");
        validateInputs(newMarketData);
        LocalDate settleDate = fxFwdInfo.settleDate;

        IrSpot uIrSpot = new IrSpot(newMarketData.irSpot.get(fxFwdInfo.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(newMarketData.irSpot.get(fxFwdInfo.baseDiscountCurve));
        FxSpot fxSpotNew = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), newMarketData.fxSpot);
        String uCurrency = fxFwdInfo.underlyingCurrencyCode;
        String bCurrency = fxFwdInfo.baseCurrencyCode;
        double fxRate = fxSpotNew.getFxrate(bCurrency, uCurrency);

        double uDisc = uIrSpot.discount(settleDate);
        double bDisc = bIrSpot.discount(settleDate);

        double uNotional = fxFwdInfo.underlyingCurrencyNotional;
        double bNotional = fxFwdInfo.baseCurrencyNotional;
        int sign = "B".equalsIgnoreCase(fxFwdInfo.buyOrSell) ? 1 : -1;

        double cnyFxrate = 1.0;
        if (!"CNY".equals(bCurrency)) {
            cnyFxrate = fxSpotNew.getFxrate(bCurrency);
        }
        double value = (uNotional * uDisc * fxRate - bNotional * bDisc) * sign;

        double uDisc1 = uIrSpot.discount(settleDate, 0.0001);
        double uPv01 = uNotional * fxRate * (uDisc1 - uDisc) * sign;

        double bDisc1 = bIrSpot.discount(settleDate, 0.0001);
        double bPv01 = bNotional * (bDisc - bDisc1) * sign;

        FxFwdMeasure result = new FxFwdMeasure();
        result.valuation = value;
        result.uPv01 = uPv01 * cnyFxrate;
        result.bPv01 = bPv01 * cnyFxrate;
        result.pv01 = (uPv01 + bPv01) * cnyFxrate;
        result.valuationCny = value * cnyFxrate;
        result.valuationCcy = bCurrency;
        result.position = uNotional * sign;
        result.valuationUnit = result.position == 0.0 ? 0.0 : result.valuation / result.position;
        result.instrumentId = fxFwdInfo.instrumentId;
        return result;
    }

    private void getCashFlowList(double uRate, double bRate, double uDisc, double bDisc) {
        LocalDate settleDate = fxFwdInfo.settleDate;
        double uNotional = fxFwdInfo.underlyingCurrencyNotional;
        double bNotional = fxFwdInfo.baseCurrencyNotional;
        int dayCount = (int) ChronoUnit.DAYS.between(dataDate, settleDate);
        int sign = "B".equalsIgnoreCase(fxFwdInfo.buyOrSell) ? 1 : -1;
        List<BaseCashFlow> cfList = new ArrayList<>();
        if (dayCount > 0) {
            BaseCashFlow uCashFlow = new BaseCashFlow();
            uCashFlow.dataDate = dataDate;
            uCashFlow.paymentDate = settleDate;
            uCashFlow.currencyCode = fxFwdInfo.underlyingCurrencyCode;
            uCashFlow.cashFlowType = "PRINCIPAL";
            uCashFlow.cashflow = uNotional * sign;
            uCashFlow.discountRate = uRate;
            uCashFlow.discountFactor = uDisc;
            cfList.add(uCashFlow);

            BaseCashFlow bCashFlow = new BaseCashFlow();
            bCashFlow.dataDate = dataDate;
            bCashFlow.paymentDate = settleDate;
            bCashFlow.currencyCode = fxFwdInfo.baseCurrencyCode;
            bCashFlow.cashFlowType = "PRINCIPAL";
            bCashFlow.cashflow = bNotional * (-sign);
            bCashFlow.discountRate = bRate;
            bCashFlow.discountFactor = bDisc;
            cfList.add(bCashFlow);
        }
        fxFwdMeasure.cashFlowList = cfList;
    }

    private void getFrtbSenesList(String uCurrency, String bCurrency, HashMap<String, String> curveMap) {
        List<FrtbSenes> list = new ArrayList<>();

        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                fxFwdInfo.settleDate,
                collectFxDeltaDependencies(uCurrency, bCurrency),
                new ArrayList<>(),
                true,
                false,
                fxFwdInfo.instrumentId,
                fxFwdInfo.baseCurrencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(fxFwdMeasure.valuation, fxFwdMeasure.valuationCny),
                shockedMarketData -> {
                    FxFwdMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                });
        list.addAll(fxSensitivities);

        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                fxFwdInfo.settleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                fxFwdInfo.instrumentId,
                fxFwdInfo.baseCurrencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(fxFwdMeasure.valuation, fxFwdMeasure.valuationCny),
                shockedMarketData -> {
                    FxFwdMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);
        fxFwdMeasure.sensitivityList = list;
    }

    private List<FrtbDependency> collectFxDeltaDependencies(String uCurrency, String bCurrency) {
        List<String> riskCurrencies = new ArrayList<>();
        if (!"CNY".equalsIgnoreCase(uCurrency)) {
            riskCurrencies.add(uCurrency);
        }
        if (!"CNY".equalsIgnoreCase(bCurrency)) {
            riskCurrencies.add(bCurrency);
        }
        return FrtbSensitivityBuilder.buildFxDeltaDependencies(riskCurrencies);
    }

    private void validateInputs(MarketData md) {
        if (fxFwdInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        requireText(fxFwdInfo.productCode, "PRODUCT_CODE");
        requireText(fxFwdInfo.instrumentId, "INSTRUMENT_ID");
        if (!"B".equalsIgnoreCase(fxFwdInfo.buyOrSell)
                && !"S".equalsIgnoreCase(fxFwdInfo.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + fxFwdInfo.buyOrSell);
        }
        requireCurrencyCode(fxFwdInfo.underlyingCurrencyCode, "UNDERLYING_CURRENCY_CODE");
        requireCurrencyCode(fxFwdInfo.baseCurrencyCode, "BASE_CURRENCY_CODE");
        requireNonNegativeFinite(fxFwdInfo.underlyingCurrencyNotional, "UNDERLYING_CURRENCY_NOTIONAL");
        requireNonNegativeFinite(fxFwdInfo.baseCurrencyNotional, "BASE_CURRENCY_NOTIONAL");
        if (fxFwdInfo.settleDate == null) {
            throw new IllegalArgumentException("SETTLE_DATE 不能为空");
        }
        requireText(fxFwdInfo.underlyingDiscountCurve, "UNDERLYING_DISCOUNT_CURVE");
        requireText(fxFwdInfo.baseDiscountCurve, "BASE_DISCOUNT_CURVE");
        validateMarketData(md, fxFwdInfo.underlyingDiscountCurve, fxFwdInfo.baseDiscountCurve);
    }

    private static void validateMarketData(MarketData md, String underlyingCurve, String baseCurve) {
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空");
        }
        if (md.irSpot == null || !md.irSpot.containsKey(underlyingCurve)) {
            throw new IllegalArgumentException("市场数据缺少标的货币折现曲线: " + underlyingCurve);
        }
        if (!md.irSpot.containsKey(baseCurve)) {
            throw new IllegalArgumentException("市场数据缺少基础货币折现曲线: " + baseCurve);
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少外汇即期曲线");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    private static void requireCurrencyCode(String value, String field) {
        requireText(value, field);
        if (value.length() != 3) {
            throw new IllegalArgumentException(field + " 必须为3位货币代码: " + value);
        }
    }

    private static void requireNonNegativeFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " 必须为非负有限数: " + value);
        }
    }

    static public class FxFwdMeasure extends Measure {
        @JSONField(serialize = false)
        public double uPv01;
        @JSONField(serialize = false)
        public double bPv01;
    }

    static public class FxFwdInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "UNDERLYING_CURRENCY_NOTIONAL")
        public Double underlyingCurrencyNotional;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "BASE_CURRENCY_NOTIONAL")
        public Double baseCurrencyNotional;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;
        @Override
        public String toString() {
            return "FxFwdInfo{" + "productCode='" + productCode + '\'' +
                    ", instrumentId='" + instrumentId + '\'' +
                    ", buyOrSell='" + buyOrSell + '\'' +
                    ", underlyingCurrencyCode='" + underlyingCurrencyCode + '\'' +
                    ", baseCurrencyCode='" + baseCurrencyCode + '\'' +
                    ", underlyingCurrencyNotional=" + underlyingCurrencyNotional +
                    ", baseCurrencyNotional=" + baseCurrencyNotional +
                    ", settleDate=" + settleDate +
                    ", underlyingDiscountCurve='" + underlyingDiscountCurve + '\'' +
                    ", baseDiscountCurve='" + baseDiscountCurve + '\'' +
                    '}';
        }
    }
}

