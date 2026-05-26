package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author xujg
 * @date 2024-08-20 09:14
 */
public class IrsCcs {

    LinkedList<StructuredCashflow.Cashflow> cashflowList;
    private LocalDate dataDate;
    private IrsCcs.IrsCcsInfo irsCcsInfo;
    private MarketData marketData;
    private Calendar calendar;
    private IrsCcsMeasure irsCcsMeasure = new IrsCcsMeasure();

    public IrsCcsMeasure calc() {
        // 基准估值直接使用传入市场，避免复制整份市场数据
        MarketData baseMarketData = marketData;
        CalcSnapshot base = calcSnapshot(baseMarketData);
        irsCcsMeasure = base.measure;
        getFrtbSensList(base, baseMarketData); // 调用函数生成frtb部分

        // 折现曲线变动1bp后的估值
        /* 四条曲线变动，避免重复变动，放入集合 */
        HashSet<String> set = new HashSet<>();
        set.add(irsCcsInfo.payDiscountCurve);
        set.add(irsCcsInfo.recDiscountCurve);
        set.add(irsCcsInfo.payReferenceCurve);
        set.add(irsCcsInfo.recReferenceCurve);
        set.removeIf(StringUtils::isBlank); /* 移除空串 */
        MarketData newMarketDate = buildShiftedIrMarketData(baseMarketData, set, 0.0001);
        CalcSnapshot shocked = calcSnapshot(newMarketDate);
        double payPv01 = shocked.payValue - base.payValue;
        double recPv01 = shocked.recValue - base.recValue;

        // 总PV01统一按REC币种汇总：REC腿PV01 + PAY腿PV01折算到REC币种
        irsCcsMeasure.pv01 = recPv01 + payPv01 * base.spotPay / base.spotRec;
        irsCcsMeasure.productCode = irsCcsInfo.productCode;
        irsCcsMeasure.dataDate = dataDate;
        irsCcsMeasure.status = "SUCCESS";
        irsCcsMeasure.logs = new ArrayList<>();

        if (irsCcsInfo.swapType.equalsIgnoreCase("ccs")) {
            // CCS Basis 已统一收口到 GIRR builder，在敏感性列表生成阶段统一追加。
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("PAY_VALUATION", base.payValue);
        detail.put("PAY_PV01", payPv01);
        detail.put("REC_VALUATION", base.recValue);
        detail.put("REC_PV01", recPv01);
        irsCcsMeasure.detail = detail;
        return irsCcsMeasure;
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
            com.zcyh.mr.marketdata.IrSpot.IrSpotInfo curveInfo = baseMarketData.irSpot.get(curveId);
            if (curveInfo == null) {
                continue;
            }
            com.zcyh.mr.marketdata.IrSpot.IrSpotInfo shockedCurve = CommUtils.deepCopy(curveInfo);
            shockedCurve.shift(shift);
            shockedMarketData.irSpot.put(curveId, shockedCurve);
        }
        return shockedMarketData;
    }

    public IrsCcsMeasure calc(MarketData marketData) {
        return calcSnapshot(marketData).measure;
    }

