package com.zcyh.mr.product.ir;

import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.basic.util.ReflectionUtils;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.*;
import com.zcyh.mr.marketdata.*;
import com.zcyh.mr.product.basic.common.ProductInputField;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;
import com.zcyh.mr.product.basic.option.EurOptUtil;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author xujg
 * @date 2024-09-10 09:17
 */
public class CapFloor {
    List<StructuredCashflow.Cashflow> cashflowList;        /*现金流结果 remark:从债券中直接获取，getCashflowList*/
    /*内部公共变量*/
    private final LocalDate dataDate;
    private final MarketData marketData;
    private final Calendar calendar;
    private final CapFloor.CapFloorInfo capFloorInfo;                                 /*入参交易实体类*/
    private CapFloor.CapFloorMeasure measure = new CapFloorMeasure();           /*返回结果类*/
    private StructuredCashflow scf;

    public CapFloor(LocalDate dataDate, CapFloor.CapFloorInfo tradeInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.capFloorInfo = tradeInfo;
        this.marketData = marketData;
        this.calendar = calendar;
        this.cashflowList = new LinkedList<>();
    }


    public CapFloorMeasure calc() {
        validateInputs(marketData);
        StructuredCashflow.ScfInfo scfInfo = ReflectionUtils.bean2Bean(capFloorInfo, StructuredCashflow.ScfInfo.class);
        scfInfo.interestType = "Floating";
        scfInfo.notionalFlag = "NONE";
        scfInfo.resetFreq = StringUtils.isBlank(capFloorInfo.resetFreq) ? capFloorInfo.payFreq : capFloorInfo.resetFreq;
        scfInfo.resetRule = capFloorInfo.fixingRule;
        scfInfo.resetDayoff = capFloorInfo.fixingDayoff;
        scfInfo.issueDate = capFloorInfo.startDate;
		scfInfo.fixingFreq = StringUtils.isBlank(capFloorInfo.fixingFreq) ? capFloorInfo.resetFreq : capFloorInfo.fixingFreq;
        /*判断买卖方向，如果为S则 *-1*/
        scfInfo.notional = getSignedNotional();
        scf = new StructuredCashflow(dataDate,scfInfo,marketData,calendar);

        measure = calc(marketData);
        double shift = 0.0001;
        // 折现曲线变动1bp后的估值
        MarketData newMarketData2 = buildShiftedIrMarketData(marketData, shift);
        CapFloor.CapFloorMeasure newMeasure = calc(newMarketData2);

        MarketData newMarketData3 = buildShiftedIrMarketData(marketData, -shift);
        CapFloor.CapFloorMeasure downMeasure = calc(newMarketData3);
        measure.pv01 = (newMeasure.valuation - downMeasure.valuation) / (2 * shift);
        measure.gamma = (newMeasure.valuation - 2 * measure.valuation + downMeasure.valuation) / (shift * shift);
        measure.productCode = capFloorInfo.productCode;
        measure.dataDate = dataDate;
        measure.position = StringUtils.equalsIgnoreCase("S", capFloorInfo.buyOrSell) ? -1.0 : 1.0;
        measure.valuationCcy = capFloorInfo.currencyCode;
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        measure.detail = buildDetail(measure);
        getFrtbSensList();          /*敏度部分同样指向全局变量返回类*/
        return measure;
    }

