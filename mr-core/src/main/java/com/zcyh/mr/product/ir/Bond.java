package com.zcyh.mr.product.ir;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.product.basic.frtb.FrtbDrcInterface;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.ReflectionUtils;
import com.zcyh.mr.calc.FrtbCalcControl;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.math.Newton;
import com.zcyh.mr.math.Ops;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.validation.BooleanInputReader;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.Series;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 固息债、浮息债估值类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/12 10:31
 */

public class Bond implements FrtbDrcInterface {

    private LocalDate dataDate;
    private BondTradeInfo bondInfo;
    private MarketData marketData;
    private Calendar cal;
    StructuredCashflow scf;
    private BondMeasure bondMeasure;
    Double spreadOverYield = null;

    LinkedList<StructuredCashflow.Cashflow> cashflowList;

    public Bond(LocalDate dataDate, BondTradeInfo bondInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.bondInfo = bondInfo;
        this.marketData = marketData;
        this.cal = calendar;
        validateInputs(marketData);
        // 含权债忽略摊销计划
        if (bondInfo.optionBondFlag) {
            bondInfo.amortizationSchedule = null;
        }
        StructuredCashflow.ScfInfo scfInfo = ReflectionUtils.bean2Bean(bondInfo, StructuredCashflow.ScfInfo.class);
        scfInfo.paymentTiming = "arrear";
        scfInfo.notionalFlag = "END";
        scfInfo.fixingCalendar = bondInfo.settleCalendar;
        scfInfo.allowMissingReferenceCurveAsZeroForward = false;
        scf = new StructuredCashflow(dataDate, scfInfo, marketData, calendar);

    }

    public BondMeasure calc() {
        validateInputs(marketData);
        // 含权债：在 SOY 校准前选取最优行权日
        if (bondInfo.optionBondFlag) {
            resolveCallPutMaturity();
        }

        // 仅首次校准：null 表示未校准，非 null（含通过 setSpreadOverYield 预设的值）跳过
        if (spreadOverYield == null) {
            spreadOverYield = this.spreadOverYield();
        }
        bondMeasure = this.calcInternal(marketData);
        bondMeasure.spreadOverYield = spreadOverYield;

        // 保存基准现金流（后续 risk metrics / FRTB 会覆盖 scf 内部状态）
        this.cashflowList = this.scf.getCashflowList();
        bondMeasure.cashFlowList = buildOutputCashFlowList(this.cashflowList, bondInfo.currencyCode);

        // 合并 PV01 / duration / convexity 计算，减少重复估值次数
        calcRiskMetrics();

        bondMeasure.accruedInterest = accruedInterest();
        bondMeasure.drcDetail = FrtbCalcControl.isDrcEnabled() ? getDrc() : null;
        bondMeasure.productCode = bondInfo.productCode;
        bondMeasure.dataDate = dataDate;
        bondMeasure.position = bondInfo.positionTrade;
        bondMeasure.valuationCcy = bondInfo.currencyCode;
        bondMeasure.valuationUnit = bondMeasure.position == 0.0 ? 0.0 : bondMeasure.valuation / bondMeasure.position;
        bondMeasure.status = "SUCCESS";
        bondMeasure.logs = new ArrayList<>();
        appendScfWarnings(bondMeasure);
        appendCreditSpreadCurveWarnings(bondMeasure);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("valuation_marity_date", bondInfo.maturityDate == null ? null : bondInfo.maturityDate.toString());
        bondMeasure.detail = detail;
        if (FrtbCalcControl.isSensitivityEnabled()) {
            getFrtbSensList();
        } else {
            bondMeasure.sensitivityList = new ArrayList<>();
        }
        return bondMeasure;
    }

