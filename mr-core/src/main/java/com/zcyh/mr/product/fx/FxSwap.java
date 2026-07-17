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
 * 外汇掉期估值类。
 */
public class FxSwap {
    private LocalDate dataDate;
    private FxSwapInfo fxSwapInfo;
    private MarketData marketData;
    private FxSwapMeasure fxSwapMeasure = new FxSwapMeasure();

    public FxSwap(LocalDate dataDate, FxSwapInfo tradeInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.fxSwapInfo = tradeInfo;
        this.marketData = marketData;
    }

    /**
     * 外汇掉期计量。
     */
    public FxSwapMeasure calc() {
        Preconditions.require(dataDate != null, "dataDate must be set");

        FxSwapMeasure result = calc(marketData);
        String uCurrency = fxSwapInfo.underlyingCurrencyCode;
        String bCurrency = fxSwapInfo.baseCurrencyCode;

        LocalDate spotSettleDate = fxSwapInfo.spotSettleDate;
        LocalDate fwdSettleDate = fxSwapInfo.fwdSettleDate;
        IrSpot uIrSpot = new IrSpot(marketData.irSpot.get(fxSwapInfo.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(marketData.irSpot.get(fxSwapInfo.baseDiscountCurve));

        double uRateN = uIrSpot.spotRate(spotSettleDate);
        double uDiscN = uIrSpot.discount(spotSettleDate);
        double bRateN = bIrSpot.spotRate(spotSettleDate);
        double bDiscN = bIrSpot.discount(spotSettleDate);

        double uRateF = uIrSpot.spotRate(fwdSettleDate);
        double uDiscF = uIrSpot.discount(fwdSettleDate);
        double bRateF = bIrSpot.spotRate(fwdSettleDate);
        double bDiscF = bIrSpot.discount(fwdSettleDate);

        fxSwapMeasure.valuation = result.valuation;
        fxSwapMeasure.uPv01 = result.uPv01;
        fxSwapMeasure.bPv01 = result.bPv01;
        fxSwapMeasure.pv01 = result.pv01;
        fxSwapMeasure.valuationCny = result.valuationCny;
        fxSwapMeasure.valuationUnit = result.valuationUnit;
        fxSwapMeasure.valuationCcy = result.valuationCcy;
        fxSwapMeasure.position = result.position;
        fxSwapMeasure.instrumentId = fxSwapInfo.instrumentId;
        fxSwapMeasure.productCode = fxSwapInfo.productCode;
        fxSwapMeasure.dataDate = dataDate;
        fxSwapMeasure.status = "SUCCESS";
        fxSwapMeasure.logs = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("UNDERLYING_PV01", fxSwapMeasure.uPv01);
        detail.put("BASE_PV01", fxSwapMeasure.bPv01);
        fxSwapMeasure.detail = detail;

        HashMap<String, String> curveMap = new HashMap<>();
        curveMap.put(fxSwapInfo.underlyingDiscountCurve, uCurrency);
        curveMap.put(fxSwapInfo.baseDiscountCurve, bCurrency);

        getFrtbSenesList(uCurrency, bCurrency, curveMap);
        getCashFlowList(uRateN, bRateN, uDiscN, bDiscN, uRateF, bRateF, uDiscF, bDiscF);
        return fxSwapMeasure;
    }

    /**
     * 外汇掉期计量。
     */
    public FxSwapMeasure calc(MarketData newMarketData) {
        Preconditions.require(dataDate != null, "dataDate must be set");
        validateInputs(newMarketData);

        LocalDate spotSettleDate = fxSwapInfo.spotSettleDate;
        LocalDate fwdSettleDate = fxSwapInfo.fwdSettleDate;

        IrSpot uIrSpot = new IrSpot(newMarketData.irSpot.get(fxSwapInfo.underlyingDiscountCurve));
        IrSpot bIrSpot = new IrSpot(newMarketData.irSpot.get(fxSwapInfo.baseDiscountCurve));
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), newMarketData.fxSpot);

        String uCurrency = fxSwapInfo.underlyingCurrencyCode;
        String bCurrency = fxSwapInfo.baseCurrencyCode;

        double valueSpot = 0;
        double valueFwd = 0;
        double uPv01Spot = 0;
        double uPv01Fwd = 0;
        double bPv01Spot = 0;
        double bPv01Fwd = 0;

        double uDiscN = uIrSpot.discount(spotSettleDate);
        double bDiscN = bIrSpot.discount(spotSettleDate);
        double uDiscF = uIrSpot.discount(fwdSettleDate);
        double bDiscF = bIrSpot.discount(fwdSettleDate);

        double fxRate = fxSpot.getFxrate(bCurrency, uCurrency);

        if ((int) ChronoUnit.DAYS.between(dataDate, spotSettleDate) > 0) {
            double uNotionalSpot = fxSwapInfo.underlyingCurrencyNotionalSpot;
            double bNotionalSpot = fxSwapInfo.baseCurrencyNotionalSpot;

            valueSpot = (uNotionalSpot * uDiscN * fxRate - bNotionalSpot * bDiscN)
                    * ("S".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);

            double uDisc1Spot = uIrSpot.discount(spotSettleDate, 0.0001);
            uPv01Spot = uNotionalSpot * fxRate * (uDisc1Spot - uDiscN)
                    * ("S".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);

            double bDisc1Spot = bIrSpot.discount(spotSettleDate, 0.0001);
            bPv01Spot = bNotionalSpot * (-bDisc1Spot + bDiscN)
                    * ("S".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);
        }

        if ((int) ChronoUnit.DAYS.between(dataDate, fwdSettleDate) > 0) {
            double uNotionalFwd = fxSwapInfo.underlyingCurrencyNotionalFwd;
            double bNotionalFwd = fxSwapInfo.baseCurrencyNotionalFwd;

            valueFwd = (uNotionalFwd * uDiscF * fxRate - bNotionalFwd * bDiscF)
                    * ("B".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);

            double uDisc1Fwd = uIrSpot.discount(fwdSettleDate, 0.0001);
            uPv01Fwd = uNotionalFwd * fxRate * (uDisc1Fwd - uDiscF)
                    * ("B".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);

            double bDisc1Fwd = bIrSpot.discount(fwdSettleDate, 0.0001);
            bPv01Fwd = bNotionalFwd * (-bDisc1Fwd + bDiscF)
                    * ("B".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);
        }
        double cnyFxrate = 1.0;
        if (!"CNY".equals(bCurrency)) {
            cnyFxrate = fxSpot.getFxrate(bCurrency);
        }

        double value = valueSpot + valueFwd;

        FxSwapMeasure result = new FxSwapMeasure();
        result.valuation = value;
        result.valuationCny = value * cnyFxrate;
        result.uPv01 = uPv01Spot + uPv01Fwd;
        result.bPv01 = bPv01Spot + bPv01Fwd;
        result.pv01 = (uPv01Spot + uPv01Fwd + bPv01Spot + bPv01Fwd) * cnyFxrate;
        result.valuationCcy = bCurrency;
        result.position = fxSwapInfo.underlyingCurrencyNotionalFwd
                * ("B".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1);
        result.valuationUnit = result.position == 0.0 ? 0.0 : result.valuation / result.position;
        result.instrumentId = fxSwapInfo.instrumentId;
        result.productCode = fxSwapInfo.productCode;
        result.dataDate = dataDate;
        result.status = "SUCCESS";
        result.logs = new ArrayList<>();
        return result;
    }

