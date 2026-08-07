package com.zcyh.mr.product.credit;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.product.basic.frtb.FrtbDrcInterface;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.ReflectionUtils;
import com.zcyh.mr.calc.FrtbCalcControl;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.calendar.CashflowUtils;
import com.zcyh.mr.marketdata.CurveFunc;
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
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.*;

public class Cds implements FrtbDrcInterface {

    /** 违约损失率默认值 */
    private static final double DEFAULT_LGD = 0.75;

    private LocalDate dataDate;
    private MarketData marketData;
    private Cds.CdsTradeInfo cdsInfo;
    private JSONObject udData;
    private Calendar cal;

    public Cds(LocalDate dataDate, Cds.CdsTradeInfo cdsInfo, MarketData marketData, Calendar calendar, JSONObject udData) {
        this.dataDate = dataDate;
        this.cdsInfo = cdsInfo;
        this.cal = calendar;
        this.udData = TradeJsonUtil.mergeTrade(udData, EngineConstants.PRODUCT_CODE.CDS, "UNDERLYING_DATA");
        this.marketData = marketData;
    }

    public Cds.CdsMeasure calc() {
        PricingOutcome outcome = price(marketData);
        CdsMeasure result = outcome.measure;

        if (!isSuccess(result)) {
            result.pv01 = 0.0;
            result.drcDetail = null;
            result.sensitivityList = new ArrayList<>();
            result.cashFlowList = null;
        } else {
            // 无有效标的信息时（如已到期）直接返回空敏感度与空DRC，避免空值传播到落库层。
            if (outcome.bondInfo == null) {
                result.sensitivityList = new ArrayList<>();
                result.pv01 = 0.0;
                result.drcDetail = null;
            } else {
                result.sensitivityList = FrtbCalcControl.isSensitivityEnabled()
                        ? getFrtbSensitivity(result, outcome.bondInfo)
                        : new ArrayList<>();
                result.pv01 = pv01(marketData, result.valuation);
                result.drcDetail = FrtbCalcControl.isDrcEnabled()
                        ? buildDrc(outcome.bondInfo, result.valuation)
                        : null;
            }
        }
        return result;
    }

    private LinkedList<StructuredCashflow.Cashflow> buildDefaultPaymentCashflows(MarketData marketData,
            Bond.BondTradeInfo bondInfo) {
        StructuredCashflow.ScfInfo localScfInfo = ReflectionUtils.bean2Bean(bondInfo, StructuredCashflow.ScfInfo.class);
        localScfInfo.fixingCalendar = localScfInfo.settleCalendar;
        StructuredCashflow localScf = new StructuredCashflow(dataDate, localScfInfo, marketData, cal);
        localScf.calc(marketData);
        LinkedList<StructuredCashflow.Cashflow> cashFlowFilterD = new LinkedList<>();
        LinkedList<StructuredCashflow.Cashflow> cashflows = localScf.getCashflowList();
        LocalDate maturityPaymentDate = adjustSettlementDate(cdsInfo.maturityDate);
        int units = cdsInfo.buyOrSell.equalsIgnoreCase("B") ? 1 : -1;
        for (StructuredCashflow.Cashflow cashflow : cashflows) {
            // 所有的现金流金额统一设置为notional*(1-rr)
            cashflow.cf = cdsInfo.notional * (1 - cdsInfo.recoveryRate);
            // units乘到现金流金额上notional*(1-rr)
            cashflow.cf = cashflow.cf * units;
            if (cashflow.paymentDate.compareTo(this.dataDate) > 0
                    && cashflow.paymentDate.compareTo(maturityPaymentDate) < 0) {
                cashFlowFilterD.add(cashflow);
            }
        }
        // 仅当 CDS 到期日不超过债券到期日时才插入，债券已到期则无违约支付
        if (!cdsInfo.maturityDate.isAfter(bondInfo.maturityDate)) {
            StructuredCashflow.Cashflow insertCashFlow = new StructuredCashflow.Cashflow();
            insertCashFlow.paymentDate = maturityPaymentDate;
            insertCashFlow.cf = units * cdsInfo.notional * (1 - cdsInfo.recoveryRate);
            cashFlowFilterD.add(insertCashFlow);
        }
        return cashFlowFilterD;
    }

