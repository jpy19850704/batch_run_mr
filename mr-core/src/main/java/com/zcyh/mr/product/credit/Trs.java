
package com.zcyh.mr.product.credit;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.product.basic.frtb.FrtbDrcInterface;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.ReflectionUtils;
import com.zcyh.mr.calc.FrtbCalcControl;
import com.zcyh.mr.calendar.Calendar;
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

/**
 * 总收益互换（TRS）估值类
 * 支持跨币种结构：融资腿与标的债券腿可使用不同币种名义本金
 */
public class Trs implements FrtbDrcInterface {

    /** 违约损失率默认值 */
    private static final double DEFAULT_LGD = 0.75;

    private LocalDate dataDate;
    private MarketData marketData;
    private TrsTradeInfo trsInfo;
    private JSONObject udData;
    private Calendar cal;

    public Trs(LocalDate dataDate, TrsTradeInfo trsInfo, MarketData marketData, Calendar calendar, JSONObject udData) {
        this.dataDate = dataDate;
        this.trsInfo = trsInfo;
        this.cal = calendar;
        this.udData = TradeJsonUtil.mergeTrade(udData, EngineConstants.PRODUCT_CODE.TRS, "UNDERLYING_DATA");
        this.marketData = marketData;
    }

    public TrsMeasure calc() {
        PricingOutcome outcome = price(marketData);
        TrsMeasure result = outcome.measure;

        if (!isSuccess(result)) {
            result.pv01 = 0.0;
            result.drcDetail = null;
            result.sensitivityList = new ArrayList<>();
            result.cashFlowList = null;
        } else {
            // 无有效标的信息时直接返回空敏感度与空DRC
            if (outcome.bondInfo == null) {
                result.sensitivityList = new ArrayList<>();
                result.pv01 = 0.0;
                result.drcDetail = null;
            } else {
                result.sensitivityList = FrtbCalcControl.isSensitivityEnabled()
                        ? getFrtbSensitivity(result, outcome.bondInfo,
                                outcome.effectiveCreditSpreadCurve, outcome.effectiveAssetDiscountCurve)
                        : new ArrayList<>();
                result.pv01 = pv01(marketData, result.valuation);
                result.drcDetail = FrtbCalcControl.isDrcEnabled()
                        ? buildDrc(outcome.bondInfo, result.valuation, outcome.underlyingNotional)
                        : null;
            }
        }
        return result;
    }

    public TrsMeasure calc(MarketData marketData) {
        return price(marketData).measure;
    }

