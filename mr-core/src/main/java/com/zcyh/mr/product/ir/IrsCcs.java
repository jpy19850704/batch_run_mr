package com.zcyh.mr.product.ir;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IRS/CCS 估值与敏感性计算。
 */
public class IrsCcs {

    private LocalDate dataDate;
    private IrsCcs.IrsCcsTradeInfo irsCcsInfo;
    private MarketData marketData;
    private Calendar calendar;

    public IrsCcsMeasure calc() {
        MarketData baseMarketData = marketData;
        CalcSnapshot base = calcSnapshot(baseMarketData);
        IrsCcsMeasure measure = base.measure;
        measure.sensitivityList = buildFrtbSensList(base, baseMarketData);

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
        measure.pv01 = recPv01 + payPv01 * base.spotPay / base.spotRec;
        measure.productCode = irsCcsInfo.productCode;
        measure.dataDate = dataDate;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        appendWarnings(measure, base.warnings);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("PAY_VALUATION", base.payValue);
        detail.put("PAY_PV01", payPv01);
        detail.put("REC_VALUATION", base.recValue);
        detail.put("REC_PV01", recPv01);
        measure.detail = detail;
        return measure;
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
        validateInputs(marketData);
        List<String> warnings = new ArrayList<>();
        StructuredCashflow.ScfInfo scfInfo = createBaseScfInfo();

        // 支付腿
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
        scfInfo.interestAggregationMethod = irsCcsInfo.payInterestAggregationMethod;
        scfInfo.fixingId = irsCcsInfo.payFixingId;
        scfInfo.referenceCurve = irsCcsInfo.payReferenceCurve;
        scfInfo.fixingCalendar = irsCcsInfo.payFixingCalendar;
        scfInfo.allowMissingReferenceCurveAsZeroForward = false;
        configureFloatingLeg(scfInfo, irsCcsInfo.payInterestType, irsCcsInfo.payFixingDayoff,
                irsCcsInfo.payFixingRule, irsCcsInfo.payResetFreq, irsCcsInfo.payFixingFreq);
        LegCalculation payLeg = calculateLeg(scfInfo, marketData, "PAY", warnings);

        // 接收腿
        StructuredCashflow.ScfInfo recScf = createBaseScfInfo();
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
        recScf.interestAggregationMethod = irsCcsInfo.recInterestAggregationMethod;
        recScf.fixingId = irsCcsInfo.recFixingId;
        recScf.referenceCurve = irsCcsInfo.recReferenceCurve;
        recScf.fixingCalendar = irsCcsInfo.recFixingCalendar;
        recScf.allowMissingReferenceCurveAsZeroForward = false;
        configureFloatingLeg(recScf, irsCcsInfo.recInterestType, irsCcsInfo.recFixingDayoff,
                irsCcsInfo.recFixingRule, irsCcsInfo.recResetFreq, irsCcsInfo.recFixingFreq);
        LegCalculation recLeg = calculateLeg(recScf, marketData, "REC", warnings);

        IrsCcsMeasure measure = new IrsCcsMeasure();
        CalcSnapshot snapshot = new CalcSnapshot();
        snapshot.recValue = recLeg.value;
        snapshot.payValue = -payLeg.value;
        FxSpot spot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        snapshot.spotPay = spot.getFxrate(irsCcsInfo.payCurrencyCode);
        snapshot.spotRec = spot.getFxrate(irsCcsInfo.recCurrencyCode);
        measure.instrumentId = irsCcsInfo.instrumentId;
        measure.position = 1.0;
        measure.valuationCcy = irsCcsInfo.recCurrencyCode;
        measure.valuationCny = recLeg.value * snapshot.spotRec - payLeg.value * snapshot.spotPay;
        /* valuation统一按REC币种 */
        measure.valuation = recLeg.value - payLeg.value * snapshot.spotPay / snapshot.spotRec;
        measure.valuationUnit = measure.valuation;
        measure.cashFlowList = new ArrayList<>();
        // 输出口径：先 REC 后 PAY；同时保证 PAY 为负、REC 为正
        measure.cashFlowList.addAll(buildOutputCashFlowList(recLeg.cashflows, irsCcsInfo.recCurrencyCode, 1.0));
        measure.cashFlowList.addAll(buildOutputCashFlowList(payLeg.cashflows, irsCcsInfo.payCurrencyCode, -1.0));
        snapshot.measure = measure;
        snapshot.warnings = warnings;
        return snapshot;
    }