    private List<LegCashFlow> buildDefaultLeg(MarketData marketData, Bond.BondTradeInfo bondInfo,
            List<StructuredCashflow.Cashflow> cashFlowFilterD) {
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(cdsInfo.discountCurve)) {
            throw new IllegalArgumentException("折现曲线不存在: " + cdsInfo.discountCurve
                    + " (INSTRUMENT_ID=" + cdsInfo.instrumentId + ")");
        }
        if (!marketData.irSpot.containsKey(bondInfo.creditSpreadCurve)) {
            throw new IllegalArgumentException("信用点差曲线不存在: " + bondInfo.creditSpreadCurve
                    + " (INSTRUMENT_ID=" + cdsInfo.instrumentId + ")");
        }

        IrSpot.IrSpotInfo dcurve = CommUtils.deepCopy(marketData.irSpot.get(cdsInfo.discountCurve));
        IrSpot irSpot = new IrSpot(dcurve);

        IrSpot.IrSpotInfo udcurve = CommUtils.deepCopy(marketData.irSpot.get(bondInfo.creditSpreadCurve));
        IrSpot creditSpreadSpot = new IrSpot(udcurve);

        int flag = 0;
        int i = 0;
        double df_d;
        double df_ud;
        double marginal_q;
        double expected;
        ArrayList<Double> list_q = new ArrayList();
        List<LegCashFlow> defaultPaymentExpected = new ArrayList<>();
        for (StructuredCashflow.Cashflow cashflow : cashFlowFilterD) {
            // 利用dcurve计算从估值日到每一个现金流日期的折现因子df_d
            df_d = irSpot.discount(cashflow.paymentDate);
            // 无风险曲线和信用利差曲线分别按现金流日期取值后相加
            df_ud = combinedDiscount(irSpot, creditSpreadSpot, cashflow.paymentDate);
            // (1 - df_ud / df_d ) / (1 - rr)
            double q = (1 - df_ud / df_d) / (1 - cdsInfo.recoveryRate);
            list_q.add(q);
            if (flag == 0) {
                marginal_q = q - 0;
                flag = 1;
            } else {
                marginal_q = q - list_q.get(i - 1);
            }
            i++;
            expected = marginal_q * cashflow.cf;
            LegCashFlow legCf = new LegCashFlow();
            legCf.paymentDate = cashflow.paymentDate;
            legCf.expectedCashflow = expected;
            legCf.cashflow = cashflow.cf;
            legCf.dfDiscount = df_d;
            legCf.dfUnderlying = df_ud;
            defaultPaymentExpected.add(legCf);
        }
        return defaultPaymentExpected;
    }

    private LinkedList<StructuredCashflow.Cashflow> buildPremiumCashflows(MarketData marketData,
            Bond.BondTradeInfo premiumBondInfo) {
        StructuredCashflow.ScfInfo localScfInfo = ReflectionUtils.bean2Bean(premiumBondInfo, StructuredCashflow.ScfInfo.class);
        localScfInfo.fixingCalendar = localScfInfo.settleCalendar;
        // CDS 保费腔利息在期初支付，使用 SCF 内置的期初支付逻辑
        localScfInfo.paymentTiming = "advance";
        StructuredCashflow localScf = new StructuredCashflow(dataDate, localScfInfo, marketData, cal);
        localScf.calc(marketData);
        LinkedList<StructuredCashflow.Cashflow> cashFlowFilterS = new LinkedList<>();
        // scfInfo.paymentTiming 已设为 advance，getCashflowList 直接返回期初支付的现金流
        LinkedList<StructuredCashflow.Cashflow> cashflows = localScf.getCashflowList();
        int units = cdsInfo.buyOrSell.equalsIgnoreCase("B") ? -1 : 1;
        for (StructuredCashflow.Cashflow cashflow : cashflows) {
            // 只取利息类型现金流
            if (!"interest".equalsIgnoreCase(cashflow.cashType)) {
                continue;
            }
            cashflow.cf = cashflow.cf * units;
            if (cashflow.paymentDate.compareTo(this.dataDate) >= 0) {
                cashFlowFilterS.add(cashflow);
            }
        }
        return cashFlowFilterS;
    }

    private List<LegCashFlow> buildPremiumLeg(MarketData marketData, Bond.BondTradeInfo bondInfo,
            LinkedList<StructuredCashflow.Cashflow> cashFlowFilterS, CdsMeasure measure) {
        int units = cdsInfo.buyOrSell.equalsIgnoreCase("B") ? -1 : 1;
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(cdsInfo.discountCurve)) {
            throw new IllegalArgumentException("折现曲线不存在: " + cdsInfo.discountCurve
                    + " (INSTRUMENT_ID=" + cdsInfo.instrumentId + ")");
        }
        if (!marketData.irSpot.containsKey(bondInfo.creditSpreadCurve)) {
            throw new IllegalArgumentException("信用点差曲线不存在: " + bondInfo.creditSpreadCurve
                    + " (INSTRUMENT_ID=" + cdsInfo.instrumentId + ")");
        }
        IrSpot.IrSpotInfo dcurve = CommUtils.deepCopy(marketData.irSpot.get(cdsInfo.discountCurve));
        IrSpot irSpot = new IrSpot(dcurve);

        IrSpot.IrSpotInfo udcurve = CommUtils.deepCopy(marketData.irSpot.get(bondInfo.creditSpreadCurve));
        IrSpot creditSpreadSpot = new IrSpot(udcurve);
        double df_d;
        double df_ud;
        double sp;
        double expected;
        List<LegCashFlow> swapFlagExpected = new ArrayList<>();
        for (StructuredCashflow.Cashflow cashflow : cashFlowFilterS) {
            // 利用dcurve计算从估值日到每一个现金流日期的折现因子df_d
            df_d = irSpot.discount(cashflow.paymentDate);
            // 无风险曲线和信用利差曲线分别按现金流日期取值后相加
            df_ud = combinedDiscount(irSpot, creditSpreadSpot, cashflow.paymentDate);
            // (1 - df_ud / df_d ) / (1 - rr)
            double q = (1 - df_ud / df_d) / (1 - cdsInfo.recoveryRate);
            sp = 1 - q;
            expected = sp * cashflow.cf;

            LegCashFlow legCf = new LegCashFlow();
            legCf.paymentDate = cashflow.paymentDate;
            legCf.expectedCashflow = expected;
            legCf.cashflow = cashflow.cf;
            legCf.cashflowType = "interest_premium";
            legCf.dfDiscount = df_d;
            legCf.dfUnderlying = df_ud;
            swapFlagExpected.add(legCf);
        }
        // 固定保费参数不为空时，仅接受数组格式并逐笔处理
        // 格式: [{"PAYMENT_DATE":"2025-06-30","CF":"50000"}, ...]
        if (StringUtils.isBlank(cdsInfo.fixedPremium)) {
            return swapFlagExpected;
        }
        JSONArray fixedPremiums;
        try {
            fixedPremiums = JSON.parseArray(cdsInfo.fixedPremium.trim());
            if (fixedPremiums == null) {
                measure.addWarningLog("FIXED_PREMIUM 必须为数组格式，已跳过 (INSTRUMENT_ID="
                        + cdsInfo.instrumentId + ")");
                return swapFlagExpected;
            }
        } catch (Exception e) {
            measure.addWarningLog("FIXED_PREMIUM 格式解析失败（仅支持数组），已跳过 (INSTRUMENT_ID="
                    + cdsInfo.instrumentId + "): " + e.getMessage());
            return swapFlagExpected;
        }
        for (int i = 0; i < fixedPremiums.size(); i++) {
            JSONObject fpObj = fixedPremiums.getJSONObject(i);
            String fpDateStr = (String) fpObj.get("PAYMENT_DATE");
            String fpCfStr = (String) fpObj.get("CF");
            if (StringUtils.isBlank(fpDateStr) || StringUtils.isBlank(fpCfStr)) {
                continue;
            }
            LocalDate fpDate;
            double fpCf;
            try {
                fpDate = LocalDate.parse(fpDateStr);
                fpCf = Double.parseDouble(fpCfStr);
            } catch (Exception e) {
                measure.addWarningLog("FIXED_PREMIUM 第 " + i + " 条数据格式错误，已跳过 (INSTRUMENT_ID="
                        + cdsInfo.instrumentId + "): " + e.getMessage());
                continue;
            }

            // 只考虑支付日在估值日之后的固定保费
            if (!fpDate.isAfter(this.dataDate)) {
                continue;
            }

            StructuredCashflow.Cashflow insertCashFlow = new StructuredCashflow.Cashflow();
            insertCashFlow.paymentDate = fpDate;
            double signedFpCf = fpCf * units;
            insertCashFlow.cf = signedFpCf;
            cashFlowFilterS.add(insertCashFlow);

            // 计算折现因子和存活概率，与正常保费现金流逻辑一致
            df_d = irSpot.discount(fpDate);
            df_ud = combinedDiscount(irSpot, creditSpreadSpot, fpDate);
            double q = (1 - df_ud / df_d) / (1 - cdsInfo.recoveryRate);
            sp = 1 - q;
            expected = sp * signedFpCf;

            LegCashFlow legCf = new LegCashFlow();
            legCf.paymentDate = fpDate;
            legCf.expectedCashflow = expected;
            legCf.cashflow = signedFpCf;
            legCf.cashflowType = "fixed_premium";
            legCf.dfDiscount = df_d;
            legCf.dfUnderlying = df_ud;
            swapFlagExpected.add(legCf);
        }
        return swapFlagExpected;
    }

    private double combinedDiscount(IrSpot discountCurve, IrSpot creditSpreadCurve, LocalDate date) {
        IrSpot.IrSpotInfo discountInfo = discountCurve.getIrSpotInfo();
        double rate = discountCurve.spotRate(date) + creditSpreadCurve.spotRate(date);
        return CurveFunc.discountFactor(discountInfo.pDataDate, date, rate, discountInfo.freq, discountInfo.dayCount);
    }

    private double pv01(MarketData marketData, double baseValuation) {
        MarketData shockedMd = buildShiftedIrMarketData(marketData, cdsInfo.discountCurve, 0.0001);
        CdsMeasure shockedMeasure = calc(shockedMd);
        if (!isSuccess(shockedMeasure)) {
            return 0.0;
        }
        return shockedMeasure.valuation - baseValuation;
    }

    /**
     * 仅复制需要冲击的折现曲线，构造局部替换后的市场数据。
     */
    private MarketData buildShiftedIrMarketData(MarketData baseMarketData, String curveId, double shift) {
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

        if (curveId == null || curveId.trim().isEmpty()) {
            return shockedMarketData;
        }
        IrSpot.IrSpotInfo curveInfo = baseMarketData.irSpot.get(curveId);
        if (curveInfo == null) {
            return shockedMarketData;
        }
        IrSpot.IrSpotInfo shockedCurve = CommUtils.deepCopy(curveInfo);
        shockedCurve.shift(shift);
        shockedMarketData.irSpot.put(curveId, shockedCurve);
        return shockedMarketData;
    }

    public Cds.CdsMeasure calc(MarketData marketData) {
        return price(marketData).measure;
    }

    private PricingOutcome price(MarketData marketData) {
        PricingOutcome outcome = new PricingOutcome();
        CdsMeasure cdsMeasure = initMeasure();
        outcome.measure = cdsMeasure;

        // CDS到期后不再参与估值与DRC计量，统一按0敞口返回，避免产生空JTD。
        if (cdsInfo.maturityDate != null && !cdsInfo.maturityDate.isAfter(dataDate)) {
            cdsMeasure.valuation = 0.0;
            cdsMeasure.valuationCny = 0.0;
            cdsMeasure.cashFlowList = new ArrayList<>();
            return outcome;
        }

        JSONObject underlyingData = (JSONObject) udData.get(cdsInfo.underlyingBondId);
        if (underlyingData == null) {
            return failOutcome(outcome,
                    "标的债券数据不存在: INSTRUMENT_ID=" + cdsInfo.instrumentId
                            + ", UNDERLYING_BOND_ID=" + cdsInfo.underlyingBondId);
        }

        try {
            Bond.BondTradeInfo bondInfo = buildDefaultBondInfo(underlyingData);
            Bond.BondTradeInfo premiumBondInfo = buildPremiumBondInfo(bondInfo);

            LinkedList<StructuredCashflow.Cashflow> defaultCashFlows = buildDefaultPaymentCashflows(marketData, bondInfo);
            List<LegCashFlow> defaultPaymentExpected = buildDefaultLeg(marketData, bondInfo, defaultCashFlows);
            LinkedList<StructuredCashflow.Cashflow> premiumCashFlows = buildPremiumCashflows(marketData, premiumBondInfo);
            List<LegCashFlow> swapFlagExpected = buildPremiumLeg(marketData, bondInfo, premiumCashFlows, cdsMeasure);

            cdsMeasure.valuation = getValuation(defaultPaymentExpected, swapFlagExpected);
            if (!Double.isFinite(cdsMeasure.valuation)) {
                return failOutcome(outcome,
                        "CDS估值结果非法(非有限数): INSTRUMENT_ID=" + cdsInfo.instrumentId);
            }
            // 使用传入 MarketData 的汇率，确保场景 FX 冲击生效
            FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance()
                    .getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
            cdsMeasure.valuationCny = cdsMeasure.valuation * fxSpot.getFxrate(cdsInfo.currencyCode);
            if (!Double.isFinite(cdsMeasure.valuationCny)) {
                return failOutcome(outcome,
                        "CDS估值本币折算结果非法(非有限数): INSTRUMENT_ID=" + cdsInfo.instrumentId);
            }
            cdsMeasure.cashFlowList = buildCdsCashFlowList(defaultPaymentExpected, swapFlagExpected);
            outcome.bondInfo = bondInfo;
            return outcome;
        } catch (IllegalArgumentException e) {
            return failOutcome(outcome, e.getMessage());
        }
    }

    private CdsMeasure initMeasure() {
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance()
                .getValue(EngineConstants.CFG.FX_BASE_CODE), this.marketData.fxSpot);
        Cds.CdsMeasure cdsMeasure = new Cds.CdsMeasure();
        cdsMeasure.instrumentId = cdsInfo.instrumentId;
        cdsMeasure.productCode = cdsInfo.productCode;
        cdsMeasure.dataDate = dataDate;
        cdsMeasure.position = 1.0;
        cdsMeasure.valuationCcy = cdsInfo.currencyCode;
        cdsMeasure.valuation = 0.0;
        cdsMeasure.valuationCny = 0.0;
        cdsMeasure.valuationUnit = 0.0;
        cdsMeasure.status = "SUCCESS";
        cdsMeasure.logs = new ArrayList<>();
        cdsMeasure.detail = new LinkedHashMap<>();
        cdsMeasure.sensitivityList = new ArrayList<>();
        cdsMeasure.cashFlowList = new ArrayList<>();
        return cdsMeasure;
    }

    private PricingOutcome failOutcome(PricingOutcome outcome, String errorMessage) {
        outcome.measure.status = "ERROR";
        outcome.measure.addErrorLog(errorMessage);
        outcome.measure.cashFlowList = null;
        outcome.measure.detail = null;
        return outcome;
    }

    private List<FrtbSenes> getFrtbSensitivity(CdsMeasure measure, Bond.BondTradeInfo bondInfo) {
        MeasureValuation baseValuation = toMeasureValuation(measure);
        List<FrtbSenes> list = new ArrayList<>();
        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                Collections.singletonList(cdsInfo.currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                dataDate,
                fxDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                cdsInfo.instrumentId,
                cdsInfo.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(calc(shockedMarketData)));
        list.addAll(fxSensitivities);

        HashMap<String, String> girrMap = new HashMap<>();
        girrMap.put(cdsInfo.discountCurve, cdsInfo.currencyCode);
        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(girrMap);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                null,
                girrDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                cdsInfo.instrumentId,
                cdsInfo.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(calc(shockedMarketData)),
                null,
                null);
        list.addAll(girrSensitivities);

        if (bondInfo != null && !StringUtils.isBlank(bondInfo.creditSpreadCurve)
                && !StringUtils.isBlank(bondInfo.issuer)
                && !StringUtils.isBlank(bondInfo.frtbCsrBucket)) {
            List<FrtbDependency> csrDependencies = bondInfo.absFlag
                    ? FrtbSensitivityBuilder.buildCsrSecNonCtpDeltaDependencies(
                            bondInfo.creditSpreadCurve,
                            bondInfo.issuer,
                            bondInfo.frtbCsrBucket,
                            "CDS")
                    : FrtbSensitivityBuilder.buildCsrNonSecDeltaDependencies(
                            bondInfo.creditSpreadCurve,
                            bondInfo.issuer,
                            bondInfo.frtbCsrBucket,
                            "CDS");
        List<FrtbSenes> csrSensitivities = FrtbSensitivityBuilder.buildCsrSensitivities(
                    marketData,
                    dataDate,
                    csrDependencies,
                    true,
                    false,
                    cdsInfo.instrumentId,
                    cdsInfo.currencyCode,
                    1e-12,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(calc(shockedMarketData)));
        list.addAll(csrSensitivities);
        }
        list.removeIf(item -> (item.sensitivityValInstCurr == 0 && item.sensitivityValInstCurrCny == 0));/*
                                                                                                          * 移除敏度结果为0的元素
                                                                                                          */
        return list;
    }

    /**
     * 将 CDS 场景估值结果收口为公共 builder 使用的估值对象。
     * 场景估值失败时返回 null，由公共 builder 直接跳过该 shock 条目。
     */
    private MeasureValuation toMeasureValuation(CdsMeasure measure) {
        if (!isSuccess(measure)) {
            return null;
        }
        return MeasureValuation.of(measure.valuation, measure.valuationCny);
    }

    private double getValuation(List<LegCashFlow> defaultPaymentExpected, List<LegCashFlow> swapFlagExpected) {
        double dpSum = 0.0;
        for (LegCashFlow legCf : defaultPaymentExpected) {
            dpSum += legCf.dfDiscount * legCf.expectedCashflow;
        }
        double sfSum = 0.0;
        for (LegCashFlow legCf : swapFlagExpected) {
            sfSum += legCf.dfDiscount * legCf.expectedCashflow;
        }
        return dpSum + sfSum;
    }

    private Bond.BondTradeInfo buildDefaultBondInfo(JSONObject underlyingData) {
        Bond.BondTradeInfo bondInfo = JSON.parseObject(underlyingData.toString(), Bond.BondTradeInfo.class);
        // 默认支付
        bondInfo.currencyCode = cdsInfo.currencyCode;
        bondInfo.interestType = "fixed";
        bondInfo.interestRate = 1 - cdsInfo.recoveryRate;
        bondInfo.referenceCurve = null;
        bondInfo.fixingId = null;
        bondInfo.spread = null;
        bondInfo.fixingFreq = null;
        applySettlementConvention(bondInfo, cdsInfo);
        bondInfo.resetRule = null;
        bondInfo.resetDayoff = null;
        bondInfo.resetFreq = null;
        bondInfo.discountCurve = cdsInfo.discountCurve;
        bondInfo.notional = cdsInfo.notional;
        bondInfo.includeTodayCashflow = true;
        bondInfo.couponProrated = true;
        return bondInfo;
    }

    private Bond.BondTradeInfo buildPremiumBondInfo(Bond.BondTradeInfo bondInfo) {
        Bond.BondTradeInfo bondInfoSF = ReflectionUtils.bean2Bean(bondInfo, Bond.BondTradeInfo.class);
        bondInfoSF.issueDate = cdsInfo.startDate;
        bondInfoSF.maturityDate = cdsInfo.maturityDate;
        bondInfoSF.interestStub = cdsInfo.interestStub;
        bondInfoSF.interestRate = cdsInfo.fixedRate;
        bondInfoSF.payFreq = cdsInfo.payFreq;
        bondInfoSF.dayCountBasis = cdsInfo.dayCountBasis;
        applySettlementConvention(bondInfoSF, cdsInfo);
        return bondInfoSF;
    }

    private LocalDate adjustSettlementDate(LocalDate date) {
        if (StringUtils.isBlank(cdsInfo.settleRule)) {
            return date;
        }
        int settleDayoff = cdsInfo.settleDayoff == null ? 0 : cdsInfo.settleDayoff;
        return cal.getBusinessDay(cdsInfo.settleCalendar, date,
                CashflowUtils.attr2BusinessDayConvention(cdsInfo.settleRule), settleDayoff);
    }

    static void applySettlementConvention(Bond.BondTradeInfo bondInfo, CdsTradeInfo cdsInfo) {
        bondInfo.settleCalendar = cdsInfo.settleCalendar;
        bondInfo.settleRule = StringUtils.trimToNull(cdsInfo.settleRule);
        bondInfo.settleDayoff = cdsInfo.settleDayoff == null ? 0 : cdsInfo.settleDayoff;
    }

    @Override
    public double jtd() {
        PricingOutcome outcome = price(marketData);
        if (!isSuccess(outcome.measure) || outcome.bondInfo == null) {
            return 0.0;
        }
        return calculateJtd(outcome.bondInfo, outcome.measure.valuation);
    }

    private DrcDetail buildDrc(Bond.BondTradeInfo bondInfo, double valuation) {
        if (!bondInfo.isDrcEnabled()) {
            return null;
        }
        FrtbDrcInterface.Param param = ReflectionUtils.bean2Bean(bondInfo, FrtbDrcInterface.Param.class);
        double jtd = calculateJtd(bondInfo, valuation);
        DrcDetail drcDetail = ((FrtbDrcInterface) () -> jtd).getDrc(param, dataDate, valuation);
        double fxRate = new FxSpot(EngineConfiguration.getInstance()
                .getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot).getFxrate(bondInfo.currencyCode);
        drcDetail.jtdCny *= fxRate;
        drcDetail.instrumentValue *= fxRate;
        return drcDetail;
    }

    private double calculateJtd(Bond.BondTradeInfo bondInfo, double valuation) {
        // LGD 默认值常量化，不修改原始对象状态
        double lgd = (bondInfo.lgd != null) ? bondInfo.lgd : DEFAULT_LGD;
        if (bondInfo.absFlag) {
            return "B".equalsIgnoreCase(cdsInfo.buyOrSell)
                    ? Math.min(-1 * cdsInfo.notional + valuation, 0)
                    : Math.max(cdsInfo.notional + valuation, 0);
        }
        return "B".equalsIgnoreCase(cdsInfo.buyOrSell)
                ? Math.min(-1 * lgd * cdsInfo.notional + valuation, 0)
                : Math.max(lgd * cdsInfo.notional + valuation, 0);
    }

    private boolean isSuccess(CdsMeasure measure) {
        return measure != null && "SUCCESS".equalsIgnoreCase(measure.status);
    }

    private List<BaseCashFlow> buildCdsCashFlowList(List<LegCashFlow> defaultPaymentExpected,
            List<LegCashFlow> swapFlagExpected) {
        List<BaseCashFlow> cashflowList = new LinkedList<>();
        for (LegCashFlow legCf : defaultPaymentExpected) {
            Cds.CdsCashflow cdsCashflow = new CdsCashflow();
            cdsCashflow.dataDate = dataDate;
            cdsCashflow.notional = cdsInfo.notional;
            cdsCashflow.paymentDate = legCf.paymentDate;
            cdsCashflow.currencyCode = cdsInfo.currencyCode;
            cdsCashflow.cashFlowType = "default_payment";
            cdsCashflow.cashflow = legCf.cashflow;
            cdsCashflow.discountFactor = legCf.dfDiscount;
            cdsCashflow.underlyingDiscountFactor = legCf.dfUnderlying;
            cdsCashflow.expectedCashflow = legCf.expectedCashflow;
            cashflowList.add(cdsCashflow);
        }
        for (LegCashFlow legCf : swapFlagExpected) {
            Cds.CdsCashflow cdsCashflow = new CdsCashflow();
            cdsCashflow.dataDate = dataDate;
            cdsCashflow.notional = cdsInfo.notional;
            cdsCashflow.paymentDate = legCf.paymentDate;
            cdsCashflow.currencyCode = cdsInfo.currencyCode;
            cdsCashflow.cashFlowType = legCf.cashflowType;
            cdsCashflow.cashflow = legCf.cashflow;
            cdsCashflow.discountFactor = legCf.dfDiscount;
            cdsCashflow.underlyingDiscountFactor = legCf.dfUnderlying;
            cdsCashflow.expectedCashflow = legCf.expectedCashflow;
            cashflowList.add(cdsCashflow);
        }
        return cashflowList;
    }

    public static class CdsTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true)
        @JSONField(name = "START_DATE", format = "yyyy-MM-dd")
        public LocalDate startDate;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_BOND_ID")
        public String underlyingBondId;
        @ProductInputField(required = true, finite = true, min = "0", max = "1", maxInclusive = false)
        @JSONField(name = "RECOVERY_RATE")
        public Double recoveryRate;
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "FIXED_RATE")
        public Double fixedRate;
        @ProductInputField(required = true)
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";
        @ProductInputField(required = true)
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @ProductInputField(required = true)
        @JSONField(name = "INTEREST_STUB")
        public String interestStub;
        @JSONField(name = "SETTLE_CALENDAR")
        public String settleCalendar;
        @ProductInputField(allowedValues = {"Regular_Preceding", "Modified_Preceding",
                "Regular_Following", "Modified_Following"})
        @JSONField(name = "SETTLE_RULE")
        public String settleRule;
        @ProductInputField(min = "0")
        @JSONField(name = "SETTLE_DAYOFF")
        public Integer settleDayoff = 0;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "FIXED_PREMIUM")
        public String fixedPremium;
        @JSONField(serialize = false, deserialize = false)
        public Bond.BondTradeInfo bondInfo;
    }

    public static class CdsMeasure extends Measure {
        @JSONField(name = "DRC")
        public DrcDetail drcDetail;
    }

    public static class CdsCashflow extends BaseCashFlow {
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "EXPECTED_CASHFLOW")
        public Double expectedCashflow;
        @JSONField(name = "UNDERLYING_DISCOUNT_FACTOR")
        public Double underlyingDiscountFactor;

        @JSONField(name = "EXPECTED_CASHFLOW_PV")
        public Double getExpectedCashflowPv() {
            if (expectedCashflow == null || discountFactor == null) {
                return null;
            }
            return expectedCashflow * discountFactor;
        }
    }

    /**
     * 内部中间现金流数据结构，用于违约支付腿和保费腿的中间计算结果存储
     */
    private static class LegCashFlow {
        LocalDate paymentDate;
        double cashflow;
        double expectedCashflow;
        double dfDiscount;
        double dfUnderlying;
        String cashflowType;
    }

    /**
     * 无状态主估值返回对象，承接主估值结果和 DRC 所需的标的债信息。
     */
    private static class PricingOutcome {
        private CdsMeasure measure;
        private Bond.BondTradeInfo bondInfo;
    }
}