    private CalcSnapshot calcSnapshot(MarketData marketData) {
        StructuredCashflow.ScfInfo scfInfo = new StructuredCashflow.ScfInfo();
        scfInfo.issueDate = irsCcsInfo.startDate;
        scfInfo.maturityDate = irsCcsInfo.maturityDate;
        scfInfo.couponProrated = true;
        scfInfo.interestStub = "longEnd";
        scfInfo.notionalFlag = irsCcsInfo.notionalExchangeType;

        double valuePay = 0, valueRec = 0;
        // 支付端
        scfInfo.interestType = irsCcsInfo.payInterestType;
        scfInfo.discountCurve = irsCcsInfo.payDiscountCurve;
        scfInfo.currencyCode = irsCcsInfo.payCurrencyCode;
        scfInfo.payFreq = irsCcsInfo.payFreq;
        scfInfo.notional = irsCcsInfo.payNotional;
        scfInfo.dayCountBasis = irsCcsInfo.payDayCountBasis;
        scfInfo.settleCalendar = irsCcsInfo.paySettleCalendar;
        scfInfo.settleDayoff = irsCcsInfo.paySettleDayoff;
        scfInfo.settleRule = irsCcsInfo.paySettleRule;
        scfInfo.interestRate = irsCcsInfo.payInterest;
        scfInfo.spread = irsCcsInfo.paySpread;
        scfInfo.fixingId = irsCcsInfo.payFixingId;
        scfInfo.referenceCurve = irsCcsInfo.payReferenceCurve;
        scfInfo.fixingCalendar = irsCcsInfo.payFixingCalendar;
        scfInfo.lastResetRate = irsCcsInfo.payLastFixingRate;

        if ("fixed".equalsIgnoreCase(irsCcsInfo.payInterestType)) {

        } else if ("floating".equalsIgnoreCase(irsCcsInfo.payInterestType)) {
            scfInfo.resetDayoff = irsCcsInfo.payFixingDayoff;
            scfInfo.resetRule = irsCcsInfo.payFixingRule;
            scfInfo.resetFreq = irsCcsInfo.payResetFreq;
            scfInfo.fixingFreq = irsCcsInfo.payFixingFreq;
        }
        StructuredCashflow legLeft = new StructuredCashflow(dataDate, scfInfo, marketData, calendar);
        legLeft.calc();
        LinkedList<StructuredCashflow.Cashflow> cashflowList1 = legLeft.getCashflowList();
        valuePay = cashflowList1.stream()
                .map(i -> i.discoutFactor * i.cf)
                .reduce(0.0, Double::sum);

        // 接收端
        StructuredCashflow.ScfInfo recScf = new StructuredCashflow.ScfInfo();
        recScf.issueDate = irsCcsInfo.startDate;
        recScf.maturityDate = irsCcsInfo.maturityDate;
        recScf.couponProrated = true;
        recScf.interestStub = "longEnd";
        recScf.notionalFlag = irsCcsInfo.notionalExchangeType;
        recScf.interestType = irsCcsInfo.recInterestType;
        recScf.discountCurve = irsCcsInfo.recDiscountCurve;
        recScf.currencyCode = irsCcsInfo.recCurrencyCode;
        recScf.payFreq = irsCcsInfo.recFreq;
        recScf.notional = irsCcsInfo.recNotional;
        recScf.dayCountBasis = irsCcsInfo.recDayCountBasis;
        recScf.settleCalendar = irsCcsInfo.recSettleCalendar;
        recScf.settleDayoff = irsCcsInfo.recSettleDayoff;
        recScf.settleRule = irsCcsInfo.recSettleRule;
        recScf.interestRate = irsCcsInfo.recInterest;
        recScf.spread = irsCcsInfo.recSpread;
        recScf.fixingId = irsCcsInfo.recFixingId;
        recScf.referenceCurve = irsCcsInfo.recReferenceCurve;
        recScf.fixingCalendar = irsCcsInfo.recFixingCalendar;
        recScf.lastResetRate = irsCcsInfo.recLastFixingRate;

        if ("fixed".equalsIgnoreCase(irsCcsInfo.recInterestType)) {

        } else if ("floating".equalsIgnoreCase(irsCcsInfo.recInterestType)) {
            recScf.resetDayoff = irsCcsInfo.recFixingDayoff;
            recScf.resetRule = irsCcsInfo.recFixingRule;
            recScf.resetFreq = irsCcsInfo.recResetFreq;
            recScf.fixingFreq = irsCcsInfo.recFixingFreq;
        }
        StructuredCashflow legRight = new StructuredCashflow(dataDate, recScf, marketData, calendar);
        legRight.calc();
        LinkedList<StructuredCashflow.Cashflow> cashflowList2 = legRight.getCashflowList();
        valueRec = cashflowList2.stream()
                .map(i -> i.discoutFactor * i.cf)
                .reduce(0.0, Double::sum);

        IrsCcsMeasure measure = new IrsCcsMeasure();
        CalcSnapshot snapshot = new CalcSnapshot();
        snapshot.recValue = valueRec;
        int units = -1; // pay端-1，rec端1省略
        snapshot.payValue = valuePay * units;
        FxSpot spot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        snapshot.spotPay = spot.getFxrate(irsCcsInfo.payCurrencyCode);
        snapshot.spotRec = spot.getFxrate(irsCcsInfo.recCurrencyCode);
        measure.instrumentId = irsCcsInfo.instrumentId;
        measure.position = 1.0;
        measure.valuationCcy = irsCcsInfo.recCurrencyCode;
        measure.valuationCny = valueRec * snapshot.spotRec - valuePay * snapshot.spotPay;
        /* valuation统一按REC币种 */
        measure.valuation = valueRec - valuePay * snapshot.spotPay / snapshot.spotRec;
        measure.valuationUnit = measure.valuation;
        measure.cashFlowList = new ArrayList<>();
        // 输出口径：先 REC 后 PAY；同时保证 PAY 为负、REC 为正
        measure.cashFlowList.addAll(buildOutputCashFlowList(cashflowList2, irsCcsInfo.recCurrencyCode, 1.0));
        measure.cashFlowList.addAll(buildOutputCashFlowList(cashflowList1, irsCcsInfo.payCurrencyCode, -1.0));
        snapshot.measure = measure;
        return snapshot;
    }