    private StructuredCashflow.ScfInfo createBaseScfInfo() {
        StructuredCashflow.ScfInfo scfInfo = new StructuredCashflow.ScfInfo();
        scfInfo.issueDate = irsCcsInfo.startDate;
        scfInfo.maturityDate = irsCcsInfo.maturityDate;
        scfInfo.couponProrated = true;
        scfInfo.interestStub = "longEnd";
        scfInfo.notionalFlag = irsCcsInfo.notionalExchangeType;
        return scfInfo;
    }

    private void configureFloatingLeg(StructuredCashflow.ScfInfo scfInfo, String interestType,
            Integer fixingDayoff, String fixingRule, String resetFreq, String fixingFreq) {
        if (!"floating".equalsIgnoreCase(interestType)) {
            return;
        }
        scfInfo.resetDayoff = fixingDayoff;
        scfInfo.resetRule = fixingRule;
        scfInfo.resetFreq = resetFreq;
        scfInfo.fixingFreq = fixingFreq;
    }

    private LegCalculation calculateLeg(StructuredCashflow.ScfInfo scfInfo, MarketData marketData,
            String legName, List<String> warnings) {
        StructuredCashflow structuredCashflow = new StructuredCashflow(dataDate, scfInfo, marketData, calendar);
        structuredCashflow.calc();
        appendScfWarnings(warnings, legName, structuredCashflow);
        LinkedList<StructuredCashflow.Cashflow> cashflows = structuredCashflow.getCashflowList();
        double value = cashflows.stream()
                .mapToDouble(cashflow -> cashflow.discoutFactor * cashflow.cf)
                .sum();
        return new LegCalculation(value, cashflows);
    }

    private void appendScfWarnings(List<String> warnings, String legName, StructuredCashflow scf) {
        if (warnings == null || scf == null) {
            return;
        }
        for (String warning : scf.getWarnings()) {
            if (StringUtils.isBlank(warning)) {
                continue;
            }
            String message = legName + "腿" + warning + " (INSTRUMENT_ID=" + irsCcsInfo.instrumentId + ")";
            if (!warnings.contains(message)) {
                warnings.add(message);
            }
        }
    }

