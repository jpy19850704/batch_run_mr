package com.zcyh.mr.product.ir;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.product.basic.frtb.FrtbDrcInterface;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.ReflectionUtils;
import com.zcyh.mr.calc.FrtbCalcControl;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.product.ir.Bond;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.TradeJsonUtil;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author xujg
 * @date 2024-10-25 08:56
 */
public class BondFuture implements FrtbDrcInterface {

    private final LocalDate dataDate;
    private BondFutureTradeInfo bondFutureInfo; /* 入参交易数据实体 */
    private final MarketData marketData;
    private final Calendar cal;
    private BondFutureMeasure bondFutureMeasure = new BondFutureMeasure(); /* 返回类全局变量 */
    private Result result;
    private HashMap<String, Double> cfMap = new HashMap<>();

    public BondFuture(LocalDate dataDate, BondFutureTradeInfo bondFutureInfo,
            MarketData marketData, Calendar calendar, JSONObject udData) {
        this.dataDate = dataDate;
        this.bondFutureInfo = bondFutureInfo;
        this.marketData = marketData;
        this.cal = calendar;
        validateInputs(udData);
        JSONArray array = new JSONArray();
        for (ConvertFactor item : bondFutureInfo.convertFactors) {
            cfMap.put(item.underlyingBondId, item.convertFactor);
        }
        udData.forEach((k, v) -> {
            if (cfMap.containsKey(k)) {
                JSONObject und = (JSONObject) v;
                array.add(TradeJsonUtil.mergeTrade(und, EngineConstants.PRODUCT_CODE.BOND_FUTURE, "UNDERLYING_DATA"));
            }
        });
        bondFutureInfo.bondInfos = JSON.parseArray(array.toString(), Bond.BondTradeInfo.class);
    }

    /**
     * 场景/FRTB 估值：复用基准阶段的 CTD 选择、Bond SOY 和 netBasis
     * 仅重新计算场景下的模型价格，叠加固定 netBasis 得到场景价格
     */
    public BondFutureMeasure calc(MarketData marketData) {
        if (this.result == null || this.result.undBondInfo == null) {
            boolean minusPriceBondInfo = this.getMinusPriceBondInfo();
            if (!minusPriceBondInfo)
                return buildErrorMeasure("未找到可用于定价的标的债券/转换因子");
        }
        if (isMaturedFuture()) {
            return buildMaturedFutureMeasure(marketData);
        }
        Result result = bfPrice(this.result.undBondInfo, marketData);
        BondFutureMeasure measure = new BondFutureMeasure();
        measure.instrumentId = bondFutureInfo.instrumentId;
        measure.productCode = bondFutureInfo.productCode;
        measure.underlyingBondValue = result.baseValue;
        // 场景价格 = 模型价格 + 固定基差
        double scenarioPrice = result.price + bondFutureMeasure.netBasis;
        measure.position = bondFutureInfo.underlyingPosition;
        measure.valuation = scenarioPrice * measure.position;
        measure.valuationCcy = bondFutureInfo.currencyCode;
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        measure.valuationCny = measure.valuation * getFxRate(marketData);
        measure.dataDate = dataDate;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        return measure;
    }

