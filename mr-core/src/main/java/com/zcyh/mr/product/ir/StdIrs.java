package com.zcyh.mr.product.ir;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.CurveFunc;
import com.zcyh.mr.product.basic.frtb.FrtbDependency;
import com.zcyh.mr.product.basic.frtb.FrtbSenes;
import com.zcyh.mr.product.basic.frtb.FrtbSensitivityBuilder;
import com.zcyh.mr.product.basic.frtb.MeasureValuation;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private StdIrsTradeInfo tradeInfo;
    private MarketData marketData;
    private Calendar calendar;

    public StdIrs(LocalDate dataDate, StdIrsTradeInfo tradeInfo, MarketData marketData, Calendar calendar) {
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
        measure.logs = new ArrayList<>();
        measure.sensitivityList = calcFrtbSens(measure);


        measure.productCode = tradeInfo.productCode;
        measure.dataDate = dataDate;
        measure.instrumentId = tradeInfo.instrumentId;
        measure.position = resolvePosition();
        measure.valuationCcy = tradeInfo.currencyCode;
        measure.valuationUnit = measure.position == 0.0 ? 0.0 : measure.valuation / measure.position;
        measure.status = "SUCCESS";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("valuation_marity_date", tradeInfo.maturityDate.toString());
        measure.detail = detail;

        return measure;
    }

    /**
     * 使用指定市场数据估值
     */
    public StdIrsMeasure calcWithMarketData(MarketData md) {
        validateInputs(md);
        StdIrsMeasure measure = new StdIrsMeasure();

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

        // 使用交易输入定义的日计数口径
        String dcbType = tradeInfo.dayCountBasis;
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
        FxSpot fxSpot = new FxSpot(EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_BASE_CODE), md.fxSpot);

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
        String code = termCode.trim().toUpperCase(Locale.ROOT);
        int amount = Integer.parseInt(code.substring(0, code.length() - 1));
        if (code.endsWith("Y")) {
            return start.plusYears(amount);
        } else if (code.endsWith("M")) {
            return start.plusMonths(amount);
        } else if (code.endsWith("D")) {
            return start.plusDays(amount);
        }
        throw new IllegalArgumentException("TERM_CODE 仅支持正整数加 Y/M/D: " + termCode);
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

    private void validateInputs(MarketData md) {
        if (tradeInfo == null) {
            throw new IllegalArgumentException("交易信息为空");
        }
        if (dataDate == null) {
            throw new IllegalArgumentException("数据日期为空");
        }
        requireText(tradeInfo.instrumentId, "INSTRUMENT_ID");
        requireText(tradeInfo.productCode, "PRODUCT_CODE");
        requireCurrencyCode(tradeInfo.currencyCode, "CURRENCY_CODE");
        if (!"B".equalsIgnoreCase(tradeInfo.buyOrSell) && !"S".equalsIgnoreCase(tradeInfo.buyOrSell)) {
            throw new IllegalArgumentException("BUY_OR_SELL 仅支持 B/S: " + tradeInfo.buyOrSell);
        }
        requireFinite(tradeInfo.tradePrice, "TRADE_PRICE");
        requireNonNegativeFinite(tradeInfo.notional, "NOTIONAL");
        if (tradeInfo.maturityDate == null) {
            throw new IllegalArgumentException("MATURITY_DATE 不能为空");
        }
        validateTermCode(tradeInfo.termCode);
        requireText(tradeInfo.referenceCurve, "REFERENCE_CURVE");
        requireText(tradeInfo.dayCountBasis, "DAY_COUNT_BASIS");
        if (md == null) {
            throw new IllegalArgumentException("市场数据为空");
        }
        if (md.irSpot == null || md.irSpot.get(tradeInfo.referenceCurve) == null) {
            throw new IllegalArgumentException("未找到参考曲线: " + tradeInfo.referenceCurve);
        }
        if (md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            throw new IllegalArgumentException("市场数据缺少外汇即期曲线");
        }
    }

    private static void validateTermCode(String termCode) {
        requireText(termCode, "TERM_CODE");
        String normalized = termCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[1-9][0-9]*[YMD]")) {
            throw new IllegalArgumentException("TERM_CODE 仅支持正整数加 Y/M/D: " + termCode);
        }
        try {
            Integer.parseInt(normalized.substring(0, normalized.length() - 1));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("TERM_CODE 数值超出支持范围: " + termCode, ex);
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

    private static void requireNonNegativeFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " 必须为非负有限数: " + value);
        }
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
    public static class StdIrsTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;

        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, length = 3)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;

        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;

        @ProductInputField(required = true, finite = true)
        @JSONField(name = "TRADE_PRICE")
        public Double tradePrice;

        @ProductInputField(required = true, finite = true, min = "0")
        @JSONField(name = "NOTIONAL")
        public Double notional;

        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;

        @ProductInputField(required = true)
        @JSONField(name = "TERM_CODE")
        public String termCode = "1Y";

        @ProductInputField(required = true)
        @JSONField(name = "REFERENCE_CURVE")
        public String referenceCurve;

        @ProductInputField(required = true)
        @JSONField(name = "DAY_COUNT_BASIS")
        public String dayCountBasis = "actual/365";

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