    /**
     * 仅复制需要冲击的利率曲线，构造局部替换后的市场数据。
     */
    private MarketData buildShiftedIrMarketData(MarketData baseMarketData, double shift) {
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

        LinkedHashSet<String> curveIds = new LinkedHashSet<>();
        curveIds.add(capFloorInfo.discountCurve);
        curveIds.add(capFloorInfo.referenceCurve);
        curveIds.removeIf(curveId -> StringUtils.isBlank(curveId));
        for (String curveId : curveIds) {
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

    public CapFloorMeasure calc(MarketData marketData){
        validateInputs(marketData);
        List<StructuredCashflow.Cashflow> cashflows = scf.calc(marketData).cashFlowList;
        List<CashFlowCapFloor> cashFlowCapFloors = updateCf(cashflows, marketData);

        Double capValue = cashFlowCapFloors.stream()
                .map(i -> i.discoutFactor * i.cf)
                .reduce(0.0, Double::sum);
        CapFloorMeasure measure = new CapFloorMeasure();
        measure.valuation = capValue;
        measure.instrumentId = capFloorInfo.instrumentId;
        measure.cashFlow = cashFlowCapFloors;
        measure.cashFlowList = buildBaseCashFlowList(cashFlowCapFloors, marketData);
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        measure.valuationCny = capValue * fxSpot.getFxrate(capFloorInfo.currencyCode);/*结果按汇率转换*/
        this.cashflowList = cashflows;
        return measure;
    }

    private List<CashFlowCapFloor> updateCf(List<StructuredCashflow.Cashflow> cashflows, MarketData marketData) {
        List<CashFlowCapFloor> res = new ArrayList<>();
        EurOptUtil eurOptUtil;
        String valuationModel = resolveValuationModel();
        boolean call = "cap".equalsIgnoreCase(capFloorInfo.capOrFloor);
        IrVol.IrVolInfo irVolInfo = marketData.irVol == null ? null : marketData.irVol.get(capFloorInfo.volatilitySurface);
        if (irVolInfo == null) {
            throw new IllegalArgumentException("波动率曲面不存在: " + capFloorInfo.volatilitySurface);
        }
        IrVol irVol = new IrVol(irVolInfo);
        double k = capFloorInfo.strikeRate;
        double signedNotional = getSignedNotional();

        for (StructuredCashflow.Cashflow cashflow : cashflows) {
            if (cashflow == null) {
                throw new IllegalArgumentException("现金流数据为空: instrumentId=" + capFloorInfo.instrumentId);
            }
            if (cashflow.fwdStartDate == null) {
                throw new IllegalArgumentException("现金流远期起始日为空: instrumentId=" + capFloorInfo.instrumentId);
            }
            if (cashflow.prePaymentDate == null || cashflow.paymentDate == null) {
                throw new IllegalArgumentException("现金流计息日期为空: instrumentId=" + capFloorInfo.instrumentId);
            }
            double r = 0;
            double v = 0;
            if (cashflow.fwdStartDate.isBefore(dataDate)){
                if (StringUtils.isBlank(capFloorInfo.fixingId)) {
                    throw new IllegalArgumentException("定盘利率标识为空: instrumentId=" + capFloorInfo.instrumentId);
                }
                Fixing.FixingInfo fixingInfo = marketData.fixingRate == null ? null : marketData.fixingRate.get(capFloorInfo.fixingId);
                if (fixingInfo == null) {
                    throw new IllegalArgumentException("定盘利率数据缺失: fixingId=" + capFloorInfo.fixingId);
                }
                Fixing fixing = new Fixing(fixingInfo);
                double rate = fixing.getRate(cashflow.fwdStartDate);
                r = call ? Math.max((rate - k),0) : Math.max((k - rate),0);
            }else {
                double s = cashflow.rate;      /* 当期远期利率 */
                double maturityT = ChronoUnit.DAYS.between(dataDate, cashflow.fwdStartDate) / 365.0;
                double settleT = ChronoUnit.DAYS.between(dataDate, cashflow.fwdStartDate) / 365.0;
                int days = (int) ChronoUnit.DAYS.between(dataDate,cashflow.fwdStartDate);
                eurOptUtil = new EurOptUtil(call,true,s,k,
                        0,0,maturityT,settleT,irVol.getVolCur(days),valuationModel);

                int days2 = resolveUnderlyingTenorDays(cashflow);
                if (days2 <= 0) {
                    days2 = days;
                }
                v = irVol.underlyingTerm(days2, irVol.getVolCur(days));  /* 波动率 */
                if (v > 0) {
                    r = eurOptUtil.getValue(v);          /* 获取bs结果时传入sigma获取结果 */
                }else {
                    r = call ? Math.max((s - k),0) : Math.max((k - s),0);
                }
            }
            double fv = -1 + 1 / CurveFunc.discountFactor(
                    cashflow.prePaymentDate,
                    cashflow.paymentDate,
                    r,
                    "smp",
                    capFloorInfo.dayCountBasis);
            cashflow.cf = fv * signedNotional;
            CashFlowCapFloor cfp = new CashFlowCapFloor(cashflow);
            cfp.expectedForwardRate = r;
            cfp.volatilityRate = v;
            cfp.dataDate = capFloorInfo.dataDate;
            cfp.currencyCode = capFloorInfo.currencyCode;
            cfp.notional = signedNotional;
            res.add(cfp);
        }
        return res;
    }

    private List<BaseCashFlow> buildBaseCashFlowList(List<CashFlowCapFloor> src, MarketData marketData) {
        List<BaseCashFlow> res = new ArrayList<>();
        if (src == null) {
            return res;
        }
        IrSpot discountCurve = new IrSpot(marketData.irSpot.get(capFloorInfo.discountCurve));
        for (CashFlowCapFloor cf : src) {
            ScfCashFlow out = new ScfCashFlow();
            out.dataDate = cf.dataDate;
            out.currencyCode = cf.currencyCode;
            out.paymentDate = cf.paymentDate;
            out.prepaymentDate = cf.prePaymentDate;
            out.theoPaymentDate = cf.theoPaymentDate;
            out.fwdStartDat = cf.fwdStartDate;
            out.fwdEndDate = cf.fwdEndDate;
            out.cashFlowType = cf.cashType;
            out.cashflow = cf.cf;
            out.discountRate = discountCurve.spotRate(cf.paymentDate);
            out.discountFactor = cf.discoutFactor;
            out.rate = cf.rate;
            out.volatilityRate = cf.volatilityRate;
            out.startNotional = cf.startNotional != null ? cf.startNotional : cf.notional;
            out.endNotional = cf.endNotional;
            res.add(out);
        }
        return res;
    }

    private double getSignedNotional() {
        if (capFloorInfo.notional == null) {
            throw new IllegalArgumentException("名义本金为空: instrumentId=" + capFloorInfo.instrumentId);
        }
        return StringUtils.equalsIgnoreCase("S", capFloorInfo.buyOrSell) ? -capFloorInfo.notional : capFloorInfo.notional;
    }

    private void validateInputs(MarketData marketData) {
        if (capFloorInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        requireText(capFloorInfo.instrumentId, "INSTRUMENT_ID");
        requireText(capFloorInfo.productCode, "PRODUCT_CODE");
        if (!"CAP".equalsIgnoreCase(capFloorInfo.capOrFloor)
                && !"FLOOR".equalsIgnoreCase(capFloorInfo.capOrFloor)) {
            throw new IllegalArgumentException("CAP_OR_FLOOR 仅支持 CAP/FLOOR: " + capFloorInfo.capOrFloor);
        }
        if (!"B".equalsIgnoreCase(capFloorInfo.buyOrSell)
                && !"S".equalsIgnoreCase(capFloorInfo.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + capFloorInfo.buyOrSell);
        }
        requireCurrencyCode(capFloorInfo.currencyCode, "CURRENCY_CODE");
        requireNonNegativeFinite(capFloorInfo.notional, "NOTIONAL");
        if (capFloorInfo.startDate == null) {
            throw new IllegalArgumentException("START_DATE 不能为空");
        }
        if (capFloorInfo.maturityDate == null) {
            throw new IllegalArgumentException("MATURITY_DATE 不能为空");
        }
        requireFinite(capFloorInfo.strikeRate, "STRIKE_RATE");
        requireText(capFloorInfo.dayCountBasis, "DAY_COUNT_BASIS");
        requireText(capFloorInfo.payFreq, "PAY_FREQ");
        requireText(capFloorInfo.discountCurve, "DISCOUNT_CURVE");
        requireText(capFloorInfo.referenceCurve, "REFERENCE_CURVE");
        requireText(capFloorInfo.volatilitySurface, "VOLATILITY_SURFACE");
        resolveValuationModel();
        if (marketData == null) {
            throw new IllegalArgumentException("市场数据为空: instrumentId=" + capFloorInfo.instrumentId);
        }
        if (marketData.irSpot == null || marketData.irSpot.get(capFloorInfo.discountCurve) == null) {
            throw new IllegalArgumentException("折现曲线不存在: " + capFloorInfo.discountCurve);
        }
        if (marketData.irSpot == null || marketData.irSpot.get(capFloorInfo.referenceCurve) == null) {
            throw new IllegalArgumentException("参考曲线不存在: " + capFloorInfo.referenceCurve);
        }
        if (marketData.irVol == null || marketData.irVol.get(capFloorInfo.volatilitySurface) == null) {
            throw new IllegalArgumentException("波动率曲面不存在: " + capFloorInfo.volatilitySurface);
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

    private static void requireNonNegativeFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " 必须为非负有限数: " + value);
        }
    }

    private void getFrtbSensList() {
        List<FrtbSenes> list = new ArrayList<>();
        HashMap<String,String> map  = new HashMap<>();
        map.put(capFloorInfo.discountCurve, capFloorInfo.currencyCode);
        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(map);
        List<FrtbDependency> girrVegaDependencies = FrtbSensitivityBuilder.buildGirrVegaDependencies(
                capFloorInfo.volatilitySurface,
                capFloorInfo.currencyCode,
                resolveGirrVegaSecondaryVertex());
        List<FrtbSenes> girrSensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                capFloorInfo.maturityDate,
                girrDeltaDependencies,
                girrVegaDependencies,
                true,
                true,
                capFloorInfo.instrumentId,
                capFloorInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(measure.valuation, measure.valuationCny),
                shockedMarketData -> {
                    CapFloorMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                },
                null,
                null);
        list.addAll(girrSensitivities);

        List<FrtbDependency> fxDeltaDependencies = FrtbSensitivityBuilder.buildFxDeltaDependencies(
                Collections.singletonList(capFloorInfo.currencyCode));
        List<FrtbSenes> fxSensitivities = FrtbSensitivityBuilder.buildFxSensitivities(
                marketData,
                dataDate,
                capFloorInfo.maturityDate,
                fxDeltaDependencies,
                Collections.emptyList(),
                true,
                false,
                this.measure.instrumentId,
                capFloorInfo.currencyCode,
                1e-12,
                com.zcyh.mr.product.basic.frtb.MeasureValuation.of(this.measure.valuation, this.measure.valuationCny),
                shockedMarketData -> {
                    CapFloorMeasure shockedMeasure = calc(shockedMarketData);
                    return com.zcyh.mr.product.basic.frtb.MeasureValuation.of(shockedMeasure.valuation, shockedMeasure.valuationCny);
                });
        list.addAll(fxSensitivities);

        list.removeIf(item -> Math.abs(item.sensitivityValInstCurr) < 1e-12
                && Math.abs(item.sensitivityValInstCurrCny) < 1e-12);/*移除敏度结果为0的元素*/
        measure.sensitivityList = list;
    }

    /**
     * 根据现金流或频率推导标的期限天数。
     * 定价本体仍需使用这段逻辑，不能因为 FRTB Vega 收口到公共 builder 而删除。
     */
    private int resolveUnderlyingTenorDays(StructuredCashflow.Cashflow cashflow) {
        if (cashflow != null && cashflow.fwdStartDate != null && cashflow.fwdEndDate != null) {
            int tenor = (int) ChronoUnit.DAYS.between(cashflow.fwdStartDate, cashflow.fwdEndDate);
            if (tenor > 0) {
                return tenor;
            }
        }
        String freq = capFloorInfo.fixingFreq;
        if (StringUtils.isBlank(freq)) {
            freq = capFloorInfo.resetFreq;
        }
        if (StringUtils.isBlank(freq)) {
            freq = capFloorInfo.payFreq;
        }
        if (StringUtils.isBlank(freq)) {
            return -1;
        }
        LocalDate end = CommUtils.periodPlus(dataDate, freq);
        return (int) ChronoUnit.DAYS.between(dataDate, end);
    }

    /**
     * GIRR Vega 第二维统一使用标的利率期限，优先 FIXING_FREQ，缺失时回退 PAY_FREQ。
     */
    private String resolveGirrVegaSecondaryVertex() {
        if (StringUtils.isNotBlank(capFloorInfo.fixingFreq)) {
            return capFloorInfo.fixingFreq.trim();
        }
        if (StringUtils.isNotBlank(capFloorInfo.payFreq)) {
            return capFloorInfo.payFreq.trim();
        }
        return null;
    }

    private Map<String, Object> buildDetail(CapFloorMeasure m) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("估值模型", resolveValuationModelLabel());
        detail.put("现金流日N(d)", buildNdDetailList(m == null ? null : m.cashFlow));
        return detail;
    }