    private PricingOutcome price(MarketData marketData) {
        PricingOutcome outcome = new PricingOutcome();
        TrsMeasure trsMeasure = initMeasure();
        outcome.measure = trsMeasure;

        String inputError = validateInput();
        if (inputError != null) {
            return failOutcome(outcome, inputError);
        }

        // TRS到期后按0返回，避免产生空JTD
        if (trsInfo.maturityDate != null && !trsInfo.maturityDate.isAfter(dataDate)) {
            trsMeasure.valuation = 0.0;
            trsMeasure.valuationCny = 0.0;
            trsMeasure.cashFlowList = new ArrayList<>();
            return outcome;
        }

        JSONObject underlyingData = (JSONObject) this.udData.get(trsInfo.underlyingBondId);
        if (underlyingData == null) {
            return failOutcome(outcome,
                    "标的债券数据不存在: INSTRUMENT_ID=" + trsInfo.instrumentId
                            + ", UNDERLYING_BOND_ID=" + trsInfo.underlyingBondId);
        }

        try {
            Bond.BondTradeInfo bondInfo = JSON.parseObject(underlyingData.toString(), Bond.BondTradeInfo.class);

            // TRS 信用利差曲线与 CDS 保持一致：只使用底层债券信用利差曲线。
            String effectiveCreditSpreadCurve = bondInfo.creditSpreadCurve;

            // 标的无风险折现曲线必须由 TRS 交易字段显式提供，不回退到底层债券折现曲线。
            String effectiveAssetDiscountCurve = trsInfo.underlyingCurrencyDiscountCurve;

            // 融资折现曲线
            String fundingCurve = trsInfo.discountCurve;
            double underlyingNotional = trsInfo.underlyingNotional;

            // 曲线存在性校验
            if (marketData.irSpot == null || !marketData.irSpot.containsKey(fundingCurve)) {
                throw new IllegalArgumentException("融资折现曲线不存在: " + fundingCurve
                        + " (INSTRUMENT_ID=" + trsInfo.instrumentId + ")");
            }
            if (StringUtils.isBlank(effectiveAssetDiscountCurve)
                    || !marketData.irSpot.containsKey(effectiveAssetDiscountCurve)) {
                throw new IllegalArgumentException("标的折现曲线不存在: " + effectiveAssetDiscountCurve
                        + " (INSTRUMENT_ID=" + trsInfo.instrumentId + ")");
            }
            if (StringUtils.isBlank(effectiveCreditSpreadCurve)
                    || !marketData.irSpot.containsKey(effectiveCreditSpreadCurve)) {
                throw new IllegalArgumentException("信用利差曲线不存在: " + effectiveCreditSpreadCurve
                        + " (INSTRUMENT_ID=" + trsInfo.instrumentId + ")");
            }

            // 构建折现曲线对象
            IrSpot.IrSpotInfo fundCurveInfo = CommUtils.deepCopy(marketData.irSpot.get(fundingCurve));
            IrSpot.IrSpotInfo assetCurveInfo = CommUtils.deepCopy(marketData.irSpot.get(effectiveAssetDiscountCurve));
            IrSpot.IrSpotInfo creditSpreadInfo = CommUtils.deepCopy(marketData.irSpot.get(effectiveCreditSpreadCurve));

            IrSpot irSpotFund = new IrSpot(fundCurveInfo);
            IrSpot irSpotAsset = new IrSpot(assetCurveInfo);
            IrSpot creditSpreadSpot = new IrSpot(creditSpreadInfo);

            // 债券腿现金流（coupon + principal）
            Bond.BondTradeInfo bondCopy = ReflectionUtils.bean2Bean(bondInfo, Bond.BondTradeInfo.class);
            bondCopy.notional = underlyingNotional;
            bondCopy.includeTodayCashflow = true;
            StructuredCashflow.ScfInfo bondLocalScfInfo = ReflectionUtils.bean2Bean(bondCopy, StructuredCashflow.ScfInfo.class);
            bondLocalScfInfo.fixingCalendar = bondLocalScfInfo.settleCalendar;
            StructuredCashflow bondScf = new StructuredCashflow(dataDate, bondLocalScfInfo, marketData, cal);
            bondScf.calc(marketData);
            appendScfWarnings(trsMeasure, "债券腿", bondScf);
            LinkedList<StructuredCashflow.Cashflow> bondCashflows = bondScf.getCashflowList();

            // 融资腿现金流
            StructuredCashflow.ScfInfo fundingScfInfo = buildFundingScfInfo();
            StructuredCashflow fundingScf = new StructuredCashflow(dataDate, fundingScfInfo, marketData, cal);
            fundingScf.calc(marketData);
            appendScfWarnings(trsMeasure, "融资腿", fundingScf);
            LinkedList<StructuredCashflow.Cashflow> fundingCashflows = fundingScf.getCashflowList();

            // FX汇率：标的币种 → 融资币种（CURRENCY_CODE）
            FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE),
                    marketData.fxSpot);
            double fxRateUnderlyingToBase = fxSpot.getFxrate(trsInfo.underlyingCurrencyCode);
            double fxRateFundingToBase = fxSpot.getFxrate(trsInfo.currencyCode);
            if (Math.abs(fxRateFundingToBase) <= 1e-15) {
                throw new IllegalArgumentException("融资币种汇率非法: " + trsInfo.currencyCode
                        + " (INSTRUMENT_ID=" + trsInfo.instrumentId + ")");
            }
            double fxRateUnderlyingToFunding = fxRateUnderlyingToBase / fxRateFundingToBase;

            // 计算债券腿PV（融资币种）
            List<TrsCashFlow> bondLegCfs = buildBondLegCashFlows(
                    bondCashflows, irSpotFund, irSpotAsset, creditSpreadSpot, fxRateUnderlyingToFunding, underlyingNotional);

            // 计算融资腿PV（融资币种）
            List<TrsCashFlow> fundingLegCfs = buildFundingLegCashFlows(fundingCashflows, irSpotFund);

            // 买方（B）= 收到债券总收益，支付融资；卖方（S）相反
            int sign = "B".equalsIgnoreCase(trsInfo.buyOrSell) ? 1 : -1;

            double bondLegPv = bondLegCfs.stream().mapToDouble(cf -> cf.pv).sum();
            double fundingLegPv = fundingLegCfs.stream().mapToDouble(cf -> cf.pv).sum();