    public BondFutureMeasure calc() {
        boolean minusPriceBondInfo = this.getMinusPriceBondInfo();
        if (!minusPriceBondInfo)
            return buildErrorMeasure("未找到可用于定价的标的债券/转换因子");

        if (isMaturedFuture()) {
            bondFutureMeasure = buildMaturedFutureMeasure(marketData);
            bondFutureMeasure.underlyingBondId = this.result.undBondInfo.bondId;
            bondFutureMeasure.netBasis = 0.0;
            bondFutureMeasure.pv01 = 0.0;
            bondFutureMeasure.sensitivityList = FrtbCalcControl.isSensitivityEnabled()
                    ? getFrtbSensitivity()
                    : new ArrayList<>();
            bondFutureMeasure.drcDetail = FrtbCalcControl.isDrcEnabled() ? getDrc() : null;
            bondFutureMeasure.cashFlowList = this.result.cashFlowList;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("标的远期价格", bondFutureInfo.futurePrice);
            detail.put("转换因子", this.result.convertFactor);
            detail.put("债券ID", this.result.undBondInfo == null ? null : this.result.undBondInfo.bondId);
            detail.put("期货点差", bondFutureMeasure.netBasis);
            bondFutureMeasure.detail = detail;
            return bondFutureMeasure;
        }

        // 期货基差校准：市场期货价格 - 模型期货价格
        bondFutureMeasure.netBasis = bondFutureInfo.futurePrice - this.result.price;

        // PV01：折现曲线 +1bp
        Set<String> shiftedCurveIds = new LinkedHashSet<>();
        shiftedCurveIds.add(this.result.undBondInfo.discountCurve);
        shiftedCurveIds.add(bondFutureInfo.discountCurve);
        MarketData marketData2 = buildShiftedIrMarketData(marketData, shiftedCurveIds, 0.0001);
        BondFutureMeasure measure2 = calc(marketData2);

        bondFutureMeasure.instrumentId = bondFutureInfo.instrumentId;
        bondFutureMeasure.underlyingBondId = this.result.undBondInfo.bondId;
        bondFutureMeasure.underlyingBondValue = this.result.baseValue;

        // 基准估值使用市场期货价格（模型价 + 基差）
        bondFutureMeasure.position = bondFutureInfo.underlyingPosition;
        bondFutureMeasure.valuation = bondFutureInfo.futurePrice * bondFutureMeasure.position;
        bondFutureMeasure.valuationCcy = bondFutureInfo.currencyCode;
        bondFutureMeasure.valuationUnit = bondFutureMeasure.position == 0.0 ? 0.0
                : bondFutureMeasure.valuation / bondFutureMeasure.position;
        bondFutureMeasure.valuationCny = bondFutureMeasure.valuation * getFxRate(marketData);

        // PV01统一按估值币种口径计算，不按CNY差分
        bondFutureMeasure.pv01 = measure2.valuation - bondFutureMeasure.valuation;
        bondFutureMeasure.logs = new ArrayList<>();
        bondFutureMeasure.sensitivityList = FrtbCalcControl.isSensitivityEnabled()
                ? getFrtbSensitivity()
                : new ArrayList<>();
        bondFutureMeasure.drcDetail = FrtbCalcControl.isDrcEnabled() ? getDrc() : null;
        bondFutureMeasure.productCode = bondFutureInfo.productCode;
        bondFutureMeasure.dataDate = dataDate;
        bondFutureMeasure.cashFlowList = this.result.cashFlowList;
        bondFutureMeasure.status = "SUCCESS";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("标的远期价格", this.result.bondForwardPrice);
        detail.put("转换因子", this.result.convertFactor);
        detail.put("债券ID", this.result.undBondInfo == null ? null : this.result.undBondInfo.bondId);
        detail.put("期货点差", bondFutureMeasure.netBasis);
        bondFutureMeasure.detail = detail;

        return bondFutureMeasure;
    }

    private boolean isMaturedFuture() {
        return bondFutureInfo.maturityDate != null && !bondFutureInfo.maturityDate.isAfter(dataDate);
    }

    private BondFutureMeasure buildMaturedFutureMeasure(MarketData marketData) {
        BondFutureMeasure measure = new BondFutureMeasure();
        measure.instrumentId = bondFutureInfo.instrumentId;
        measure.productCode = bondFutureInfo.productCode;
        measure.underlyingBondId = this.result == null || this.result.undBondInfo == null
                ? null
                : this.result.undBondInfo.bondId;
        measure.underlyingBondValue = this.result == null ? null : this.result.baseValue;
        measure.netBasis = 0.0;
        measure.position = bondFutureInfo.underlyingPosition;
        measure.valuation = bondFutureInfo.futurePrice * measure.position;
        measure.valuationCcy = bondFutureInfo.currencyCode;
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        measure.valuationCny = measure.valuation * getFxRate(marketData);
        measure.dataDate = dataDate;
        measure.pv01 = 0.0;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        measure.sensitivityList = new ArrayList<>();
        return measure;
    }

    private BondFutureMeasure buildErrorMeasure(String message) {
        BondFutureMeasure measure = new BondFutureMeasure();
        measure.instrumentId = bondFutureInfo.instrumentId;
        measure.productCode = bondFutureInfo.productCode;
        measure.dataDate = dataDate;
        measure.position = 0.0;
        measure.valuation = 0.0;
        measure.valuationUnit = 0.0;
        measure.valuationCny = 0.0;
        measure.pv01 = 0.0;
        measure.status = "ERROR";
        measure.logs = new ArrayList<>();
        measure.addErrorLog(message);
        measure.sensitivityList = new ArrayList<>();
        measure.cashFlowList = null;
        measure.detail = null;
        return measure;
    }