    private String resolveValuationModel() {
        String model = capFloorInfo.valuationModel;
        if (StringUtils.isBlank(model)) {
            return "bachelier";
        }
        String normalized = model.trim().toUpperCase(Locale.ROOT);
        if ("BACHELIER".equals(normalized) || "NORMAL".equals(normalized)) {
            return "bachelier";
        }
        if ("BLACK76".equals(normalized) || "BLACK".equals(normalized) || "LOGNORMAL".equals(normalized)) {
            return "black";
        }
        throw new IllegalArgumentException("VALUATION_MODEL不支持: " + model + " (仅支持 BACHELIER/BLACK76)");
    }

    private String resolveValuationModelLabel() {
        return "black".equals(resolveValuationModel()) ? "Black76" : "Bachelier";
    }

    private List<Map<String, Object>> buildNdDetailList(List<CashFlowCapFloor> cashflows) {
        List<Map<String, Object>> ndList = new ArrayList<>();
        if (cashflows == null || capFloorInfo.strikeRate == null) {
            return ndList;
        }
        double w = "cap".equalsIgnoreCase(capFloorInfo.capOrFloor) ? 1.0 : -1.0;
        for (CashFlowCapFloor cf : cashflows) {
            if (cf == null || cf.paymentDate == null || cf.fwdStartDate == null || cf.volatilityRate == null) {
                continue;
            }
            if (cf.fwdStartDate.isBefore(dataDate)) {
                continue;
            }
            double maturityT = ChronoUnit.DAYS.between(dataDate, cf.fwdStartDate) / 365.0;
            if (maturityT <= 0 || cf.volatilityRate <= 0) {
                continue;
            }
            double denom = cf.volatilityRate * Math.sqrt(maturityT);
            if (denom == 0.0) {
                continue;
            }
            double d = (cf.rate - capFloorInfo.strikeRate) / denom;
            double nd = EurOptUtil.cdf(w * d);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("现金流日", cf.paymentDate);
            item.put("N(d)", nd);
            ndList.add(item);
        }
        return ndList;
    }