            trsMeasure.valuation = sign * (bondLegPv - fundingLegPv);

            if (!Double.isFinite(trsMeasure.valuation)) {
                return failOutcome(outcome,
                        "TRS估值结果非法(非有限数): INSTRUMENT_ID=" + trsInfo.instrumentId);
            }

            // valuationCcy = trsInfo.currencyCode（融资币种，通常为CNY）
            trsMeasure.valuationCny = trsMeasure.valuation * fxSpot.getFxrate(trsInfo.currencyCode);

            if (!Double.isFinite(trsMeasure.valuationCny)) {
                return failOutcome(outcome,
                        "TRS估值本币折算结果非法(非有限数): INSTRUMENT_ID=" + trsInfo.instrumentId);
            }

            trsMeasure.cashFlowList = buildTrsCashFlowList(bondLegCfs, fundingLegCfs, underlyingNotional);
            outcome.bondInfo = bondInfo;
            outcome.effectiveCreditSpreadCurve = effectiveCreditSpreadCurve;
            outcome.effectiveAssetDiscountCurve = effectiveAssetDiscountCurve;
            outcome.underlyingNotional = underlyingNotional;
            return outcome;

        } catch (IllegalArgumentException e) {
            return failOutcome(outcome, e.getMessage());
        }
    }

    /**
     * 构建债券腿现金流列表，使用跨币种折现因子 D_xccy = D_fund² / D_asset，以融资币种计价。
     */
    private List<TrsCashFlow> buildBondLegCashFlows(
            LinkedList<StructuredCashflow.Cashflow> bondCashflows,
            IrSpot irSpotFund, IrSpot irSpotAsset, IrSpot creditSpreadSpot,
            double fxRateUnderlyingToFunding,
            double underlyingNotional) {

        List<TrsCashFlow> result = new ArrayList<>();
        double prevQ = 0.0;

        for (StructuredCashflow.Cashflow cashflow : bondCashflows) {
            if (!cashflow.paymentDate.isAfter(dataDate)) {
                continue;
            }
            double dFund = irSpotFund.discount(cashflow.paymentDate);
            double dAsset = irSpotAsset.discount(cashflow.paymentDate);
            double dAssetCredit = combinedDiscount(irSpotAsset, creditSpreadSpot, cashflow.paymentDate);

            // 跨币种折现因子 D_xccy(t) = D_fund(t)^2 / D_asset(t)
            double dXccy = (dAsset > 1e-15) ? dFund * dFund / dAsset : 0.0;

            // 累计违约概率 q(t) = (1 - D_asset_credit/D_asset) / (1-RR)
            // 约束在 [0, 1]：q > 1 代表信用利差极高（实际已视为必然违约），
            // 若不封顶则 prevQ 超过 1，后续 marginalQ 仍正，回收总额超名义本金，经济含义错误
            double q = (dAsset > 1e-15 && trsInfo.recoveryRate < 1.0 - 1e-10)
                    ? Math.min(1.0, Math.max(0.0, (1.0 - dAssetCredit / dAsset) / (1.0 - trsInfo.recoveryRate)))
                    : 0.0;
            // 生存概率 Q(t) = 1 - q(t)，恒在 [0, 1]
            double Q = 1.0 - q;

            // 边际违约概率：q 已封顶，此处保护性 max(0) 防止曲线数据倒挂
            double marginalQ = Math.max(0.0, q - prevQ);
            prevQ = q;

            // 存活加权现金流PV + 违约回收PV（均转换为融资币种）
            double survivalPv = cashflow.cf * Q * dXccy * fxRateUnderlyingToFunding;
            double recoveryPv = trsInfo.recoveryRate * underlyingNotional * marginalQ * dXccy * fxRateUnderlyingToFunding;

            TrsCashFlow legCf = new TrsCashFlow();
            legCf.paymentDate = cashflow.paymentDate;
            legCf.cashflow = cashflow.cf * fxRateUnderlyingToFunding;
            legCf.discountFactor = dXccy;
            legCf.survivalProb = Q;
            legCf.pv = survivalPv + recoveryPv;
            legCf.cashflowType = "bond_" + cashflow.cashType;
            result.add(legCf);
        }
        return result;
    }

    private double combinedDiscount(IrSpot discountCurve, IrSpot creditSpreadCurve, LocalDate date) {
        IrSpot.IrSpotInfo discountInfo = discountCurve.getIrSpotInfo();
        double rate = discountCurve.spotRate(date) + creditSpreadCurve.spotRate(date);
        return CurveFunc.discountFactor(discountInfo.pDataDate, date, rate, discountInfo.freq, discountInfo.dayCount);
    }

    /**
     * 构建融资腿现金流列表，使用融资折现曲线 D_fund，以融资币种计价。
     */
    private List<TrsCashFlow> buildFundingLegCashFlows(
            LinkedList<StructuredCashflow.Cashflow> fundingCashflows,
            IrSpot irSpotFund) {

        List<TrsCashFlow> result = new ArrayList<>();
        for (StructuredCashflow.Cashflow cashflow : fundingCashflows) {
            if (!"interest".equalsIgnoreCase(cashflow.cashType)) {
                continue;
            }
            if (!cashflow.paymentDate.isAfter(dataDate)) {
                continue;
            }
            double dFund = irSpotFund.discount(cashflow.paymentDate);

            TrsCashFlow legCf = new TrsCashFlow();
            legCf.paymentDate = cashflow.paymentDate;
            legCf.cashflow = cashflow.cf;
            legCf.discountFactor = dFund;
            legCf.pv = cashflow.cf * dFund;
            legCf.cashflowType = "funding_interest";
            result.add(legCf);
        }
        return result;
    }

    /**
     * 构建融资腿 SCF 配置，使用 TrsTradeInfo 中的融资腿字段。
     */
    private StructuredCashflow.ScfInfo buildFundingScfInfo() {
        StructuredCashflow.ScfInfo scfInfo = new StructuredCashflow.ScfInfo();
        scfInfo.issueDate = trsInfo.startDate;
        scfInfo.maturityDate = trsInfo.maturityDate;
        scfInfo.notional = trsInfo.notional;
        scfInfo.currencyCode = trsInfo.currencyCode;
        scfInfo.interestType = trsInfo.interestType;
        scfInfo.interestRate = trsInfo.interestRate;
        scfInfo.referenceCurve = trsInfo.referenceCurve;
        scfInfo.payFreq = trsInfo.payFreq;
        scfInfo.dayCountBasis = trsInfo.dayCountBasis;
        scfInfo.interestStub = trsInfo.interestStub;
        scfInfo.settleCalendar = trsInfo.settleCalendar;
        scfInfo.fixingCalendar = trsInfo.settleCalendar;
        return scfInfo;
    }

    private double pv01(MarketData marketData, double baseValuation) {
        MarketData shockedMd = buildShiftedIrMarketData(marketData, trsInfo.discountCurve, 0.0001);
        TrsMeasure shockedMeasure = calc(shockedMd);
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

    private List<FrtbSenes> getFrtbSensitivity(TrsMeasure measure, Bond.BondTradeInfo bondInfo,
            String effectiveCreditSpreadCurve, String effectiveAssetDiscountCurve) {
        List<FrtbSenes> list = new ArrayList<>();
        MeasureValuation baseValuation = toMeasureValuation(measure);

        // FX敏感性：仅跨币种时计算标的债券的FX敞口
        boolean isCrossCurrency = !StringUtils.equalsIgnoreCase(trsInfo.currencyCode, trsInfo.underlyingCurrencyCode);
        if (isCrossCurrency) {
            List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                    Collections.singletonList(trsInfo.underlyingCurrencyCode));
            List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                    marketData, dataDate, dataDate, fxDeltaDependencies,
                    Collections.emptyList(), true, false,
                    trsInfo.instrumentId, trsInfo.currencyCode, 1e-12,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(calc(shockedMarketData)));
            list.addAll(fxSensitivities);
        }

        // GIRR敏感性：融资折现曲线（融资币种bucket）+ 标的折现曲线（标的币种bucket）
        HashMap<String, String> girrMap = new HashMap<>();
        girrMap.put(trsInfo.discountCurve, trsInfo.currencyCode);

        // 跨币种折现曲线退化检查：
        // 跨币种且两条曲线代码相同时，D_xccy shock方向仍正确，但bucket归属存在歧义，
        // 输出WARN并复用融资币种bucket（girrMap已有该条目，无需重复添加）
        if (isCrossCurrency && effectiveAssetDiscountCurve.equals(trsInfo.discountCurve)) {
            measure.addWarningLog("TRS跨币种折现曲线退化：标的折现曲线(" + effectiveAssetDiscountCurve
                    + ")与融资折现曲线(" + trsInfo.discountCurve + ")相同，"
                    + "GIRR bucket归入融资币种(" + trsInfo.currencyCode + ") "
                    + "(INSTRUMENT_ID=" + trsInfo.instrumentId + ")");
        } else {
            girrMap.put(effectiveAssetDiscountCurve, trsInfo.underlyingCurrencyCode);
        }

        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(girrMap);
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData, dataDate, null, girrDeltaDependencies,
                Collections.emptyList(), true, false,
                trsInfo.instrumentId, trsInfo.currencyCode, 1e-12,
                baseValuation,
                shockedMarketData -> toMeasureValuation(calc(shockedMarketData)),
                null, null);
        list.addAll(girrSensitivities);

        // CSR敏感性：信用利差曲线，outputType="TRS"
        if (bondInfo != null && StringUtils.isNotBlank(effectiveCreditSpreadCurve)
                && StringUtils.isNotBlank(bondInfo.issuer)
                && StringUtils.isNotBlank(bondInfo.frtbCsrBucket)) {
            List<FrtbDependency> csrDependencies = bondInfo.absFlag
                    ? FrtbSensitivityBuilder.buildCsrSecNonCtpDeltaDependencies(
                            effectiveCreditSpreadCurve, bondInfo.issuer, bondInfo.frtbCsrBucket, "TRS")
                    : FrtbSensitivityBuilder.buildCsrNonSecDeltaDependencies(
                            effectiveCreditSpreadCurve, bondInfo.issuer, bondInfo.frtbCsrBucket, "TRS");
            List<FrtbSenes> csrSensitivities = FrtbSensitivityBuilder.buildCsrSensitivities(
                    marketData, dataDate, csrDependencies, true, false,
                    trsInfo.instrumentId, trsInfo.currencyCode, 1e-12,
                    baseValuation,
                    shockedMarketData -> toMeasureValuation(calc(shockedMarketData)));
            list.addAll(csrSensitivities);
        }

        list.removeIf(item -> (item.sensitivityValInstCurr == 0 && item.sensitivityValInstCurrCny == 0));
        return list;
    }

    /**
     * 将TRS场景估值结果转换为FRTB builder使用的估值对象。
     */
    private MeasureValuation toMeasureValuation(TrsMeasure measure) {
        if (!isSuccess(measure)) {
            return null;
        }
        return MeasureValuation.of(measure.valuation, measure.valuationCny);
    }

    @Override
    public double jtd() {
        PricingOutcome outcome = price(marketData);
        if (!isSuccess(outcome.measure) || outcome.bondInfo == null) {
            return 0.0;
        }
        return calculateJtd(outcome.bondInfo, outcome.measure.valuation, outcome.underlyingNotional);
    }

    private DrcDetail buildDrc(Bond.BondTradeInfo bondInfo, double valuation, double underlyingNotional) {
        if (!bondInfo.isDrcEnabled()) {
            return null;
        }
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), marketData.fxSpot);
        double fxRateUnderlying = fxSpot.getFxrate(trsInfo.underlyingCurrencyCode);
        double fxRateFunding = fxSpot.getFxrate(trsInfo.currencyCode);
        // JTD基于标的币种名义本金，需将融资币种估值还原到标的币种
        double valuationInUnderlyingCcy = (fxRateUnderlying > 1e-15)
                ? valuation * fxRateFunding / fxRateUnderlying
                : 0.0;

        FrtbDrcInterface.Param param = ReflectionUtils.bean2Bean(bondInfo, FrtbDrcInterface.Param.class);
        param.notional = underlyingNotional;
        double jtd = calculateJtd(bondInfo, valuationInUnderlyingCcy, underlyingNotional);
        DrcDetail drcDetail = ((FrtbDrcInterface) () -> jtd).getDrc(param, dataDate, valuationInUnderlyingCcy);
        drcDetail.jtdCny *= fxRateUnderlying;
        drcDetail.instrumentValue *= fxRateUnderlying;
        return drcDetail;
    }

    private double calculateJtd(Bond.BondTradeInfo bondInfo, double valuationInUnderlyingCcy, double underlyingNotional) {
        double lgd = (bondInfo.lgd != null) ? bondInfo.lgd : DEFAULT_LGD;
        if (bondInfo.absFlag) {
            return "B".equalsIgnoreCase(trsInfo.buyOrSell)
                    ? Math.min(-1 * underlyingNotional + valuationInUnderlyingCcy, 0)
                    : Math.max(underlyingNotional + valuationInUnderlyingCcy, 0);
        }
        return "B".equalsIgnoreCase(trsInfo.buyOrSell)
                ? Math.min(-1 * lgd * underlyingNotional + valuationInUnderlyingCcy, 0)
                : Math.max(lgd * underlyingNotional + valuationInUnderlyingCcy, 0);
    }

    private TrsMeasure initMeasure() {
        TrsMeasure trsMeasure = new TrsMeasure();
        trsMeasure.instrumentId = trsInfo.instrumentId;
        trsMeasure.productCode = trsInfo.productCode;
        trsMeasure.dataDate = dataDate;
        trsMeasure.position = 1.0;
        trsMeasure.valuationCcy = trsInfo.currencyCode;
        trsMeasure.valuation = 0.0;
        trsMeasure.valuationCny = 0.0;
        trsMeasure.valuationUnit = 0.0;
        trsMeasure.status = "SUCCESS";
        trsMeasure.logs = new ArrayList<>();
        trsMeasure.detail = new LinkedHashMap<>();
        trsMeasure.sensitivityList = new ArrayList<>();
        trsMeasure.cashFlowList = new ArrayList<>();
        return trsMeasure;
    }

    private PricingOutcome failOutcome(PricingOutcome outcome, String errorMessage) {
        outcome.measure.status = "ERROR";
        outcome.measure.addErrorLog(errorMessage);
        outcome.measure.cashFlowList = null;
        outcome.measure.detail = null;
        return outcome;
    }

    private boolean isSuccess(TrsMeasure measure) {
        return measure != null && "SUCCESS".equalsIgnoreCase(measure.status);
    }

    private void appendScfWarnings(TrsMeasure measure, String legName, StructuredCashflow scf) {
        if (measure == null || scf == null) {
            return;
        }
        for (String warning : scf.getWarnings()) {
            if (StringUtils.isBlank(warning)) {
                continue;
            }
            measure.addWarningLog(legName + warning + " (INSTRUMENT_ID=" + trsInfo.instrumentId + ")");
        }
    }

    /**
     * 估值输入参数前置校验。
     */
    private String validateInput() {
        if (trsInfo == null) {
            return "交易参数不能为空";
        }
        if (StringUtils.isBlank(trsInfo.instrumentId)) {
            return "INSTRUMENT_ID 不能为空";
        }
        if (!"B".equalsIgnoreCase(trsInfo.buyOrSell) && !"S".equalsIgnoreCase(trsInfo.buyOrSell)) {
            return "BUY_OR_SELL 仅支持 B/S: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (trsInfo.notional == null || !Double.isFinite(trsInfo.notional) || trsInfo.notional < 0.0) {
            return "NOTIONAL 必须为非负有限数: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (trsInfo.recoveryRate == null || trsInfo.recoveryRate < 0.0 || trsInfo.recoveryRate >= 1.0) {
            return "RECOVERY_RATE 必须在 [0,1) 范围: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (!Double.isFinite(trsInfo.recoveryRate)) {
            return "RECOVERY_RATE 必须为有限数: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (trsInfo.startDate == null || trsInfo.maturityDate == null) {
            return "START_DATE / MATURITY_DATE 不能为空: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (trsInfo.maturityDate.isBefore(trsInfo.startDate)) {
            return "MATURITY_DATE 不能早于 START_DATE: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (StringUtils.isBlank(trsInfo.currencyCode)) {
            return "CURRENCY_CODE 不能为空: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (StringUtils.isBlank(trsInfo.underlyingCurrencyCode)) {
            return "UNDERLYING_CURRENCY_CODE 不能为空: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (StringUtils.isBlank(trsInfo.discountCurve)) {
            return "DISCOUNT_CURVE 不能为空: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (StringUtils.isBlank(trsInfo.underlyingCurrencyDiscountCurve)) {
            return "UNDERLYING_CURRENCY_DISCOUNT_CURVE 不能为空: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (StringUtils.isBlank(trsInfo.underlyingBondId)) {
            return "UNDERLYING_BOND_ID 不能为空: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        if (trsInfo.underlyingNotional == null || !Double.isFinite(trsInfo.underlyingNotional)
                || trsInfo.underlyingNotional < 0.0) {
            return "UNDERLYING_NOTIONAL 必须为非负有限数: INSTRUMENT_ID=" + trsInfo.instrumentId;
        }
        return null;
    }

    private List<BaseCashFlow> buildTrsCashFlowList(List<TrsCashFlow> bondLegCfs,
            List<TrsCashFlow> fundingLegCfs,
            double underlyingNotional) {
        List<BaseCashFlow> cashflowList = new ArrayList<>();
        for (TrsCashFlow cf : bondLegCfs) {
            TrsCashFlowOut out = new TrsCashFlowOut();
            out.dataDate = dataDate;
            out.notional = underlyingNotional;
            out.paymentDate = cf.paymentDate;
            out.currencyCode = trsInfo.currencyCode;
            out.cashFlowType = cf.cashflowType;
            out.cashflow = cf.cashflow;
            out.discountFactor = cf.discountFactor;
            out.survivalProb = cf.survivalProb;
            out.pv = cf.pv;
            cashflowList.add(out);
        }
        for (TrsCashFlow cf : fundingLegCfs) {
            TrsCashFlowOut out = new TrsCashFlowOut();
            out.dataDate = dataDate;
            out.notional = trsInfo.notional;
            out.paymentDate = cf.paymentDate;
            out.currencyCode = trsInfo.currencyCode;
            out.cashFlowType = cf.cashflowType;
            out.cashflow = cf.cashflow;
            out.discountFactor = cf.discountFactor;
            out.pv = cf.pv;
            cashflowList.add(out);
        }
        return cashflowList;
    }

    // ===== 内部静态类 =====

    public static class TrsTradeInfo implements TradeInfo {
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
        /** 标的腿名义本金（标的币种） */
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "UNDERLYING_NOTIONAL")
        public Double underlyingNotional;
        /** 融资币种 */
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        /** 标的债券币种 */
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "UNDERLYING_CURRENCY_CODE")
        public String underlyingCurrencyCode;
        /** 融资折现曲线 */
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        /** 标的无风险折现曲线 */
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_CURRENCY_DISCOUNT_CURVE")
        public String underlyingCurrencyDiscountCurve;
        /** 信用利差曲线（保留输入字段，计量口径使用底层债券 creditSpreadCurve） */
        @JSONField(name = "CREDIT_SPREAD_CURVE")
        public String creditSpreadCurve;
        @ProductInputField(required = true)
        @JSONField(name = "UNDERLYING_BOND_ID")
        public String underlyingBondId;
        /** 标的类型，当前支持 BOND */
        @ProductInputField(allowedValues = {"BOND"}, ignoreCase = true)
        @JSONField(name = "UNDERLYING_TYPE")
        public String underlyingType;
        @ProductInputField(required = true, finite = true, min = "0", max = "1", maxInclusive = false)
        @JSONField(name = "RECOVERY_RATE")
        public Double recoveryRate;
        /** 融资腿利率（固息）或利差（浮息） */
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "INTEREST_RATE")
        public Double interestRate;
        /** 融资腿利率类型：fixed/float */
        @ProductInputField(required = true, allowedValues = {"FIXED", "FLOATING"}, ignoreCase = true)
        @JSONField(name = "INTEREST_TYPE")
        public String interestType;
        /** 浮息参考曲线 */
        @ProductInputField
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @ProductInputField(required = true)
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @ProductInputField(required = true)
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";
        @ProductInputField(required = true)
        @JSONField(name = "INTEREST_STUB")
        public String interestStub;
        @JSONField(name = "SETTLE_CALENDAR")
        public String settleCalendar;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
    }

    public static class TrsMeasure extends Measure {
        @JSONField(name = "DRC")
        public DrcDetail drcDetail;
    }

    public static class TrsCashFlowOut extends BaseCashFlow {
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "SURVIVAL_PROB")
        public Double survivalProb;
        @JSONField(name = "PV")
        public Double pv;
    }

    /**
     * 内部中间现金流数据结构
     */
    private static class TrsCashFlow {
        LocalDate paymentDate;
        double cashflow;
        double discountFactor;
        double survivalProb;
        double pv;
        String cashflowType;
    }

    /**
     * 无状态主估值返回对象
     */
    private static class PricingOutcome {
        TrsMeasure measure;
        Bond.BondTradeInfo bondInfo;
        String effectiveCreditSpreadCurve;
        String effectiveAssetDiscountCurve;
        double underlyingNotional;
    }
}