    private boolean getMinusPriceBondInfo() {
        /*
         * 遍历入参的信息组成BondInfo类，获取合约价格
         * 把返回的结果全部放入一个集合当中，获取其中最小的价格的那只债
         * 返回与之对应的bond信息，baseValue，bondid等
         */
        LinkedList<Result> list = new LinkedList<>();
        for (Bond.BondTradeInfo info : bondFutureInfo.bondInfos) {
            Result result = bfPrice(info, this.marketData);
            list.add(result);
        }
        if (list.isEmpty())
            return false;
        Double min = Collections.min(list.stream().map(result -> result.price).collect(Collectors.toList()));
        this.result = list.stream().filter(i -> i.price == min)
                .collect(Collectors.toList()).get(0);
        ; /* 计算价格，选中价格最小的保存 */
        return true;
    }

    /**
     * 获取合约价格，返回包含标的债估值、模型期货价格、SOY 等信息
     * 如果 this.result 已存在（基准阶段已完成），复用其 SOY，不重新校准
     */
    private Result bfPrice(Bond.BondTradeInfo bondInfo, MarketData marketData) {
        Bond bond = new Bond(dataDate, bondInfo, marketData, cal);
        // 复用已校准的 SOY（非 null 时 bond.calc() 跳过校准）
        if (this.result != null) {
            bond.setSpreadOverYield(this.result.spreadOverYield);
        }
        Bond.BondMeasure measure = bond.calc();
        double baseValue = measure.valuation;

        List<LocalDate> cfDateList = bond.getScf().getCfDatelist();
        LinkedList<StructuredCashflow.Cashflow> cfList = bond.getCashflowList();
        cfList.removeIf(i -> i.cashType.equalsIgnoreCase("notional"));
        List<LocalDate> dates = cfDateList.subList(1, cfDateList.size());
        dates = dates.stream().filter(date -> date.isAfter(dataDate))
                .collect(Collectors.toList());

        List<StructuredCashflow.Cashflow> cashflowList = cfList.stream().filter(i -> i.paymentDate.isAfter(dataDate))
                .collect(Collectors.toList());
        List<Double> cfall = cashflowList.stream().map(i -> i.cf).collect(Collectors.toList());

        LinkedList<StructuredCashflow.Cashflow> cfq = new LinkedList<>(cfList);
        cfq.removeIf(cf -> cf.paymentDate.isAfter(bondFutureInfo.maturityDate));
        double q = cfq.stream().map(cf -> cf.cf * cf.discoutFactor).reduce(0.0, Double::sum);
        List<LocalDate> qDates = dates.stream().filter(date -> (date.isBefore(bondFutureInfo.maturityDate)
                || date.isEqual(bondFutureInfo.maturityDate))).collect(Collectors.toList());
        double ai = 0.0;
        if (qDates.size() == 0) {
            LocalDate a = cfDateList.get(cfDateList.size() - dates.size() - 1);
            double dt2 = CurveFunc.daysBetweenDCB(a, dates.get(0), bondInfo.dayCountBasis);
            double dt0 = CurveFunc.daysBetweenDCB(a, bondFutureInfo.maturityDate, bondInfo.dayCountBasis);
            ai = cfall.get(0) * dt0 / dt2;
        }

        IrSpot irSpot = new IrSpot(marketData.irSpot.get(bondFutureInfo.discountCurve));
        double df = irSpot.discount(bondFutureInfo.maturityDate);
        double price = (baseValue - q) / df - ai;
        double cf = cfMap.get(bondInfo.bondId);

        Result s = new Result();
        s.spreadOverYield = measure.spreadOverYield;
        s.baseValue = baseValue;
        s.undBondInfo = bondInfo;
        s.cashFlowList = measure.cashFlowList == null ? null : new ArrayList<>(measure.cashFlowList);
        s.bondForwardPrice = price;
        s.convertFactor = cf;
        s.price = price / cf;
        return s;
    }