    /*capFloor内部类，封装计量指标*/
    static public class CapFloorMeasure extends Measure {
        @JSONField(name = "GAMMA")
        public double gamma;
        @JSONField(name = "VEGA")
        public double vega;
        @JSONField(serialize = false, deserialize = false)
        public List<CashFlowCapFloor> cashFlow;
    }
    
    /*capFloor内部类，封装入参信息，入参Json转化为内部类的形式,方便后面使用*/
    public static class CapFloorInfo{
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true, allowedValues = {"CAP", "FLOOR"}, ignoreCase = true)
        @JSONField(name = "CAP_OR_FLOOR")
        public String capOrFloor;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @ProductInputField(required = true)
        @JSONField(name = "START_DATE", format = "yyyyMMdd")
        public LocalDate startDate;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @ProductInputField(required = true, finite = true)
        @JSONField(name = "STRIKE_RATE")
        public Double strikeRate;
        @ProductInputField(required = true)
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";
        @ProductInputField(required = true)
        @JSONField(name = "PAY_FREQ")
        public String payFreq;
        @JSONField(name = "SETTLE_CALENDAR")
        public String settleCalendar;
        @JSONField(name = "SETTLE_RULE")
        public String settleRule;
        @JSONField(name = "SETTLE_DAYOFF")
        public Integer settleDayoff;
        @JSONField(name = "FIXING_FREQ")
        public String fixingFreq;
        @JSONField(name = "FIXING_CALENDAR")
        public String fixingCalendar;
        @JSONField(name = "FIXING_RULE")
        public String fixingRule;
        @JSONField(name = "FIXING_DAYOFF")
        public Integer fixingDayoff;
        @ProductInputField(required = true)
        @JSONField(name = "DISCOUNT_CURVE")
        public String discountCurve;
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;

        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;
        @JSONField(name = "RESET_FREQ")
        public String resetFreq;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @ProductInputField(required = true)
        @JSONField(name = "VOLATILITY_SURFACE")
        public String volatilitySurface;
        @ProductInputField(allowedValues = {"BACHELIER", "NORMAL", "BLACK76", "BLACK", "LOGNORMAL"},
                ignoreCase = true)
        @JSONField(name = "VALUATION_MODEL")
        public String valuationModel;
    }

    private static class CashFlowCapFloor extends StructuredCashflow.Cashflow {
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
        @JSONField(name = "NOTIONAL")
        public Double notional;
        @JSONField(name = "VOLATILITY_RATE")
        public Double volatilityRate;
        @JSONField(name = "EXPECTED_FORWARD_RATE")
        public Double expectedForwardRate;

        public CashFlowCapFloor(StructuredCashflow.Cashflow cashFlow) {
            this.prePaymentDate = cashFlow.prePaymentDate;
            this.paymentDate = cashFlow.paymentDate;
            this.fwdStartDate = cashFlow.fwdStartDate;
            this.fwdEndDate = cashFlow.fwdEndDate;
            this.cashType = cashFlow.cashType;
            this.cf = cashFlow.cf;
            this.rate = cashFlow.rate;
            this.discoutFactor = cashFlow.discoutFactor;
            this.timeFactor = cashFlow.timeFactor;
            this.paymentType = cashFlow.paymentType;
            this.theoPaymentDate = cashFlow.theoPaymentDate;
            this.theoPrePaymentDate = cashFlow.theoPrePaymentDate;
        }
    }
}