    private void appendWarnings(Measure measure, List<String> warnings) {
        if (measure == null || warnings == null) {
            return;
        }
        for (String warning : warnings) {
            measure.addWarningLog(warning);
        }
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

    private List<FrtbSenes> buildFrtbSensList(CalcSnapshot base, MarketData baseMarketData) {
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
                base.measure.instrumentId,
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
        return newList;
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
     * CCS Basis 统一选择每条腿最能代表该币种利率风险的曲线。
     */
    private String resolveBasisCurve(String interestType, String referenceCurve, String discountCurve) {
        if ("floating".equalsIgnoreCase(interestType)) {
            return StringUtils.isBlank(referenceCurve) ? null : referenceCurve.trim();
        }
        if (StringUtils.isNotBlank(discountCurve)) {
            return discountCurve.trim();
        }
        return StringUtils.isBlank(referenceCurve) ? null : referenceCurve.trim();
    }

    public IrsCcs(LocalDate dataDate, IrsCcs.IrsCcsTradeInfo tradeInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.irsCcsInfo = tradeInfo;
        this.marketData = marketData;
        this.calendar = calendar;
    }

    private void validateInputs(MarketData md) {
        if (irsCcsInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        if (dataDate == null) {
            throw new IllegalArgumentException("数据日期为空");
        }
        if (calendar == null) {
            throw new IllegalArgumentException("交易日历为空");
        }
        requireText(irsCcsInfo.productCode, "PRODUCT_CODE");
        requireText(irsCcsInfo.instrumentId, "INSTRUMENT_ID");
        if (!"IRS".equalsIgnoreCase(irsCcsInfo.swapType) && !"CCS".equalsIgnoreCase(irsCcsInfo.swapType)) {
            throw new IllegalArgumentException("SWAP_TYPE 仅支持 IRS/CCS: " + irsCcsInfo.swapType);
        }
        if (irsCcsInfo.startDate == null) {
            throw new IllegalArgumentException("START_DATE 不能为空");
        }
        if (irsCcsInfo.maturityDate == null) {
            throw new IllegalArgumentException("MATURITY_DATE 不能为空");
        }
        if (!irsCcsInfo.startDate.isBefore(irsCcsInfo.maturityDate)) {
            throw new IllegalArgumentException("START_DATE 必须早于 MATURITY_DATE");
        }
        if (!isNotionalExchangeType(irsCcsInfo.notionalExchangeType)) {
            throw new IllegalArgumentException("NOTIONAL_EXCHANGE_TYPE 仅支持 START/END/START_END/NONE: "
                    + irsCcsInfo.notionalExchangeType);
        }
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空");
        }
        validateLeg(md, "PAY", irsCcsInfo.payNotional, irsCcsInfo.payCurrencyCode,
                irsCcsInfo.payInterestType, irsCcsInfo.payInterest, irsCcsInfo.payReferenceCurve,
                irsCcsInfo.paySpread, irsCcsInfo.payFreq, irsCcsInfo.payDayCountBasis,
                irsCcsInfo.payDiscountCurve, irsCcsInfo.payResetFreq, irsCcsInfo.payFixingFreq,
                irsCcsInfo.payInterestAggregationMethod);
        validateLeg(md, "REC", irsCcsInfo.recNotional, irsCcsInfo.recCurrencyCode,
                irsCcsInfo.recInterestType, irsCcsInfo.recInterest, irsCcsInfo.recReferenceCurve,
                irsCcsInfo.recSpread, irsCcsInfo.recFreq, irsCcsInfo.recDayCountBasis,
                irsCcsInfo.recDiscountCurve, irsCcsInfo.recResetFreq, irsCcsInfo.recFixingFreq,
                irsCcsInfo.recInterestAggregationMethod);
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少外汇即期曲线");
        }
    }

    private void validateLeg(MarketData md, String leg, Double notional, String currencyCode,
            String interestType, Double interest, String referenceCurve, Double spread, String payFreq,
            String dayCountBasis, String discountCurve, String resetFreq, String fixingFreq,
            String aggregationMethod) {
        requireNonNegativeFinite(notional, leg + "_NOTIONAL");
        requireCurrencyCode(currencyCode, leg + "_CURRENCY_CODE");
        if (!"FIXED".equalsIgnoreCase(interestType) && !"FLOATING".equalsIgnoreCase(interestType)) {
            throw new IllegalArgumentException(leg + "_INTEREST_TYPE 仅支持 FIXED/FLOATING: " + interestType);
        }
        requireText(payFreq, leg + "_FREQ");
        requireText(dayCountBasis, leg + "_DAY_COUNT_BASIS");
        requireText(discountCurve, leg + "_DISCOUNT_CURVE");
        requireAggregationMethod(aggregationMethod, leg + "_INTEREST_AGGREGATION_METHOD");
        requireFiniteIfPresent(spread, leg + "_SPREAD");
        if (md.irSpot == null || md.irSpot.get(discountCurve) == null) {
            throw new IllegalArgumentException("未找到" + leg + "折现曲线: " + discountCurve);
        }
        if ("FIXED".equalsIgnoreCase(interestType)) {
            requireFinite(interest, leg + "_INTEREST");
            return;
        }
        requireText(referenceCurve, leg + "_REFERENCE_CURVE");
        requireText(resetFreq, leg + "_RESET_FREQ");
        requireText(fixingFreq, leg + "_FIXING_FREQ");
        if (md.irSpot.get(referenceCurve) == null) {
            throw new IllegalArgumentException("未找到" + leg + "参考曲线: " + referenceCurve);
        }
        requireFiniteIfPresent(interest, leg + "_INTEREST");
    }

    private static boolean isNotionalExchangeType(String value) {
        return "START".equalsIgnoreCase(value) || "END".equalsIgnoreCase(value)
                || "START_END".equalsIgnoreCase(value) || "NONE".equalsIgnoreCase(value);
    }

    private static void requireAggregationMethod(String value, String field) {
        if (!"AVERAGE".equalsIgnoreCase(value) && !"COMPOUNDING".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(field + " 仅支持 AVERAGE/COMPOUNDING: " + value);
        }
    }

    private static void requireText(String value, String field) {
        if (StringUtils.isBlank(value)) {
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

    private static void requireFiniteIfPresent(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " 必须为有限数: " + value);
        }
    }

    private static void requireNonNegativeFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " 必须为非负有限数: " + value);
        }
    }

    public static class IrsCcsTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"IRS", "CCS"}, ignoreCase = true)
        @JSONField(name = "SWAP_TYPE")
        public String swapType;
        @ProductInputField(required = true)
        @JSONField(name = "START_DATE", format = "yyyy-MM-dd")
        public LocalDate startDate;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true, allowedValues = {"START", "END", "START_END", "NONE"},
                ignoreCase = true)
        @JSONField(name = "NOTIONAL_EXCHANGE_TYPE")
        public String notionalExchangeType;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "PAY_NOTIONAL")
        public Double payNotional;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "PAY_CURRENCY_CODE")
        public String payCurrencyCode;
        @ProductInputField(required = true, allowedValues = {"FIXED", "FLOATING"}, ignoreCase = true)
        @JSONField(name = "PAY_INTEREST_TYPE")
        public String payInterestType;
        @ProductInputField(finite = true)
        @JSONField(name = "PAY_INTEREST")
        public Double payInterest;
        @ProductInputField
        @JSONField(name = "PAY_REFERENCE_CURVE")
        public String payReferenceCurve;
        @JSONField(name = "PAY_FIXING_ID")
        public String payFixingId;
        @JSONField(name = "PAY_FIXING_CALENDAR")
        public String payFixingCalendar;
        @ProductInputField(finite = true)
        @JSONField(name = "PAY_SPREAD")
        public Double paySpread = 0.0;
        @ProductInputField(allowedValues = {"AVERAGE", "COMPOUNDING"}, ignoreCase = true)
        @JSONField(name = "PAY_INTEREST_AGGREGATION_METHOD")
        public String payInterestAggregationMethod = "COMPOUNDING";
        @ProductInputField(required = true)
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @ProductInputField(required = true)
        @JSONField(name = "PAY_DAY_COUNT_BASIS")
        public String payDayCountBasis = "actual/365";
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
        @ProductInputField(required = true)
        @JSONField(name = "PAY_DISCOUNT_CURVE")
        public String payDiscountCurve;
        @ProductInputField
        @JSONField(name = "PAY_RESET_FREQ")
        public String payResetFreq;
        @ProductInputField
        @JSONField(name = "PAY_FIXING_FREQ")
        public String payFixingFreq;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "REC_NOTIONAL")
        public Double recNotional;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "REC_CURRENCY_CODE")
        public String recCurrencyCode;
        @ProductInputField(required = true, allowedValues = {"FIXED", "FLOATING"}, ignoreCase = true)
        @JSONField(name = "REC_INTEREST_TYPE")
        public String recInterestType;
        @ProductInputField(finite = true)
        @JSONField(name = "REC_INTEREST")
        public Double recInterest;
        @ProductInputField
        @JSONField(name = "REC_REFERENCE_CURVE")
        public String recReferenceCurve;
        @JSONField(name = "REC_FIXING_ID")
        public String recFixingId;
        @JSONField(name = "REC_FIXING_CALENDAR")
        public String recFixingCalendar;
        @ProductInputField(finite = true)
        @JSONField(name = "REC_SPREAD")
        public Double recSpread = 0.0;
        @ProductInputField(allowedValues = {"AVERAGE", "COMPOUNDING"}, ignoreCase = true)
        @JSONField(name = "REC_INTEREST_AGGREGATION_METHOD")
        public String recInterestAggregationMethod = "COMPOUNDING";
        @ProductInputField(required = true)
        @JSONField(name = "REC_FREQ")
        public String recFreq;
        @ProductInputField
        @JSONField(name = "REC_RESET_FREQ")
        public String recResetFreq;
        @ProductInputField
        @JSONField(name = "REC_FIXING_FREQ")
        public String recFixingFreq;
        @ProductInputField(required = true)
        @JSONField(name = "REC_DAY_COUNT_BASIS")
        public String recDayCountBasis = "actual/365";
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
        @ProductInputField(required = true)
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
        public List<String> warnings;
    }

    private static class LegCalculation {
        private final double value;
        private final LinkedList<StructuredCashflow.Cashflow> cashflows;

        private LegCalculation(double value, LinkedList<StructuredCashflow.Cashflow> cashflows) {
            this.value = value;
            this.cashflows = cashflows;
        }
    }

}