    /**
     * 仅复制需要冲击的利率曲线，构造局部替换后的市场数据。
     */
    private MarketData buildShiftedIrMarketData(MarketData baseMarketData, Set<String> curveIds, double shift) {
        MarketData shockedMarketData = new MarketData();
        shockedMarketData.irSpot = new HashMap<>(baseMarketData.irSpot);
        shockedMarketData.irVol = new HashMap<>(baseMarketData.irVol);
        shockedMarketData.eqSpot = new HashMap<>(baseMarketData.eqSpot);
        shockedMarketData.eqVol = new HashMap<>(baseMarketData.eqVol);
        shockedMarketData.commSpot = new HashMap<>(baseMarketData.commSpot);
        shockedMarketData.commVol = new HashMap<>(baseMarketData.commVol);
        shockedMarketData.fxVol = new HashMap<>(baseMarketData.fxVol);
        shockedMarketData.fixingRate = new HashMap<>(baseMarketData.fixingRate);
        shockedMarketData.fxSpot = baseMarketData.fxSpot;

        if (curveIds == null || curveIds.isEmpty()) {
            return shockedMarketData;
        }
        for (String curveId : curveIds) {
            if (curveId == null || curveId.trim().isEmpty()) {
                continue;
            }
            if (baseMarketData.irSpot == null) {
                continue;
            }
            IrSpot.IrSpotInfo curveInfo = baseMarketData.irSpot.get(curveId);
            if (curveInfo == null) {
                continue;
            }
            IrSpot.IrSpotInfo shockedCurve = CommUtils.deepCopy(curveInfo);
            shockedCurve.shift(shift);
            shockedMarketData.irSpot.put(curveId, shockedCurve);
        }
        return shockedMarketData;
    }

    /**
     * 获取 FRTB 结果列表
     * @date 2024-10-29 10:53:332
     * @author xujg
     */
    public List<FrtbSenes> getFrtbSensitivity() {
        List<FrtbSenes> list = new ArrayList<>();

        /* GIRR 一般利率风险 */
        HashMap<String, String> map = new HashMap<>();
        map.put(bondFutureInfo.discountCurve, bondFutureInfo.currencyCode);
        map.put(this.result.undBondInfo.discountCurve, this.result.undBondInfo.currencyCode);
        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(map);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                bondFutureInfo.maturityDate,
                girrDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                bondFutureMeasure.instrumentId,
                bondFutureInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(bondFutureMeasure.valuation, bondFutureMeasure.valuationCny),
                shockedMarketData -> {
                    BondFutureMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);
        /* CSR 信用利差风险 */
        if (!hasText(bondFutureInfo.frtbCsrBucket)) {
            bondFutureMeasure.addWarningLog("FRTB_CSR_BUCKET为空，跳过CSR敏感性计算");
        } else if (!hasText(bondFutureInfo.issuer)) {
            bondFutureMeasure.addWarningLog("ISSUER为空，跳过CSR敏感性计算");
        } else if (this.result.undBondInfo.creditSpreadCurve != null) {
            List<FrtbDependency> csrDependencies = bondFutureInfo.absFlag
                    ? FrtbSensitivityBuilder.buildCsrSecNonCtpDeltaDependencies(
                            this.result.undBondInfo.creditSpreadCurve,
                            bondFutureInfo.issuer,
                            bondFutureInfo.frtbCsrBucket,
                            "BOND")
                    : FrtbSensitivityBuilder.buildCsrNonSecDeltaDependencies(
                            this.result.undBondInfo.creditSpreadCurve,
                            bondFutureInfo.issuer,
                            bondFutureInfo.frtbCsrBucket,
                            "BOND");
        List<FrtbSenes> csrSensitivities = FrtbSensitivityBuilder.buildCsrSensitivities(
                    marketData,
                    dataDate,
                    csrDependencies,
                    true,
                    true,
                    bondFutureMeasure.instrumentId,
                    bondFutureInfo.currencyCode,
                    1e-12,
                    com.zcyh.mr.product.basic.frtb.MeasureValuation.of(bondFutureMeasure.valuation, bondFutureMeasure.valuationCny),
                    shockedMarketData -> {
                        BondFutureMeasure shockedMeasure = calc(shockedMarketData);
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                    });
        list.addAll(csrSensitivities);
        }
        list.removeIf(item -> Math.abs(item.sensitivityValInstCurr) < 1e-12
                && Math.abs(item.sensitivityValInstCurrCny) < 1e-12);/* 移除敏度结果为0的元素 */
        return list;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public DrcDetail getDrc() {
        if (!this.result.undBondInfo.isDrcEnabled()) {
            return null;
        }
        /* 取标的债 */
        Param param = ReflectionUtils.bean2Bean(this.result.undBondInfo, Param.class);
        // DRC口径统一按CNY估值金额输入，避免重复换汇
        DrcDetail drcDetail = this.getDrc(param, dataDate, bondFutureMeasure.valuationCny);
        drcDetail.jtdCny = drcDetail.jtd * getFxRate(marketData);
        return drcDetail;
    }

    private double getFxRate(MarketData md) {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);
        return fxSpot.getFxrate(bondFutureInfo.currencyCode);
    }