    /**
     * 场景估值方法（零 deepCopy 优化）。
     * SCF 用场景数据生成现金流，Bond 自行构建折现曲线叠加信用利差和 SOY。
     * 不修改传入的 MarketData，不做全量深拷贝。
     */
    public BondMeasure calc(MarketData scenarioMd) {
        validateInputs(scenarioMd);
        // SCF 用场景数据更新现金流（浮息投射用 referenceCurve，不触及 discountCurve）
        scf.calc(scenarioMd);
        LinkedList<StructuredCashflow.Cashflow> cashflows = scf.getCashflowList();

        double value = calculatePresentValue(scenarioMd, cashflows);

        // 构建 Measure
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance()
                .getValue(EngineConstants.CFG.FX_BASE_CODE), scenarioMd.fxSpot);
        BondMeasure measure = new BondMeasure();
        double positionTrade = bondInfo.positionTrade;
        measure.instrumentId = bondInfo.instrumentId;
        measure.position = positionTrade;
        measure.valuationCcy = bondInfo.currencyCode;
        measure.valuation = value * positionTrade;
        measure.valuationCny = value * positionTrade * fxSpot.getFxrate(bondInfo.currencyCode);
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        return measure;
    }

    /**
     * 内部估值方法：构造局部替换后的折现曲线并计算。
     * 不直接修改传入的 MarketData，避免共享市场数据被污染。
     */
    BondMeasure calcInternal(MarketData md) {
        this.scf.calc(md);
        LinkedList<StructuredCashflow.Cashflow> cashflows = this.scf.getCashflowList();

        Double value = calculatePresentValue(md, cashflows);

        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance()
                .getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);

        BondMeasure bondMeasure = new BondMeasure();
        double positionTrade = bondInfo.positionTrade;
        bondMeasure.instrumentId = bondInfo.instrumentId;
        bondMeasure.position = positionTrade;
        bondMeasure.valuationCcy = bondInfo.currencyCode;
        bondMeasure.valuation = value * positionTrade;
        bondMeasure.valuationCny = value * positionTrade * fxSpot.getFxrate(bondInfo.currencyCode);
        bondMeasure.valuationUnit = bondMeasure.position == 0.0 ? 0.0 : bondMeasure.valuation / bondMeasure.position;
        return bondMeasure;
    }

    /**
     * 合并计算 PV01、有效久期、有效凸性，减少重复估值次数
     * 原本 pv01+duration+convexity 需要 5 次估值，优化后仅 3 次
     */
    private void calcRiskMetrics() {
        double eps = 0.0001;

        // duration、convexity 与 PV01 使用一致的利率冲击口径
        MarketData marketDataUp = buildRiskShockMarketData(marketData, eps);
        MarketData marketDataDown = buildRiskShockMarketData(marketData, -eps);

        BondMeasure measureUp = calcInternal(marketDataUp);
        BondMeasure measureDown = calcInternal(marketDataDown);

        // PV01 与 duration、convexity 统一使用同一套 shock 后市场
        MarketData pv01Md = buildRiskShockMarketData(marketData, eps);
        BondMeasure pv01Measure = calcInternal(pv01Md);
        // PV01统一按估值币种口径计算，不按CNY折算
        bondMeasure.pv01 = pv01Measure.valuation - bondMeasure.valuation;

        // 有效久期：中心差分法
        bondMeasure.effectiveDuration = (measureDown.valuation - measureUp.valuation)
                / (2 * eps * bondMeasure.valuation);

        // 有效凸性：中心差分法
        bondMeasure.effectiveConvexity = (measureUp.valuation + measureDown.valuation - 2 * bondMeasure.valuation)
                / (Math.pow(eps, 2) * bondMeasure.valuation);
    }

    private double accruedInterest() {
        LinkedList<StructuredCashflow.Cashflow> cashflowList = new LinkedList<>(this.cashflowList);
        List<StructuredCashflow.Cashflow> list = cashflowList.stream()
                .filter(e -> "interest".equalsIgnoreCase(e.cashType))
                .sorted(Comparator.comparing(i -> i.theoPaymentDate)).collect(Collectors.toList());

        if (list.size() == 0)
            return 0.0;
        List<StructuredCashflow.Cashflow> cfAccruedSimDate = new ArrayList<>();
        /* 遍历找到dataDate所在区间 */
        for (StructuredCashflow.Cashflow cashflow : list) {
            if (!dataDate.isAfter(cashflow.theoPaymentDate)) {
                cfAccruedSimDate.add(cashflow);
                break;
            }
        }
        double res = 0.0;
        if (cfAccruedSimDate.size() == 1) {
            LocalDate left = cfAccruedSimDate.get(0).theoPrePaymentDate;
            LocalDate right = cfAccruedSimDate.get(0).theoPaymentDate;
            res = cfAccruedSimDate.get(0).cf * ChronoUnit.DAYS.between(left, dataDate)
                    / (double) ChronoUnit.DAYS.between(left, right);
        }
        double positionTrade = bondInfo.positionTrade;
        return res * positionTrade;
    }

    public void getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                Collections.singletonList(bondInfo.currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                dataDate,
                fxDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                bondInfo.instrumentId,
                bondInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(bondMeasure.valuation, bondMeasure.valuationCny),
                this::repriceForFrtbSensitivity);
        list.addAll(fxSensitivities);

        HashMap<String, String> map = new HashMap<>();

        // GIRR Delta
        map.put(bondInfo.discountCurve, bondInfo.currencyCode);
        if ("floating".equalsIgnoreCase(bondInfo.interestType)
                && !StringUtils.isBlank(bondInfo.referenceCurve)
                && marketData.irSpot != null
                && marketData.irSpot.containsKey(bondInfo.referenceCurve)) {
            map.put(bondInfo.referenceCurve, bondInfo.currencyCode);
        }
        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(map);
        List<FrtbSenes> girrDeltaSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                null,
                girrDeltaDependencies,
                Collections.emptyList(),
                true,
                bondInfo.optionBondFlag,
                bondInfo.instrumentId,
                bondInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(bondMeasure.valuation, bondMeasure.valuationCny),
                this::repriceForFrtbSensitivity,
                hasValidCallPutDates()
                        ? this::repriceForFrtbSensitivity
                        : null,
                null);
        list.addAll(girrDeltaSensitivities);

        // CSR Delta/Curvature：统一冲击折现曲线，信用点差曲线仅参与估值、不参与 FRTB shock
        map.clear();
        String csrCurve = bondInfo.discountCurve;
        List<FrtbDependency> csrDependencies = bondInfo.absFlag
                ? FrtbSensitivityBuilder.buildCsrSecNonCtpDeltaDependencies(
                        csrCurve,
                        bondInfo.issuer,
                        bondInfo.frtbCsrBucket,
                        bondInfo.productCode)
                : FrtbSensitivityBuilder.buildCsrNonSecDeltaDependencies(
                        csrCurve,
                        bondInfo.issuer,
                        bondInfo.frtbCsrBucket,
                        bondInfo.productCode);
        List<FrtbSenes> csrSensitivities = FrtbSensitivityBuilder.buildCsrSensitivities(
                marketData,
                dataDate,
                csrDependencies,
                true,
                false,
                bondInfo.instrumentId,
                bondInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(bondMeasure.valuation, bondMeasure.valuationCny),
                this::repriceForFrtbSensitivity);
        list.addAll(csrSensitivities);

        list.removeIf(item -> (Math.abs(item.sensitivityValInstCurr) < 1e-12
                && Math.abs(item.sensitivityValInstCurrCny) < 1e-12));/*
                                                                                                          * 移除敏度结果为0的元素
                                                                                                          */
        bondMeasure.sensitivityList = list;
    }

    private com.zcyh.mr.product.basic.frtb.MeasureValuation repriceForFrtbSensitivity(MarketData shockedMarketData) {
        BondMeasure shockedMeasure = hasValidCallPutDates()
                ? calcWithReselectMaturity(shockedMarketData)
                : calcInternal(shockedMarketData);
        return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
    }

    public double spreadOverYield() {
        if (bondInfo.dirtyPrice == null || bondInfo.dirtyPrice == 0) {
            return 0.0;
        }
        Double originalSpreadOverYield = this.spreadOverYield;
        final Ops.DoubleOp func = new Ops.DoubleOp() {
            public double op(final double x) {
                spreadOverYield = x;
                Bond.BondMeasure measure = calcInternal(marketData);
                return bondInfo.dirtyPrice * bondInfo.positionTrade - measure.valuation;
            }
        };

        final double accuracy = 1.0e-8;
        final Newton newton = new Newton();
        try {
            double root = newton.solve(func, accuracy, 0.01, -100, 100);
            return root;
        } finally {
            this.spreadOverYield = originalSpreadOverYield;
        }
    }

    public DrcDetail getDrc() {
        if (!bondInfo.isDrcEnabled()) {
            return null;
        }
        FrtbDrcInterface.Param param = ReflectionUtils.bean2Bean(bondInfo, FrtbDrcInterface.Param.class);
        DrcDetail drcDetail = this.getDrc(param, dataDate, bondMeasure.valuation);
        double fxRate = new FxSpot(EngineConfiguration.getInstance()
                .getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot).getFxrate(bondInfo.currencyCode);
        drcDetail.jtdCny *= fxRate;
        drcDetail.instrumentValue *= fxRate;
        return drcDetail;
    }

    public LinkedList<StructuredCashflow.Cashflow> getCashflowList() {
        return cashflowList;
    }

    public StructuredCashflow getScf() {
        return scf;
    }

    public void setSpreadOverYield(Double val) {
        this.spreadOverYield = val;
    }

    private List<BaseCashFlow> buildOutputCashFlowList(List<StructuredCashflow.Cashflow> src, String currencyCode) {
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
            out.cashflow = cf.cf;
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

    private double calculatePresentValue(MarketData md, List<StructuredCashflow.Cashflow> cashflows) {
        double value = 0.0;
        if (cashflows == null) {
            return value;
        }
        for (StructuredCashflow.Cashflow cf : cashflows) {
            double df = adjustedForwardDiscount(md, dataDate, cf.paymentDate);
            if (bondInfo.includeTodayCashflow != null
                    && !bondInfo.includeTodayCashflow
                    && cf.paymentDate != null
                    && cf.paymentDate.equals(dataDate)) {
                df = 0.0;
            }
            cf.discoutFactor = df;
            value += cf.cf * df;
        }
        return value;
    }

    private double adjustedForwardDiscount(MarketData md, LocalDate from, LocalDate to) {
        IrSpot.IrSpotInfo discountCurveInfo = md.irSpot.get(bondInfo.discountCurve);
        LocalDate originDate = discountCurveInfo.pDataDate == null ? dataDate : discountCurveInfo.pDataDate;
        if (from.isAfter(to)) {
            if (to.isBefore(originDate)) {
                return 0.0;
            }
            return 1.0;
        }
        if (to.isBefore(originDate)) {
            return 0.0;
        }
        if (from.isBefore(originDate)) {
            double rate = adjustedSpotRate(md, discountCurveInfo, to);
            return CurveFunc.discountFactor(from, to, rate, discountCurveInfo.freq, discountCurveInfo.dayCount);
        }
        double disc1 = adjustedDiscount(md, discountCurveInfo, from);
        if (disc1 == 0.0) {
            return 0.0;
        }
        double disc2 = adjustedDiscount(md, discountCurveInfo, to);
        return disc2 / disc1;
    }

    private double adjustedDiscount(MarketData md, IrSpot.IrSpotInfo discountCurveInfo, LocalDate date) {
        double rate = adjustedSpotRate(md, discountCurveInfo, date);
        LocalDate originDate = discountCurveInfo.pDataDate == null ? dataDate : discountCurveInfo.pDataDate;
        return CurveFunc.discountFactor(originDate, date, rate, discountCurveInfo.freq, discountCurveInfo.dayCount);
    }

    private double adjustedSpotRate(MarketData md, IrSpot.IrSpotInfo discountCurveInfo, LocalDate date) {
        LocalDate originDate = discountCurveInfo.pDataDate == null ? dataDate : discountCurveInfo.pDataDate;
        int days = (int) ChronoUnit.DAYS.between(originDate, date);
        double rate = interpolateRateWithShock(
                discountCurveInfo.curveData,
                discountCurveInfo.shockCurveData,
                days,
                discountCurveInfo.interpolateType);
        if (!StringUtils.isBlank(bondInfo.creditSpreadCurve)
                && md.irSpot != null
                && md.irSpot.containsKey(bondInfo.creditSpreadCurve)) {
            IrSpot.IrSpotInfo creditSpreadInfo = md.irSpot.get(bondInfo.creditSpreadCurve);
            LocalDate csOriginDate = creditSpreadInfo.pDataDate == null ? originDate : creditSpreadInfo.pDataDate;
            int csDays = (int) ChronoUnit.DAYS.between(csOriginDate, date);
            rate += interpolateRateWithShock(
                    creditSpreadInfo.curveData,
                    creditSpreadInfo.shockCurveData,
                    csDays,
                    creditSpreadInfo.interpolateType);
        }
        if (spreadOverYield != null && spreadOverYield != 0.0) {
            rate += spreadOverYield;
        }
        return rate;
    }

    private double interpolateRateWithShock(
            Series<Integer, Double> curveData,
            Series<Integer, Double> shockCurveData,
            int days,
            String interpolateType) {
        double rate = Interpolation.interpolate(curveData, days, interpolateType);
        if (shockCurveData != null && !shockCurveData.isEmpty()) {
            rate += Interpolation.interpolate(shockCurveData, days, "linear");
        }
        return rate;
    }

    /**
     * 仅复制需要 shock 的利率曲线，构造风险指标使用的局部替换市场。
     */
    private MarketData buildRiskShockMarketData(MarketData baseMarketData, double shift) {
        HashMap<String, IrSpot.IrSpotInfo> replacements = new HashMap<>();
        for (String curveId : collectRiskShockCurveIds()) {
            if (curveId == null || curveId.trim().isEmpty()) {
                continue;
            }
            IrSpot.IrSpotInfo baseCurve = baseMarketData.irSpot.get(curveId);
            if (baseCurve == null) {
                continue;
            }
            IrSpot.IrSpotInfo shockedCurve = CommUtils.deepCopy(baseCurve);
            shockedCurve.shift(shift);
            replacements.put(curveId, shockedCurve);
        }
        return replaceIrCurves(baseMarketData, replacements);
    }

    /**
     * 收集利率风险指标需要同时冲击的曲线集合。
     * 浮息债统一同时冲击折现曲线和定盘曲线。
     */
    private Set<String> collectRiskShockCurveIds() {
        Set<String> curveIds = new LinkedHashSet<>();
        curveIds.add(bondInfo.discountCurve);
        if (!"Fixed".equalsIgnoreCase(bondInfo.interestType)
                && marketData.irSpot != null
                && marketData.irSpot.containsKey(bondInfo.referenceCurve)) {
            curveIds.add(bondInfo.referenceCurve);
        }
        curveIds.removeIf(item -> item == null || item.trim().isEmpty());
        return curveIds;
    }

    private void appendScfWarnings(BondMeasure measure) {
        if (measure == null || scf == null) {
            return;
        }
        for (String warning : scf.getWarnings()) {
            measure.addWarningLog(warning);
        }
    }

    private void appendCreditSpreadCurveWarnings(BondMeasure measure) {
        if (measure == null) {
            return;
        }
        if (StringUtils.isBlank(bondInfo.creditSpreadCurve)) {
            measure.addWarningLog("CREDIT_SPREAD_CURVE为空，债券估值仅使用折现曲线: "
                    + bondInfo.discountCurve);
            return;
        }
        if (marketData.irSpot != null && !marketData.irSpot.containsKey(bondInfo.creditSpreadCurve)) {
            measure.addWarningLog("CREDIT_SPREAD_CURVE=" + bondInfo.creditSpreadCurve
                    + " 在市场数据中不存在，债券估值仅使用折现曲线: " + bondInfo.discountCurve);
        }
    }

    /**
     * 复制顶层市场容器并覆盖指定利率曲线。
     */
    private MarketData replaceIrCurves(MarketData baseMarketData, Map<String, IrSpot.IrSpotInfo> replacements) {
        MarketData marketData = new MarketData();
        marketData.irSpot = new HashMap<>(baseMarketData.irSpot);
        marketData.irVol = new HashMap<>(baseMarketData.irVol);
        marketData.eqSpot = new HashMap<>(baseMarketData.eqSpot);
        marketData.eqVol = new HashMap<>(baseMarketData.eqVol);
        marketData.commSpot = new HashMap<>(baseMarketData.commSpot);
        marketData.commVol = new HashMap<>(baseMarketData.commVol);
        marketData.fxVol = new HashMap<>(baseMarketData.fxVol);
        marketData.fixingRate = new HashMap<>(baseMarketData.fixingRate);
        marketData.fxSpot = baseMarketData.fxSpot;
        if (replacements != null && !replacements.isEmpty()) {
            marketData.irSpot.putAll(replacements);
        }
        return marketData;
    }

    // ==================== 含权债相关方法 ====================

    /**
     * 判断当前债券是否为有效的含权债（标志位为 true 且有可用的行权日期）
     */
    public boolean hasValidCallPutDates() {
        return bondInfo.optionBondFlag
                && bondInfo.callPutDates != null
                && !bondInfo.callPutDates.isEmpty();
    }

    /**
     * 含权债到期日解析：处理行权日期列表，选取最优到期日并覆盖 bondInfo.maturityDate。
     * callPutDates 为空时保持原到期日不变（视为普通债券）。
     */
    private void resolveCallPutMaturity() {
        if (bondInfo.callPutDates == null || bondInfo.callPutDates.isEmpty()) {
            return;
        }
        // 将到期日作为兜底行权日加入列表，类型取首个日期的类型
        String defaultType = bondInfo.callPutDates.get(0).type;
        boolean maturityExists = bondInfo.callPutDates.stream()
                .anyMatch(cpd -> cpd.date != null && cpd.date.equals(bondInfo.maturityDate));
        if (!maturityExists) {
            CallPutDate m = new CallPutDate();
            m.date = bondInfo.maturityDate;
            m.type = defaultType;
            bondInfo.callPutDates.add(m);
        }
        // 过滤已过期的行权日
        bondInfo.callPutDates.removeIf(cpd -> cpd.date == null || cpd.date.isBefore(dataDate));
        if (bondInfo.callPutDates.isEmpty())
            return;

        String selectedDate = pickMaturityDate(CommUtils.deepCopy(this.marketData));
        bondInfo.maturityDate = LocalDate.parse(selectedDate);

        // 重建 SCF（maturityDate 已变化，现金流需重新生成）
        StructuredCashflow.ScfInfo scfInfo = ReflectionUtils.bean2Bean(bondInfo, StructuredCashflow.ScfInfo.class);
        scfInfo.paymentTiming = "arrear";
        scfInfo.notionalFlag = "END";
        scfInfo.fixingCalendar = bondInfo.settleCalendar;
        scfInfo.allowMissingReferenceCurveAsZeroForward = false;
        scf = new StructuredCashflow(dataDate, scfInfo, marketData, cal);
    }

    /**
     * 根据市场数据和各行权日的类型，选出最优到期日。
     * Call 日期取最大估值（发行人最可能赎回），Put 日期取最小估值（投资者最可能回售）。
     * 同时存在 Call 和 Put 时，按时间先后决定谁先行权。
     *
     * @param marketData 市场数据
     * @return 选中的到期日字符串
     */
    public String pickMaturityDate(MarketData marketData) {
        Map<CallPutDate, Double> pvMap = new LinkedHashMap<>();
        for (CallPutDate cpd : bondInfo.callPutDates) {
            if (cpd.date == null || !cpd.date.isAfter(dataDate))
                continue;
            StructuredCashflow.ScfInfo scfInfo = ReflectionUtils.bean2Bean(bondInfo,
                    StructuredCashflow.ScfInfo.class);
            scfInfo.maturityDate = cpd.date;
            scfInfo.paymentTiming = "arrear";
            scfInfo.notionalFlag = "END";
            scfInfo.fixingCalendar = bondInfo.settleCalendar;
            scfInfo.allowMissingReferenceCurveAsZeroForward = false;
            StructuredCashflow tmpScf = new StructuredCashflow(dataDate, scfInfo, marketData, cal);
            List<StructuredCashflow.Cashflow> cashflows = tmpScf.calc().cashFlowList;
            double pv = calculatePresentValue(marketData, cashflows);
            pvMap.put(cpd, pv);
        }

        // Call 是发行人权利，选择持有人价值更低的日期
        CallPutDate bestCall = null;
        double bestCallPv = Double.POSITIVE_INFINITY;
        // Put 是投资者权利，选择持有人价值更高的日期
        CallPutDate bestPut = null;
        double bestPutPv = Double.NEGATIVE_INFINITY;

        for (Map.Entry<CallPutDate, Double> entry : pvMap.entrySet()) {
            CallPutDate cpd = entry.getKey();
            double pv = entry.getValue();
            if ("Call".equalsIgnoreCase(cpd.type)) {
                if (pv < bestCallPv) {
                    bestCallPv = pv;
                    bestCall = cpd;
                }
            } else {
                if (pv > bestPutPv) {
                    bestPutPv = pv;
                    bestPut = cpd;
                }
            }
        }

        // 过滤后无有效日期，回退到到期日
        if (bestCall == null && bestPut == null)
            return bondInfo.maturityDate.toString();
        if (bestCall == null)
            return bestPut.date.toString();
        if (bestPut == null)
            return bestCall.date.toString();
        // 两种都有时，时间更早的行权日优先
        return bestCall.date.isBefore(bestPut.date)
                ? bestCall.date.toString()
                : bestPut.date.toString();
    }

    /**
     * 使用冲击后的市场数据重新选取最优行权日并估值。
     * 用于 GIRR Curvature 和压力测试等需要重新评估行权决策的场景。
     * SOY 保持固定不重新校准。
     *
     * @param scenarioMd 冲击后的市场数据
     * @return 重选行权日后的估值结果
     */
    public BondMeasure calcWithReselectMaturity(MarketData scenarioMd) {
        String selectedDate = pickMaturityDate(scenarioMd);
        // 临时构建一个新 Bond 实例，避免修改当前实例状态
        BondTradeInfo tmpInfo = ReflectionUtils.bean2Bean(bondInfo, BondTradeInfo.class);
        tmpInfo.maturityDate = LocalDate.parse(selectedDate);
        Bond tmpBond = new Bond(dataDate, tmpInfo, marketData, cal);
        tmpBond.spreadOverYield = this.spreadOverYield;
        return tmpBond.calc(scenarioMd);
    }

    @Override
    public double jtd() {
        double units = bondInfo.positionTrade;
        // LGD 默认值常量化，且不修改 bondInfo 状态
        double lgd = bondInfo.lgd;
        double jtd;
        if (bondInfo.absFlag) {
            jtd = bondMeasure.valuation;
        } else {
            jtd = units > 0
                    ? Math.max(lgd * bondInfo.notional * units + bondMeasure.valuation - bondInfo.notional * units, 0)
                    : Math.min(lgd * bondInfo.notional * units + bondMeasure.valuation - bondInfo.notional * units, 0);
        }
        return jtd;
    }

    // 债券内部类，封装计量指标
    static public class BondMeasure extends Measure {
        @JSONField(name = "SPREAD_OVER_YIELD")
        public double spreadOverYield;
        @JSONField(name = "EFFECTIVE_DURATION")
        public double effectiveDuration;
        @JSONField(name = "EFFECTIVE_CONVEXITY")
        public double effectiveConvexity;
        @JSONField(name = "ACCRUED_INTEREST")
        public double accruedInterest;
        @JSONField(name = "DRC")
        public DrcDetail drcDetail;

        @Override
        public String toString() {
            return "BondMeasure{" +
                    "instrumentId='" + instrumentId + '\'' +
                    ", value=" + valuation +
                    ", valueCny=" + valuationCny +
                    ", spreadOverYield=" + spreadOverYield +
                    '}';
        }
    }

    private void validateInputs(MarketData md) {
        if (bondInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        if (dataDate == null) {
            throw new IllegalArgumentException("数据日期为空");
        }
        if (cal == null) {
            throw new IllegalArgumentException("交易日历为空");
        }
        requireText(bondInfo.productCode, "PRODUCT_CODE");
        requireText(bondInfo.instrumentId, "INSTRUMENT_ID");
        requireText(bondInfo.bondId, "BOND_ID");
        requireCurrencyCode(bondInfo.currencyCode, "CURRENCY_CODE");
        if (bondInfo.issueDate == null) {
            throw new IllegalArgumentException("ISSUE_DATE 不能为空");
        }
        if (bondInfo.maturityDate == null) {
            throw new IllegalArgumentException("MATURITY_DATE 不能为空");
        }
        requireText(bondInfo.interestStub, "INTEREST_STUB");
        if (!"FIXED".equalsIgnoreCase(bondInfo.interestType)
                && !"FLOATING".equalsIgnoreCase(bondInfo.interestType)) {
            throw new IllegalArgumentException("INTEREST_TYPE 仅支持 FIXED/FLOATING: " + bondInfo.interestType);
        }
        requireText(bondInfo.payFreq, "PAY_FREQ");
        requireText(bondInfo.dayCountBasis, "DAY_COUNT_BASIS");
        requireText(bondInfo.discountCurve, "DISCOUNT_CURVE");
        requireNonNegativeFinite(bondInfo.notional, "NOTIONAL");
        requireFinite(bondInfo.positionTrade, "POSITION_TRADE");
        requireFinite(bondInfo.spread, "SPREAD");
        requireFiniteIfPresent(bondInfo.dirtyPrice, "DIRTY_PRICE");
        requireFiniteIfPresent(bondInfo.lastResetRate, "LAST_RESET_RATE");
        requireRange(bondInfo.lgd, "DRC_LGD", 0.0, 1.0);
        bondInfo.isDrcEnabled();
        if ("FIXED".equalsIgnoreCase(bondInfo.interestType)) {
            requireFinite(bondInfo.interestRate, "INTEREST_RATE");
        } else {
            requireText(bondInfo.referenceCurve, "REFERENCE_CURVE");
            requireText(bondInfo.resetFreq, "RESET_FREQ");
            requireText(bondInfo.fixingFreq, "FIXING_FREQ");
        }
        validateCallPutDates();
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空");
        }
        if (md.irSpot == null || md.irSpot.get(bondInfo.discountCurve) == null) {
            throw new IllegalArgumentException("折现曲线不存在: " + bondInfo.discountCurve);
        }
        if ("FLOATING".equalsIgnoreCase(bondInfo.interestType)
                && md.irSpot.get(bondInfo.referenceCurve) == null) {
            throw new IllegalArgumentException("参考曲线不存在: " + bondInfo.referenceCurve);
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少外汇即期曲线");
        }
    }

    private void validateCallPutDates() {
        if (bondInfo.optionBondFlag && (bondInfo.callPutDates == null || bondInfo.callPutDates.isEmpty())) {
            throw new IllegalArgumentException("含权债必须提供 CALLPUT_DATES");
        }
        if (bondInfo.callPutDates == null) {
            return;
        }
        for (CallPutDate item : bondInfo.callPutDates) {
            if (item == null || item.date == null) {
                throw new IllegalArgumentException("CALLPUT_DATES.DATE 不能为空");
            }
            if (!"CALL".equalsIgnoreCase(item.type) && !"PUT".equalsIgnoreCase(item.type)) {
                throw new IllegalArgumentException("CALLPUT_DATES.TYPE 仅支持 CALL/PUT: "
                        + (item == null ? null : item.type));
            }
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

    private static void requireRange(Double value, String field, double min, double max) {
        if (value == null || !Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(field + " 必须在[" + min + "," + max + "]范围内: " + value);
        }
    }

    // 债券内部类，封装基本信息
    static public class BondTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate dataDate;
        @ProductInputField(required = true)
        @JSONField(name = "BOND_ID")
        public String bondId;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "ISSUER")
        public String issuer;
        @ProductInputField(required = true)
        @JSONField(name = "ISSUE_DATE", format = "yyyy-MM-dd")
        public LocalDate issueDate;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "INTEREST_STUB")
        public String interestStub;
        @ProductInputField(required = true, allowedValues = {"FIXED", "FLOATING"}, ignoreCase = true)
        @JSONField(name = "INTEREST_TYPE")
        public String interestType;
        @ProductInputField(finite = true)
        @JSONField(name = "INTEREST_RATE")
        public Double interestRate;
        @ProductInputField(required = true)
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @ProductInputField
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @ProductInputField(finite = true)
        @JSONField(name = "SPREAD")
        public Double spread = 0.0;
        @ProductInputField
        @JSONField(name = "FIXING_FREQ")
        public String fixingFreq;
        @ProductInputField(required = true)
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";
        @JSONField(name = "SETTLE_CALENDAR")
        public String settleCalendar;
        @JSONField(name = "SETTLE_RULE")
        public String settleRule;
        @JSONField(name = "SETTLE_DAYOFF")
        public Integer settleDayoff;
        @JSONField(name = "RESET_RULE")
        public String resetRule;
        @JSONField(name = "RESET_DAYOFF")
        public Integer resetDayoff;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "CREDIT_SPREAD_CURVE")
        public String creditSpreadCurve;
        @JSONField(name = "ABS_FLAG", deserializeUsing = BooleanInputReader.class)
        public boolean absFlag = false;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional = 100.0;
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "POSITION_TRADE")
        public Double positionTrade = 1.0;
        @ProductInputField(finite = true)
        @JSONField(name = "DIRTY_PRICE")
        public Double dirtyPrice;
        @JSONField(name = "ISSUER_RATING")
        public String issuerRating;
        @ProductInputField
        @JSONField(name = "RESET_FREQ")
        public String resetFreq;
        @ProductInputField(required = true, finite = true, min = "0", max = "1")
        @JSONField(name = "DRC_LGD")
        public Double lgd = 0.75;
        @JSONField(name = "DRC_FLAG", deserializeUsing = BooleanInputReader.class)
        public Boolean drcFlag = true;

        @ProductInputField(finite = true)
        @JSONField(name = "LAST_RESET_RATE")
        public Double lastResetRate;

        @JSONField(name = "FRTB_CSR_BUCKET")
        public String frtbCsrBucket;
        @JSONField(name = "FRTB_SECNCTP_DRC_TYPE")
        public String frtbSecnctpDrcType;
        @JSONField(name = "FRTB_SECNCTP_DRC_RW")
        public Double frtbSecnctpDrcRw;
        @JSONField(name = "FRTB_NSEC_DRC_BUCKET")
        public String frtbNsecDrcBucket;

        @JSONField(name = "INCLUDE_TODAY_CASHFLOW", deserializeUsing = BooleanInputReader.class)
        public Boolean includeTodayCashflow = true;
        @JSONField(name = "COUPON_PRORATED", deserializeUsing = BooleanInputReader.class)
        public Boolean couponProrated = true;
        @JSONField(name = "OPTION_BOND_FLAG", deserializeUsing = BooleanInputReader.class)
        public boolean optionBondFlag = false;

        @ProductInputField
        @JSONField(name = "CALLPUT_DATES")
        public List<CallPutDate> callPutDates;

        @ProductInputField
        @JSONField(name = "AMORTIZATION_SCHEDULE")
        public List<StructuredCashflow.AmortizationEntry> amortizationSchedule;

        public boolean isDrcEnabled() {
            return Boolean.TRUE.equals(drcFlag);
        }
    }

    /**
     * 行权日期，每个日期独立标注 Call 或 Put 类型
     */
    public static class CallPutDate {
        @ProductInputField(required = true)
        @JSONField(name = "DATE", format = "yyyy-MM-dd")
        public LocalDate date;
        @ProductInputField(required = true, allowedValues = {"CALL", "PUT"}, ignoreCase = true)
        @JSONField(name = "TYPE")
        public String type; /* "Call" 或 "Put" */
    }
}