    private void getFrtbSenesList(String uCurrency, String bCurrency, HashMap<String, String> curveMap) {
        List<FrtbSenes> list = new ArrayList<>();

        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                fxSwapInfo.fwdSettleDate,
                collectFxDeltaDependencies(uCurrency, bCurrency),
                new ArrayList<>(),
                true,
                false,
                fxSwapInfo.instrumentId,
                fxSwapInfo.baseCurrencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(fxSwapMeasure.valuation, fxSwapMeasure.valuationCny),
                shockedMarketData -> {
                    FxSwapMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                });
        list.addAll(fxSensitivities);

        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                fxSwapInfo.fwdSettleDate,
                FrtbSensitivityBuilder.buildGirrDeltaDependencies(curveMap),
                new ArrayList<>(),
                true,
                false,
                fxSwapInfo.instrumentId,
                fxSwapInfo.baseCurrencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(fxSwapMeasure.valuation, fxSwapMeasure.valuationCny),
                shockedMarketData -> {
                    FxSwapMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);
        fxSwapMeasure.sensitivityList = list;
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
        if (fxSwapInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        requireText(fxSwapInfo.productCode, "PRODUCT_CODE");
        requireText(fxSwapInfo.instrumentId, "INSTRUMENT_ID");
        if (!"B".equalsIgnoreCase(fxSwapInfo.buyOrSell)
                && !"S".equalsIgnoreCase(fxSwapInfo.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + fxSwapInfo.buyOrSell);
        }
        requireCurrencyCode(fxSwapInfo.underlyingCurrencyCode, "UNDERLYING_CURRENCY_CODE");
        requireCurrencyCode(fxSwapInfo.baseCurrencyCode, "BASE_CURRENCY_CODE");
        requireNonNegativeFinite(fxSwapInfo.underlyingCurrencyNotionalSpot,
                "UNDERLYING_CURRENCY_NOTIONAL_SPOT");
        requireNonNegativeFinite(fxSwapInfo.baseCurrencyNotionalSpot, "BASE_CURRENCY_NOTIONAL_SPOT");
        requireNonNegativeFinite(fxSwapInfo.underlyingCurrencyNotionalFwd,
                "UNDERLYING_CURRENCY_NOTIONAL_FWD");
        requireNonNegativeFinite(fxSwapInfo.baseCurrencyNotionalFwd, "BASE_CURRENCY_NOTIONAL_FWD");
        if (fxSwapInfo.spotSettleDate == null) {
            throw new IllegalArgumentException("SPOT_SETTLE_DATE 不能为空");
        }
        if (fxSwapInfo.fwdSettleDate == null) {
            throw new IllegalArgumentException("FWD_SETTLE_DATE 不能为空");
        }
        requireText(fxSwapInfo.underlyingDiscountCurve, "UNDERLYING_DISCOUNT_CURVE");
        requireText(fxSwapInfo.baseDiscountCurve, "BASE_DISCOUNT_CURVE");
        validateMarketData(md, fxSwapInfo.underlyingDiscountCurve, fxSwapInfo.baseDiscountCurve);
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

    private void getCashFlowList(double uRateN, double bRateN, double uDiscN, double bDiscN,
            double uRateF, double bRateF, double uDiscF, double bDiscF) {
        LocalDate spotSettleDate = fxSwapInfo.spotSettleDate;
        LocalDate fwdSettleDate = fxSwapInfo.fwdSettleDate;
        int spotDays = (int) ChronoUnit.DAYS.between(dataDate, spotSettleDate);
        int fwdDays = (int) ChronoUnit.DAYS.between(dataDate, fwdSettleDate);
        int buySign = "B".equalsIgnoreCase(fxSwapInfo.buyOrSell) ? 1 : -1;
        List<BaseCashFlow> cfList = new ArrayList<>();

        if (spotDays > 0) {
            BaseCashFlow spotU = new BaseCashFlow();
            spotU.dataDate = dataDate;
            spotU.paymentDate = spotSettleDate;
            spotU.currencyCode = fxSwapInfo.underlyingCurrencyCode;
            spotU.cashFlowType = "PRINCIPAL";
            spotU.cashflow = fxSwapInfo.underlyingCurrencyNotionalSpot * (-buySign);
            spotU.discountRate = uRateN;
            spotU.discountFactor = uDiscN;
            cfList.add(spotU);

            BaseCashFlow spotB = new BaseCashFlow();
            spotB.dataDate = dataDate;
            spotB.paymentDate = spotSettleDate;
            spotB.currencyCode = fxSwapInfo.baseCurrencyCode;
            spotB.cashFlowType = "PRINCIPAL";
            spotB.cashflow = fxSwapInfo.baseCurrencyNotionalSpot * buySign;
            spotB.discountRate = bRateN;
            spotB.discountFactor = bDiscN;
            cfList.add(spotB);
        }

        if (fwdDays > 0) {
            BaseCashFlow fwdU = new BaseCashFlow();
            fwdU.dataDate = dataDate;
            fwdU.paymentDate = fwdSettleDate;
            fwdU.currencyCode = fxSwapInfo.underlyingCurrencyCode;
            fwdU.cashFlowType = "PRINCIPAL";
            fwdU.cashflow = fxSwapInfo.underlyingCurrencyNotionalFwd * buySign;
            fwdU.discountRate = uRateF;
            fwdU.discountFactor = uDiscF;
            cfList.add(fwdU);

            BaseCashFlow fwdB = new BaseCashFlow();
            fwdB.dataDate = dataDate;
            fwdB.paymentDate = fwdSettleDate;
            fwdB.currencyCode = fxSwapInfo.baseCurrencyCode;
            fwdB.cashFlowType = "PRINCIPAL";
            fwdB.cashflow = fxSwapInfo.baseCurrencyNotionalFwd * (-buySign);
            fwdB.discountRate = bRateF;
            fwdB.discountFactor = bDiscF;
            cfList.add(fwdB);
        }

        fxSwapMeasure.cashFlowList = cfList;
    }

    static public class FxSwapMeasure extends Measure {
        @JSONField(serialize = false)
        public double uPv01;
        @JSONField(serialize = false)
        public double bPv01;
    }

    static public class FxSwapInfo {
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
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "UNDERLYING_CURRENCY_NOTIONAL_SPOT")
        public Double underlyingCurrencyNotionalSpot;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "UNDERLYING_CURRENCY_NOTIONAL_FWD")
        public Double underlyingCurrencyNotionalFwd;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String baseCurrencyCode;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "BASE_CURRENCY_NOTIONAL_SPOT")
        public Double baseCurrencyNotionalSpot;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "BASE_CURRENCY_NOTIONAL_FWD")
        public Double baseCurrencyNotionalFwd;
        @ProductInputField(required = true)
        @JSONField(name = "SPOT_SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate spotSettleDate;
        @ProductInputField(required = true)
        @JSONField(name = "FWD_SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate fwdSettleDate;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_DISCOUNT_CURVE")
        public String underlyingDiscountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "BASE_DISCOUNT_CURVE")
        public String baseDiscountCurve;

        @Override
        public String toString() {
            return "FxSwapInfo{" + "productCode='" + productCode + '\'' +
                    ", instrumentId='" + instrumentId + '\'' +
                    ", buyOrSell='" + buyOrSell + '\'' +
                    ", underlyingCurrencyCode='" + underlyingCurrencyCode + '\'' +
                    ", underlyingCurrencyNotionalSpot=" + underlyingCurrencyNotionalSpot +
                    ", underlyingCurrencyNotionalFwd=" + underlyingCurrencyNotionalFwd +
                    ", baseCurrencyCode='" + baseCurrencyCode + '\'' +
                    ", baseCurrencyNotionalSpot=" + baseCurrencyNotionalSpot +
                    ", baseCurrencyNotionalFwd=" + baseCurrencyNotionalFwd +
                    ", spotSettleDate=" + spotSettleDate +
                    ", fwdSettleDate=" + fwdSettleDate +
                    ", underlyingDiscountCurve='" + underlyingDiscountCurve + '\'' +
                    ", baseDiscountCurve='" + baseDiscountCurve + '\'' +
                    '}';
        }
    }
}