    @Override
    public double jtd() {
        double units = bondFutureInfo.underlyingPosition;
        double lgd = this.result.undBondInfo.lgd;
        double notional = this.result.undBondInfo.notional;
        double jtd;
        if (result.undBondInfo.absFlag) {
            jtd = bondFutureMeasure.valuation;
        } else {
            jtd = units > 0
                    ? Math.max(lgd * notional * units + bondFutureMeasure.valuation - notional * units, 0)
                    : Math.min(lgd * notional * units + bondFutureMeasure.valuation - notional * units, 0);
        }
        return jtd;
    }

    static class Result {
        /*
         * 因一笔标的债在调用bfPrice时已经创建了对应的bond类，并得出估值
         * 避免出现重复调用估值方法，将的出的结果全部封装在返回类中
         */
        double baseValue;
        double price;
        Bond.BondTradeInfo undBondInfo;
        double spreadOverYield;
        List<BaseCashFlow> cashFlowList;
        double bondForwardPrice;
        double convertFactor;
    }

    private void validateInputs(JSONObject udData) {
        if (bondFutureInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        if (dataDate == null) {
            throw new IllegalArgumentException("数据日期为空");
        }
        if (cal == null) {
            throw new IllegalArgumentException("交易日历为空");
        }
        requireText(bondFutureInfo.productCode, "PRODUCT_CODE");
        requireText(bondFutureInfo.instrumentId, "INSTRUMENT_ID");
        requireCurrencyCode(bondFutureInfo.currencyCode, "CURRENCY_CODE");
        requireFinite(bondFutureInfo.underlyingPosition, "UNDERLYING_POSITION");
        requireText(bondFutureInfo.discountCurve, "DISCOUNT_CURVE");
        requireFinite(bondFutureInfo.futurePrice, "FUTURE_PRICE");
        if (bondFutureInfo.maturityDate == null) {
            throw new IllegalArgumentException("MATURITY_DATE 不能为空");
        }
        if (bondFutureInfo.convertFactors == null || bondFutureInfo.convertFactors.isEmpty()) {
            throw new IllegalArgumentException("CONVERT_FACTORS 不能为空");
        }
        for (ConvertFactor item : bondFutureInfo.convertFactors) {
            if (item == null) {
                throw new IllegalArgumentException("CONVERT_FACTORS 条目不能为空");
            }
            requireText(item.underlyingBondId, "CONVERT_FACTORS.UNDERLYING_BOND_ID");
            if (item.convertFactor == null || !Double.isFinite(item.convertFactor) || item.convertFactor <= 0.0) {
                throw new IllegalArgumentException("CONVERT_FACTORS.CONVERT_FACTOR 必须为正有限数: "
                        + item.convertFactor);
            }
        }
        if (udData == null) {
            throw new IllegalArgumentException("UNDERLYING_DATA 不能为空");
        }
        if (marketData == null) {
            throw new IllegalArgumentException("市场数据为空");
        }
        if (marketData.irSpot == null || marketData.irSpot.get(bondFutureInfo.discountCurve) == null) {
            throw new IllegalArgumentException("折现曲线不存在: " + bondFutureInfo.discountCurve);
        }
        if (marketData.fxSpot == null || marketData.fxSpot.curveData == null
                || marketData.fxSpot.curveData.isEmpty()) {
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

    private static void requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " 必须为有限数: " + value);
        }
    }

    public static class BondFutureTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "UNDERLYING_POSITION")
        public Double underlyingPosition;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "FUTURE_PRICE")
        public Double futurePrice;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "CONVERT_FACTORS")
        public List<ConvertFactor> convertFactors;
        @JSONField(serialize = false, deserialize = false)
        public List<Bond.BondTradeInfo> bondInfos;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "ISSUER")
        public String issuer;
        @JSONField(name = "FRTB_CSR_BUCKET")
        public String frtbCsrBucket;
        @JSONField(name = "ABS_FLAG")
        public boolean absFlag = false;
    }

    public static class ConvertFactor {
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_BOND_ID")
        public String underlyingBondId;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONVERT_FACTOR")
        public Double convertFactor;
    }

    static public class BondFutureMeasure extends Measure {
        @JSONField(name = "UNDERLYING_BOND_ID")
        public String underlyingBondId;
        @JSONField(name = "UNDERLYING_BOND_VALUE", format = "0.########")
        public Double underlyingBondValue;
        @JSONField(name = "NET_BASIS", format = "0.########")
        public Double netBasis;
        @JSONField(name = "DRC", ordinal = 2)
        public DrcDetail drcDetail;
    }
}