    /**
     * 将内部现金流对象转换为统一输出现金流对象，并补充币种信息。
     */
    private List<BaseCashFlow> buildOutputCashFlowList(List<StructuredCashflow.Cashflow> src, String currencyCode,
            double sign) {
        List<BaseCashFlow> res = new ArrayList<>();
        if (src == null) {
            return res;
        }
        for (StructuredCashflow.Cashflow cf : src) {
            ScfCashFlow out = new ScfCashFlow();
            out.dataDate = dataDate;
            out.currencyCode = currencyCode;
            out.paymentDate = cf.paymentDate;
            out.cashFlowType = cf.cashType;
            out.cashflow = cf.cf * sign;
            out.discountFactor = cf.discoutFactor;
            out.rate = cf.rate;
            out.startNotional = cf.startNotional;
            out.endNotional = cf.endNotional;
            out.fwdStartDat = cf.fwdStartDate;
            out.fwdEndDate = cf.fwdEndDate;
            out.theoPaymentDate = cf.theoPaymentDate;
            out.prepaymentDate = cf.prePaymentDate;
            res.add(out);
        }
        return res;
    }

    public void getFrtbSensList(CalcSnapshot base, MarketData baseMarketData) {
        List<FrtbSenes> list = new ArrayList<>();
        String instrumentCurrency = base.measure.valuationCcy;
        if (StringUtils.isBlank(instrumentCurrency)) {
            throw new IllegalArgumentException("IRS/CCS估值币种为空: instrumentId=" + irsCcsInfo.instrumentId);
        }
        if (!StringUtils.equalsIgnoreCase(instrumentCurrency, irsCcsInfo.recCurrencyCode)) {
            throw new IllegalArgumentException("IRS/CCS估值币种必须等于收款币种: instrumentId=" + irsCcsInfo.instrumentId);
        }
        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                Arrays.asList(irsCcsInfo.payCurrencyCode, irsCcsInfo.recCurrencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                baseMarketData,
                dataDate,
                irsCcsInfo.maturityDate,
                fxDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                irsCcsInfo.instrumentId,
                instrumentCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(base.measure.valuation, base.measure.valuationCny),
                shockedMarketData -> {
                    IrsCcsMeasure shockedMeasure = calcSnapshot(shockedMarketData).measure;
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(
                            shockedMeasure.valuation,
                            shockedMeasure.valuationCny);
                });
        list.addAll(fxSensitivities);

        HashMap<String, String> girrCurveBucketMap = new HashMap<>();
        putGirrCurveDependency(girrCurveBucketMap, irsCcsInfo.payDiscountCurve, irsCcsInfo.payCurrencyCode,
                "PAY_DISCOUNT_CURVE");
        if ("floating".equalsIgnoreCase(irsCcsInfo.payInterestType)) {
            putGirrCurveDependency(girrCurveBucketMap, irsCcsInfo.payReferenceCurve, irsCcsInfo.payCurrencyCode,
                    "PAY_REFERENCE_CURVE");
        }
        putGirrCurveDependency(girrCurveBucketMap, irsCcsInfo.recDiscountCurve, irsCcsInfo.recCurrencyCode,
                "REC_DISCOUNT_CURVE");
        if ("floating".equalsIgnoreCase(irsCcsInfo.recInterestType)) {
            putGirrCurveDependency(girrCurveBucketMap, irsCcsInfo.recReferenceCurve, irsCcsInfo.recCurrencyCode,
                    "REC_REFERENCE_CURVE");
        }
        List<FrtbDependency> girrDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(girrCurveBucketMap);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                baseMarketData,
                dataDate,
                irsCcsInfo.maturityDate,
                girrDependencies,
                Collections.emptyList(),
                true,
                false,
                irsCcsMeasure.instrumentId,
                instrumentCurrency,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(base.measure.valuation, base.measure.valuationCny),
                shockedMarketData -> {
                    IrsCcsMeasure shockedMeasure = calcSnapshot(shockedMarketData).measure;
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(
                            shockedMeasure.valuation,
                            shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        if ("ccs".equalsIgnoreCase(irsCcsInfo.swapType)) {
            List<FrtbDependency> girrBasisDependencies = FrtbSensitivityBuilder.buildGirrDeltaBasisDependencies(
                    irsCcsInfo.payCurrencyCode,
                    resolveBasisCurve(irsCcsInfo.payInterestType, irsCcsInfo.payReferenceCurve, irsCcsInfo.payDiscountCurve),
                    irsCcsInfo.recCurrencyCode,
                    resolveBasisCurve(irsCcsInfo.recInterestType, irsCcsInfo.recReferenceCurve, irsCcsInfo.recDiscountCurve));
        List<FrtbSenes> girrBasisSensitivities = FrtbSensitivityBuilder.buildGirrDeltaBasisSensitivities(
                    baseMarketData,
                    dataDate,
                    girrBasisDependencies,
                    irsCcsInfo.instrumentId,
                    instrumentCurrency,
                    1e-12,
                    com.zcyh.mr.product.basic.frtb.MeasureValuation.of(base.measure.valuation, base.measure.valuationCny),
                    shockedMarketData -> {
                        IrsCcsMeasure shockedMeasure = calcSnapshot(shockedMarketData).measure;
                        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(
                                shockedMeasure.valuation,
                                shockedMeasure.valuationCny);
                    });
            list.addAll(girrBasisSensitivities);
        }

        list.removeIf(i -> Math.abs(i.sensitivityValInstCurr) < 1e-12
                && Math.abs(i.sensitivityValInstCurrCny) < 1e-12);
        List<FrtbSenes> newList = new ArrayList<>();
        list.stream().collect(Collectors.groupingBy(
                i -> (i.riskFactorClass + "@" + i.riskFactorType + "@" + i.riskFactorId + "@" + i.riskFactorBucket + "@"
                        + i.riskFactorVertex1
                        + "@" + i.instrumentId + "@" + i.instrumentCurrency),
                Collectors.toList())).forEach((id, transfer) -> {
                    transfer.stream().reduce(
                            (a, b) -> {
                                a.sensitivityValInstCurr = a.sensitivityValInstCurr + b.sensitivityValInstCurr;
                                a.sensitivityValInstCurrCny = a.sensitivityValInstCurrCny + b.sensitivityValInstCurrCny;
                                return a;
                            }).ifPresent(newList::add);
                });
        irsCcsMeasure.sensitivityList = newList;
    }

    private void putGirrCurveDependency(HashMap<String, String> curveBucketMap, String curve, String currency,
            String fieldName) {
        String curveId = StringUtils.trimToNull(curve);
        if (curveId == null) {
            return;
        }
        String bucket = StringUtils.trimToNull(currency);
        if (bucket == null) {
            throw new IllegalArgumentException("IRS/CCS GIRR曲线币种为空: instrumentId=" + irsCcsInfo.instrumentId
                    + ", field=" + fieldName + ", curve=" + curveId);
        }
        String existingBucket = curveBucketMap.get(curveId);
        if (existingBucket != null && !StringUtils.equalsIgnoreCase(existingBucket, bucket)) {
            throw new IllegalArgumentException("IRS/CCS 同一GIRR曲线对应多个币种: instrumentId="
                    + irsCcsInfo.instrumentId + ", curve=" + curveId + ", firstCurrency=" + existingBucket
                    + ", currentCurrency=" + bucket + ", currentField=" + fieldName);
        }
        curveBucketMap.put(curveId, bucket);
    }

    /**
     * CCS Basis 统一选择每条腿最能代表该币种利率风险的曲线：
     * 浮动腿优先使用 referenceCurve，否则退回 discountCurve。
     */
    private String resolveBasisCurve(String interestType, String referenceCurve, String discountCurve) {
        if ("floating".equalsIgnoreCase(interestType) && StringUtils.isNotBlank(referenceCurve)) {
            return referenceCurve.trim();
        }
        if (StringUtils.isNotBlank(discountCurve)) {
            return discountCurve.trim();
        }
        return StringUtils.isBlank(referenceCurve) ? null : referenceCurve.trim();
    }

    /**
     * 计算两端敏感度后汇总
     * @date 2024-11-20 13:45:404
     * @author xujg
     */

    public IrsCcs(LocalDate dataDate, IrsCcs.IrsCcsInfo tradeInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.irsCcsInfo = tradeInfo;
        this.marketData = marketData;
        this.calendar = calendar;
        this.cashflowList = new LinkedList<>();
    }

    public LinkedList<StructuredCashflow.Cashflow> getCashflowList() {
        return cashflowList;
    }

    public static class IrsCcsInfo {
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "SWAP_TYPE")
        public String swapType;
        @JSONField(name = "START_DATE")
        public LocalDate startDate;
        @JSONField(name = "MATURITY_DATE")
        public LocalDate maturityDate;
        @JSONField(name = "NOTIONAL_EXCHANGE_TYPE")
        public String notionalExchangeType;
        @JSONField(name = "PAY_NOTIONAL")
        public Double payNotional;
        @JSONField(name = "PAY_CURRENCY_CODE")
        public String payCurrencyCode;
        @JSONField(name = "PAY_INTEREST_TYPE")
        public String payInterestType;
        @JSONField(name = "PAY_INTEREST")
        public Double payInterest;
        @JSONField(name = "PAY_REFERENCE_CURVE")
        public String payReferenceCurve;
        @JSONField(name = "PAY_FIXING_ID")
        public String payFixingId;
        @JSONField(name = "PAY_FIXING_CALENDAR")
        public String payFixingCalendar;
        @JSONField(name = "PAY_SPREAD")
        public Double paySpread;
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @JSONField(name = "PAY_DAY_COUNT_BASIS")
        public String payDayCountBasis;
        @JSONField(name = "PAY_SETTLE_CALENDAR")
        public String paySettleCalendar;
        @JSONField(name = "PAY_SETTLE_RULE")
        public String paySettleRule;
        @JSONField(name = "PAY_SETTLE_DAYOFF")
        public Integer paySettleDayoff;
        @JSONField(name = "PAY_FIXING_RULE")
        public String payFixingRule;
        @JSONField(name = "PAY_FIXING_DAYOFF")
        public Integer payFixingDayoff;
        @JSONField(name = "PAY_LAST_FIXING_RATE")
        public Double payLastFixingRate;
        @JSONField(name = "PAY_DISCOUNT_CURVE")
        public String payDiscountCurve;
        @JSONField(name = "PAY_RESET_FREQ")
        public String payResetFreq;
        @JSONField(name = "PAY_FIXING_FREQ")
        public String payFixingFreq;
        @JSONField(name = "REC_NOTIONAL")
        public Double recNotional;
        @JSONField(name = "REC_CURRENCY_CODE")
        public String recCurrencyCode;
        @JSONField(name = "REC_INTEREST_TYPE")
        public String recInterestType;
        @JSONField(name = "REC_INTEREST")
        public Double recInterest;
        @JSONField(name = "REC_REFERENCE_CURVE")
        public String recReferenceCurve;
        @JSONField(name = "REC_FIXING_ID")
        public String recFixingId;
        @JSONField(name = "REC_FIXING_CALENDAR")
        public String recFixingCalendar;
        @JSONField(name = "REC_SPREAD")
        public Double recSpread;
        @JSONField(name = "REC_FREQ")
        public String recFreq;
        @JSONField(name = "REC_RESET_FREQ")
        public String recResetFreq;
        @JSONField(name = "REC_FIXING_FREQ")
        public String recFixingFreq;
        @JSONField(name = "REC_DAY_COUNT_BASIS")
        public String recDayCountBasis;
        @JSONField(name = "REC_SETTLE_CALENDAR")
        public String recSettleCalendar;
        @JSONField(name = "REC_SETTLE_RULE")
        public String recSettleRule;
        @JSONField(name = "REC_SETTLE_DAYOFF")
        public Integer recSettleDayoff;
        @JSONField(name = "REC_FIXING_RULE")
        public String recFixingRule;
        @JSONField(name = "REC_FIXING_DAYOFF")
        public Integer recFixingDayoff;
        @JSONField(name = "REC_LAST_FIXING_RATE")
        public Double recLastFixingRate;
        @JSONField(name = "REC_DISCOUNT_CURVE")
        public String recDiscountCurve;
        @JSONField(name = "DATA_DATE")
        public LocalDate dataDate;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
    }

    public static class IrsCcsMeasure extends Measure {
    }

    private static class CalcSnapshot {
        public IrsCcsMeasure measure;
        public double payValue;
        public double recValue;
        public double spotPay;
        public double spotRec;
    }

}

