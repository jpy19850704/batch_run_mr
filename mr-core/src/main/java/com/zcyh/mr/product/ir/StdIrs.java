package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.CurveFunc;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准利率互换（STD_IRS）估值类
 *
 * 基于 PrimeNCD1Y 的标准化利率互换合约，现金结算。
 * 估值公式：V = (远期利率 − 成交价) × 名义本金 × DCF × 方向
 * 无折现。
 */
public class StdIrs {

    private LocalDate dataDate;
    private StdIrsInfo tradeInfo;
    private MarketData marketData;
    private Calendar calendar;

    public StdIrs(LocalDate dataDate, StdIrsInfo tradeInfo, MarketData marketData, Calendar calendar) {
        this.dataDate = dataDate;
        this.tradeInfo = tradeInfo;
        this.marketData = marketData;
        this.calendar = calendar;
    }

    /**
     * 执行估值计算
     */
    public StdIrsMeasure calc() {
        StdIrsMeasure measure = calcWithMarketData(marketData);

        // PV01：定盘曲线整体平移 1bp 后重新估值
        MarketData shiftedMd = buildShiftedIrMarketData(marketData, tradeInfo.referenceCurve, 0.0001);
        StdIrsMeasure shiftedMeasure = calcWithMarketData(shiftedMd);
        measure.pv01 = shiftedMeasure.valuation - measure.valuation;

        // FRTB GIRR Delta 敏感度
        measure.sensitivityList = calcFrtbSens(measure);


        measure.productCode = tradeInfo.productCode;
        measure.dataDate = dataDate;
        measure.instrumentId = tradeInfo.instrumentId;
        measure.position = resolvePosition();
        measure.valuationCcy = tradeInfo.currencyCode;
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("valuation_marity_date", tradeInfo.maturityDate == null ? null : tradeInfo.maturityDate.toString());
        measure.detail = detail;

        return measure;
    }

    /**
     * 使用指定市场数据估值
     */
    public StdIrsMeasure calcWithMarketData(MarketData md) {
        StdIrsMeasure measure = new StdIrsMeasure();

        if (md == null || md.irSpot == null || md.irSpot.get(tradeInfo.referenceCurve) == null) {
            throw new IllegalArgumentException("未找到参考曲线: " + tradeInfo.referenceCurve);
        }
        IrSpot irSpot = new IrSpot(md.irSpot.get(tradeInfo.referenceCurve));

        // 计息起始日：固定按 F 规则向后 1 个工作日；若无日历则取下一自然日
        LocalDate accrualStart;
        if (calendar != null && tradeInfo.settleCalendar != null && !tradeInfo.settleCalendar.trim().isEmpty()) {
            accrualStart = calendar.getBusinessDay(
                    tradeInfo.settleCalendar,
                    tradeInfo.maturityDate,
                    "F",
                    1);
        } else {
            accrualStart = tradeInfo.maturityDate.plusDays(1);
        }
        LocalDate accrualEnd = adjustAccrualEnd(calcAccrualEnd(accrualStart, tradeInfo.termCode));

        // 日算因子 A/A-Bond
        String dcbType = tradeInfo.dayCountBasis != null ? tradeInfo.dayCountBasis : "actual/actual";
        // 计算远期利率，并统一转换为单利计息口径
        double forwardRate = irSpot.fwdRate(accrualStart, accrualEnd);
        forwardRate = CurveFunc.convertIrRate(forwardRate, accrualStart, accrualEnd,
                irSpot.getIrSpotInfo().freq, irSpot.getIrSpotInfo().dayCount, "smp", dcbType);
        double dcf = CurveFunc.timeFactor(accrualStart, accrualEnd, dcbType);

        // 方向：B=+1（买入，预期利率上升），S=-1
        int direction = "B".equalsIgnoreCase(tradeInfo.buyOrSell) ? 1 : -1;
        double position = direction * tradeInfo.notional;

        // 估值 = (远期利率 - 成交价) × 名义本金 × DCF × 方向
        double unitValue = (forwardRate - tradeInfo.tradePrice) * dcf;
        double value = unitValue * position;
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), md.fxSpot);

        measure.position = position;
        measure.valuation = value;
        measure.valuationCcy = tradeInfo.currencyCode;
        measure.valuationUnit = position == 0.0 ? 0.0 : value / position;
        measure.valuationCny = value * fxSpot.getFxrate(tradeInfo.currencyCode);
        measure.forwardRate = forwardRate;
        measure.dcf = dcf;
        measure.tradePrice = tradeInfo.tradePrice;
        measure.cashFlowList = buildCashFlowList(tradeInfo.maturityDate, accrualStart, accrualEnd, forwardRate, value);
        fillCommonMeasureFields(measure);

        return measure;
    }

    /**
     * 仅复制需要冲击的定盘曲线，构造局部替换后的市场数据。
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

    /**
     * 根据期限代码计算计息期结束日
     */
    private LocalDate calcAccrualEnd(LocalDate start, String termCode) {
        if (termCode == null || termCode.isEmpty()) {
            return start.plusYears(1);
        }
        String code = termCode.toUpperCase().trim();
        if (code.endsWith("Y")) {
            int years = Integer.parseInt(code.substring(0, code.length() - 1));
            return start.plusYears(years);
        } else if (code.endsWith("M")) {
            int months = Integer.parseInt(code.substring(0, code.length() - 1));
            return start.plusMonths(months);
        } else if (code.endsWith("D")) {
            int days = Integer.parseInt(code.substring(0, code.length() - 1));
            return start.plusDays(days);
        }
        return start.plusYears(1);
    }

    private LocalDate adjustAccrualEnd(LocalDate accrualEnd) {
        if (calendar != null && tradeInfo.settleCalendar != null && !tradeInfo.settleCalendar.trim().isEmpty()) {
            return calendar.getBusinessDay(
                    tradeInfo.settleCalendar,
                    accrualEnd,
                    "F",
                    0);
        }
        return accrualEnd;
    }

    /**
     * 计算 FRTB GIRR Delta 敏感度
     */
    private List<FrtbSenes> calcFrtbSens(StdIrsMeasure measure) {
        MeasureValuation baseValuation =
                MeasureValuation.of(measure.valuation, measure.valuationCny);
        HashMap<String, String> map = new HashMap<>();
        map.put(tradeInfo.referenceCurve, tradeInfo.currencyCode);
        List<FrtbDependency> girrDeltaDependencies = FrtbSensitivityBuilder.buildGirrDeltaDependencies(map);
        List<FrtbSenes> sensitivities = FrtbSensitivityBuilder.buildGirrSensitivities(
                marketData,
                dataDate,
                tradeInfo.maturityDate,
                girrDeltaDependencies,
                new ArrayList<>(),
                true,
                false,
                tradeInfo.instrumentId,
                tradeInfo.currencyCode,
                1e-12,
                baseValuation,
                shockedMarketData -> {
                    StdIrsMeasure shockedMeasure = calcWithMarketData(shockedMarketData);
                    return MeasureValuation.of(
                            shockedMeasure.valuation,
                            shockedMeasure.valuationCny);
                },
                null,
                null);
        return sensitivities;
    }

    private double resolvePosition() {
        int direction = "B".equalsIgnoreCase(tradeInfo.buyOrSell) ? 1 : -1;
        return direction * tradeInfo.notional;
    }

    /**
     * 补齐基础和场景估值都会依赖的通用结果字段。
     */
    private void fillCommonMeasureFields(StdIrsMeasure measure) {
        measure.productCode = tradeInfo.productCode;
        measure.dataDate = dataDate;
        measure.instrumentId = tradeInfo.instrumentId;
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
    }

    private List<BaseCashFlow> buildCashFlowList(LocalDate paymentDate, LocalDate accrualStart, LocalDate accrualEnd,
            double forwardRate, double valuation) {
        List<BaseCashFlow> cashflows = new ArrayList<>();
        ScfCashFlow cf = new ScfCashFlow();
        cf.dataDate = dataDate;
        cf.currencyCode = tradeInfo.currencyCode;
        cf.cashFlowType = "FLOAT_INTEREST";
        cf.paymentDate = paymentDate;
        cf.cashflow = valuation;
        cf.discountRate = 0.0;
        cf.discountFactor = 1.0;
        cf.rate = forwardRate;
        cf.startNotional = tradeInfo.notional;
        cf.endNotional = tradeInfo.notional;
        cf.fwdStartDat = accrualStart;
        cf.fwdEndDate = accrualEnd;
        cashflows.add(cf);
        return cashflows;
    }

    /**
     * 交易信息
     */
    public static class StdIrsInfo {
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;

        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;

        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;

        @JSONField(name = "TRADE_PRICE")
        public double tradePrice;

        @JSONField(name = "NOTIONAL")
        public double notional;

        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;

        @JSONField(name = "TERM_CODE")
        public String termCode;

        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;

        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis;

        @JSONField(name = "SETTLE_CALENDAR")
        public String settleCalendar;
    }

    /**
     * 估值结果
     */
    public static class StdIrsMeasure extends Measure {
        @JSONField(name = "FORWARD_RATE", format = "0.########")
        public double forwardRate;

        @JSONField(name = "DCF", format = "0.########")
        public double dcf;

        @JSONField(name = "TRADE_PRICE", format = "0.########")
        public double tradePrice;

        @JSONField(name = "FRTB_SENSITIVITY")
        public List<FrtbSenes> sensitivityList;
    }
}

