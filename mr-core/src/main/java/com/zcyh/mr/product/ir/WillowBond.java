package com.zcyh.mr.product.ir;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.basic.util.ReflectionUtils;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.CurveFunc;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.BaseCashFlow;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.ScfCashFlow;
import com.zcyh.mr.product.basic.scf.StructuredCashflow;
import com.zcyh.mr.product.basic.willow.WillowAlphaCalibrator;
import com.zcyh.mr.product.basic.willow.WillowModelType;
import com.zcyh.mr.product.basic.willow.WillowNodeDefinition;
import com.zcyh.mr.product.basic.willow.WillowTransitionGenerator;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

public class WillowBond {
    private static final double DEFAULT_SPREAD_OVER_YIELD = 0.0;
    private static final double SPREAD_SEARCH_MIN = -1.0;
    private static final double SPREAD_SEARCH_MAX = 1.0;
    private static final int SPREAD_SEARCH_STEPS = 400;
    private static final double SPREAD_PRICE_TOLERANCE = 1e-8;
    private static final double SPREAD_ROOT_TOLERANCE = 1e-12;

    private final LocalDate dataDate;
    private final WillowBondInfo info;
    private final MarketData marketData;
    private final Calendar calendar;
    private Double fixedSpreadOverYield;

    public WillowBond(LocalDate dataDate, WillowBondInfo info, MarketData marketData, Calendar calendar) {
        this.dataDate = Objects.requireNonNull(dataDate, "DATA_DATE不能为空");
        this.info = Objects.requireNonNull(info, "WILLOW_BOND交易信息不能为空");
        this.marketData = Objects.requireNonNull(marketData, "市场数据不能为空");
        this.calendar = calendar;
    }

    public Bond.BondMeasure calc() {
        validate();
        LinkedList<StructuredCashflow.Cashflow> cashflows = buildCashflows();
        WillowTransitionGenerator transitionGenerator = new WillowTransitionGenerator();

        List<LocalDate> timeline = buildTimeline(cashflows);
        Map<LocalDate, Double> cashflowByDate = cashflowByDate(cashflows);
        Map<LocalDate, String> exerciseByDate = exerciseByDate();
        CalibratedTree calibratedTree = buildCalibratedTree(timeline, transitionGenerator);

        SpreadCalibrationResult spreadResult = calibrateSpread(timeline, cashflowByDate, exerciseByDate,
                transitionGenerator, calibratedTree);
        double value = backwardPrice(timeline, cashflowByDate, exerciseByDate, transitionGenerator,
                calibratedTree, spreadResult.spread);
        return buildMeasure(value, cashflows, timeline, exerciseByDate, calibratedTree, spreadResult);
    }

    public void setSpreadOverYield(Double spreadOverYield) {
        if (spreadOverYield != null && !Double.isFinite(spreadOverYield)) {
            throw new IllegalArgumentException("SPREAD_OVER_YIELD非法: " + spreadOverYield);
        }
        this.fixedSpreadOverYield = spreadOverYield;
    }

    private void validate() {
        requireText(info.instrumentId, "INSTRUMENT_ID");
        requireText(info.currencyCode, "CURRENCY_CODE");
        requireText(info.discountCurve, "DISCOUNT_CURVE");
        requireText(info.willowReferenceCurve, "WILLOW_REFERENCE_CURVE");
        requireText(info.willowModelType, "WILLOW_MODEL_TYPE");
        if (!"Fixed".equalsIgnoreCase(info.interestType)) {
            throw new IllegalArgumentException("WILLOW_BOND第一版仅支持固定息: INTEREST_TYPE=" + info.interestType);
        }
        if (info.notional == null || info.notional <= 0.0) {
            throw new IllegalArgumentException("NOTIONAL必须大于0");
        }
        if (info.positionTrade == null) {
            throw new IllegalArgumentException("POSITION_TRADE不能为空");
        }
        if (info.interestRate == null) {
            throw new IllegalArgumentException("INTEREST_RATE不能为空");
        }
        if (info.willowVolatility == null || info.willowVolatility < 0.0) {
            throw new IllegalArgumentException("WILLOW_VOLATILITY必须大于等于0");
        }
        if (info.willowStepDays == null || info.willowStepDays <= 0) {
            throw new IllegalArgumentException("WILLOW_STEP_DAYS必须大于0");
        }
        if (info.callPutDates == null || info.callPutDates.isEmpty()) {
            throw new IllegalArgumentException("WILLOW_BOND必须提供CALLPUT_DATES");
        }
        if (marketData.irSpot == null || !marketData.irSpot.containsKey(info.discountCurve)) {
            throw new IllegalArgumentException("折现曲线不存在: " + info.discountCurve);
        }
        if (!marketData.irSpot.containsKey(info.willowReferenceCurve)) {
            throw new IllegalArgumentException("WILLOW_REFERENCE_CURVE不存在: " + info.willowReferenceCurve);
        }
        boolean treeForDiscount = info.willowReferenceCurve.equals(info.discountCurve);
        boolean treeForForward = !StringUtils.isBlank(info.referenceCurve)
                && info.willowReferenceCurve.equals(info.referenceCurve);
        if (!treeForDiscount && !treeForForward) {
            throw new IllegalArgumentException("WILLOW_REFERENCE_CURVE未命中交易曲线: " + info.willowReferenceCurve);
        }
        if (!treeForDiscount) {
            throw new IllegalArgumentException("WILLOW_BOND第一版要求WILLOW_REFERENCE_CURVE命中DISCOUNT_CURVE");
        }
    }

    private LinkedList<StructuredCashflow.Cashflow> buildCashflows() {
        StructuredCashflow.ScfInfo scfInfo = ReflectionUtils.bean2Bean(info, StructuredCashflow.ScfInfo.class);
        scfInfo.paymentTiming = "arrear";
        scfInfo.notionalFlag = "END";
        scfInfo.fixingCalendar = info.settleCalendar;
        scfInfo.amortizationSchedule = null;
        StructuredCashflow scf = new StructuredCashflow(dataDate, scfInfo, marketData, calendar);
        return new LinkedList<>(scf.calc().cashFlowList);
    }

    private List<LocalDate> buildTimeline(List<StructuredCashflow.Cashflow> cashflows) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        dates.add(dataDate);
        LocalDate endDate = info.maturityDate;
        for (StructuredCashflow.Cashflow cashflow : cashflows) {
            if (cashflow.paymentDate != null && cashflow.paymentDate.isAfter(dataDate)) {
                dates.add(cashflow.paymentDate);
                if (cashflow.paymentDate.isAfter(endDate)) {
                    endDate = cashflow.paymentDate;
                }
            }
        }
        for (Bond.CallPutDate callPutDate : info.callPutDates) {
            if (callPutDate.date != null && callPutDate.date.isAfter(dataDate)) {
                dates.add(callPutDate.date);
            }
        }
        LocalDate step = dataDate.plusDays(info.willowStepDays);
        while (!step.isAfter(endDate)) {
            dates.add(step);
            step = step.plusDays(info.willowStepDays);
        }
        dates.add(endDate);
        return new ArrayList<>(dates);
    }

    private Map<LocalDate, Double> cashflowByDate(List<StructuredCashflow.Cashflow> cashflows) {
        Map<LocalDate, Double> map = new TreeMap<>();
        for (StructuredCashflow.Cashflow cashflow : cashflows) {
            if (cashflow.paymentDate == null || cashflow.paymentDate.isBefore(dataDate)) {
                continue;
            }
            if (Boolean.FALSE.equals(info.includeTodayCashflow) && cashflow.paymentDate.equals(dataDate)) {
                continue;
            }
            map.merge(cashflow.paymentDate, cashflow.cf, Double::sum);
        }
        return map;
    }

    private Map<LocalDate, String> exerciseByDate() {
        Map<LocalDate, String> map = new TreeMap<>();
        for (Bond.CallPutDate callPutDate : info.callPutDates) {
            if (callPutDate.date == null || !callPutDate.date.isAfter(dataDate)) {
                continue;
            }
            String type = requireText(callPutDate.type, "CALLPUT_DATES.TYPE");
            if (!"Call".equalsIgnoreCase(type) && !"Put".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException("CALLPUT_DATES.TYPE仅支持Call或Put: " + type);
            }
            map.put(callPutDate.date, type);
        }
        return map;
    }

    private double backwardPrice(List<LocalDate> timeline,
            Map<LocalDate, Double> cashflowByDate,
            Map<LocalDate, String> exerciseByDate,
            WillowTransitionGenerator transitionGenerator,
            CalibratedTree calibratedTree,
            double spreadOverYield) {
        double[] nextValues = filled(cashflowByDate.getOrDefault(timeline.get(timeline.size() - 1), 0.0));
        applyExercise(nextValues, timeline.get(timeline.size() - 1),
                cashflowByDate.getOrDefault(timeline.get(timeline.size() - 1), 0.0), exerciseByDate);

        for (int i = timeline.size() - 2; i > 0; i--) {
            LocalDate current = timeline.get(i);
            LocalDate next = timeline.get(i + 1);
            double[] currentValues = new double[WillowNodeDefinition.NODE_COUNT];
            double dt = yearFraction(current, next);
            double currentBrownianTime = yearFraction(dataDate, current);
            for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
                double df = spreadAdjustedDiscountFactor(calibratedTree.discountFactor(i, node),
                        dt, spreadOverYield, current, node);
                double expected = 0.0;
                for (var transition : transitionGenerator.getTransitions(currentBrownianTime, dt, node)) {
                    expected += transition.probability * nextValues[transition.destNode];
                }
                currentValues[node] = cashflowByDate.getOrDefault(current, 0.0) + df * expected;
            }
            applyExercise(currentValues, current, cashflowByDate.getOrDefault(current, 0.0), exerciseByDate);
            nextValues = currentValues;
        }

        if (timeline.size() == 1) {
            return cashflowByDate.getOrDefault(dataDate, 0.0);
        }
        double value = cashflowByDate.getOrDefault(dataDate, 0.0);
        double dt = yearFraction(dataDate, timeline.get(1));
        for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
            double df = spreadAdjustedDiscountFactor(calibratedTree.discountFactor(0, node),
                    dt, spreadOverYield, dataDate, node);
            value += WillowNodeDefinition.probability(node) * df * nextValues[node];
        }
        return value;
    }

    private void applyExercise(double[] values, LocalDate date, double cashflowAtDate, Map<LocalDate, String> exerciseByDate) {
        String type = exerciseByDate.get(date);
        if (type == null) {
            return;
        }
        double payoff = cashflowAtDate + info.notional;
        for (int node = 0; node < values.length; node++) {
            if ("Call".equalsIgnoreCase(type)) {
                values[node] = Math.min(values[node], payoff);
            } else {
                values[node] = Math.max(values[node], payoff);
            }
        }
    }

    private SpreadCalibrationResult calibrateSpread(List<LocalDate> timeline,
            Map<LocalDate, Double> cashflowByDate,
            Map<LocalDate, String> exerciseByDate,
            WillowTransitionGenerator transitionGenerator,
            CalibratedTree calibratedTree) {
        if (fixedSpreadOverYield != null) {
            return SpreadCalibrationResult.success(fixedSpreadOverYield);
        }
        if (info.dirtyPrice == null || info.dirtyPrice == 0.0) {
            return SpreadCalibrationResult.success(DEFAULT_SPREAD_OVER_YIELD);
        }
        double targetPrice = info.dirtyPrice;
        List<Double> candidates = new ArrayList<>();
        double zeroSpreadPrice = Double.NaN;
        double previousSpread = SPREAD_SEARCH_MIN;
        double previousValue = spreadObjective(previousSpread, targetPrice, timeline, cashflowByDate, exerciseByDate,
                transitionGenerator, calibratedTree);
        if (previousSpread == DEFAULT_SPREAD_OVER_YIELD) {
            zeroSpreadPrice = previousValue + targetPrice;
        }
        collectSpreadCandidate(candidates, previousSpread, previousValue);

        for (int i = 1; i <= SPREAD_SEARCH_STEPS; i++) {
            double currentSpread = SPREAD_SEARCH_MIN
                    + (SPREAD_SEARCH_MAX - SPREAD_SEARCH_MIN) * i / SPREAD_SEARCH_STEPS;
            double currentValue = spreadObjective(currentSpread, targetPrice, timeline, cashflowByDate, exerciseByDate,
                    transitionGenerator, calibratedTree);
            if (currentSpread == DEFAULT_SPREAD_OVER_YIELD) {
                zeroSpreadPrice = currentValue + targetPrice;
            }
            collectSpreadCandidate(candidates, currentSpread, currentValue);
            if (previousValue * currentValue < 0.0) {
                candidates.add(solveSpreadRoot(previousSpread, currentSpread, targetPrice, timeline,
                        cashflowByDate, exerciseByDate, transitionGenerator, calibratedTree));
            }
            previousSpread = currentSpread;
            previousValue = currentValue;
        }

        if (candidates.isEmpty()) {
            return SpreadCalibrationResult.warning(DEFAULT_SPREAD_OVER_YIELD,
                    "WILLOW_BOND债券spread校准无解，使用0 spread定价: DIRTY_PRICE=" + targetPrice
                            + ", PRICE_AT_ZERO_SPREAD=" + zeroSpreadPrice
                            + ", SEARCH_RANGE=[" + SPREAD_SEARCH_MIN + "," + SPREAD_SEARCH_MAX + "]");
        }
        return SpreadCalibrationResult.success(selectSpreadClosestToZero(candidates));
    }

    private void collectSpreadCandidate(List<Double> candidates, double spread, double objectiveValue) {
        if (Double.isFinite(objectiveValue) && Math.abs(objectiveValue) <= SPREAD_PRICE_TOLERANCE) {
            candidates.add(spread);
        }
    }

    private double solveSpreadRoot(double left,
            double right,
            double targetPrice,
            List<LocalDate> timeline,
            Map<LocalDate, Double> cashflowByDate,
            Map<LocalDate, String> exerciseByDate,
            WillowTransitionGenerator transitionGenerator,
            CalibratedTree calibratedTree) {
        double fLeft = spreadObjective(left, targetPrice, timeline, cashflowByDate, exerciseByDate,
                transitionGenerator, calibratedTree);
        double fRight = spreadObjective(right, targetPrice, timeline, cashflowByDate, exerciseByDate,
                transitionGenerator, calibratedTree);
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (left + right);
            double fMid = spreadObjective(mid, targetPrice, timeline, cashflowByDate, exerciseByDate,
                    transitionGenerator, calibratedTree);
            if (Math.abs(fMid) <= SPREAD_PRICE_TOLERANCE || Math.abs(right - left) <= SPREAD_ROOT_TOLERANCE) {
                return mid;
            }
            if (fLeft * fMid <= 0.0) {
                right = mid;
                fRight = fMid;
            } else {
                left = mid;
                fLeft = fMid;
            }
        }
        return 0.5 * (left + right);
    }

    private double spreadObjective(double spread,
            double targetPrice,
            List<LocalDate> timeline,
            Map<LocalDate, Double> cashflowByDate,
            Map<LocalDate, String> exerciseByDate,
            WillowTransitionGenerator transitionGenerator,
            CalibratedTree calibratedTree) {
        return backwardPrice(timeline, cashflowByDate, exerciseByDate, transitionGenerator,
                calibratedTree, spread) - targetPrice;
    }

    private double selectSpreadClosestToZero(List<Double> candidates) {
        double selected = candidates.get(0);
        for (double candidate : candidates) {
            if (Math.abs(candidate) < Math.abs(selected)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private CalibratedTree buildCalibratedTree(List<LocalDate> timeline,
            WillowTransitionGenerator transitionGenerator) {
        int stepCount = timeline.size() - 1;
        if (stepCount <= 0) {
            return CalibratedTree.empty();
        }
        IrSpot irSpot = new IrSpot(marketData.irSpot.get(info.willowReferenceCurve));
        WillowModelType modelType = parseModelType(info.willowModelType);
        double valuationBaseDiscount = irSpot.discount(dataDate);
        if (valuationBaseDiscount <= 0.0 || !Double.isFinite(valuationBaseDiscount)) {
            throw new IllegalArgumentException("WILLOW_REFERENCE_CURVE在估值日折现因子非法: " + valuationBaseDiscount);
        }

        double[][] discountFactors = new double[stepCount][WillowNodeDefinition.NODE_COUNT];
        double[] alphas = new double[stepCount];
        double[] statePrices = WillowNodeDefinition.probabilities();
        double maxFitError = 0.0;

        for (int step = 0; step < stepCount; step++) {
            LocalDate current = timeline.get(step);
            LocalDate next = timeline.get(step + 1);
            double dt = yearFraction(current, next);
            if (dt <= 0.0 || !Double.isFinite(dt)) {
                throw new IllegalArgumentException("Willow时间步长非法: from=" + current + ", to=" + next);
            }

            double targetCurrentDiscount = referenceDiscount(irSpot, valuationBaseDiscount, current);
            double targetNextDiscount = referenceDiscount(irSpot, valuationBaseDiscount, next);

            double statePriceSum = sum(statePrices);
            if (statePriceSum <= 0.0 || !Double.isFinite(statePriceSum)) {
                throw new IllegalArgumentException("Willow状态价格和非法: step=" + step + ", sum=" + statePriceSum);
            }
            double targetStepDiscount = targetNextDiscount / statePriceSum;
            double referenceStepDiscount = targetNextDiscount / targetCurrentDiscount;
            double referenceShortRate = shortRateFromDiscount(referenceStepDiscount, dt, current, next);
            double[] stateWeights = normalize(statePrices, statePriceSum);
            double[] baseRates = baseNodeRates(modelType, referenceShortRate,
                    Math.max(yearFraction(dataDate, current), 0.0), current);
            double alpha = WillowModelType.NORMAL.equals(modelType)
                    ? WillowAlphaCalibrator.calibrateNormal(targetStepDiscount, dt, baseRates, stateWeights)
                    : WillowAlphaCalibrator.calibrateLogNormal(targetStepDiscount, dt, baseRates, stateWeights);
            alphas[step] = alpha;

            for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
                double rate = WillowModelType.NORMAL.equals(modelType)
                        ? baseRates[node] + alpha
                        : Math.exp(baseRates[node] + alpha);
                discountFactors[step][node] = shortRateDiscount(rate, dt, current, node);
            }
            statePrices = nextStatePrices(step, current, dt, statePrices, discountFactors[step], transitionGenerator);
            maxFitError = Math.max(maxFitError, Math.abs(sum(statePrices) - targetNextDiscount));
        }
        return new CalibratedTree(discountFactors, alphas, maxFitError);
    }

    private double[] nextStatePrices(int step,
            LocalDate current,
            double dt,
            double[] currentStatePrices,
            double[] discountFactors,
            WillowTransitionGenerator transitionGenerator) {
        double[] nextStatePrices = new double[WillowNodeDefinition.NODE_COUNT];
        if (step == 0) {
            for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
                nextStatePrices[node] = currentStatePrices[node] * discountFactors[node];
            }
            return nextStatePrices;
        }
        double currentBrownianTime = yearFraction(dataDate, current);
        for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
            double statePrice = currentStatePrices[node] * discountFactors[node];
            for (var transition : transitionGenerator.getTransitions(currentBrownianTime, dt, node)) {
                nextStatePrices[transition.destNode] += statePrice * transition.probability;
            }
        }
        return nextStatePrices;
    }

    private double[] baseNodeRates(WillowModelType modelType,
            double referenceShortRate,
            double currentBrownianTime,
            LocalDate current) {
        double[] baseRates = new double[WillowNodeDefinition.NODE_COUNT];
        double[] zValues = WillowNodeDefinition.zValues();
        if (WillowModelType.NORMAL.equals(modelType)) {
            double scale = info.willowVolatility * Math.sqrt(currentBrownianTime);
            for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
                baseRates[node] = referenceShortRate + scale * zValues[node];
            }
            return baseRates;
        }
        if (referenceShortRate <= 0.0) {
            throw new IllegalArgumentException("LOG_NORMAL要求参考短率大于0: date=" + current
                    + ", rate=" + referenceShortRate);
        }
        double variance = info.willowVolatility * info.willowVolatility * currentBrownianTime;
        double logCenter = Math.log(referenceShortRate) - 0.5 * variance;
        double scale = Math.sqrt(variance);
        for (int node = 0; node < WillowNodeDefinition.NODE_COUNT; node++) {
            baseRates[node] = logCenter + scale * zValues[node];
        }
        return baseRates;
    }

    private double referenceDiscount(IrSpot irSpot, double valuationBaseDiscount, LocalDate date) {
        double discount = irSpot.discount(date) / valuationBaseDiscount;
        if (discount <= 0.0 || !Double.isFinite(discount)) {
            throw new IllegalArgumentException("WILLOW_REFERENCE_CURVE折现因子非法: date=" + date
                    + ", discount=" + discount);
        }
        return discount;
    }

    private double shortRateFromDiscount(double discount, double dt, LocalDate from, LocalDate to) {
        if (discount <= 0.0 || !Double.isFinite(discount)) {
            throw new IllegalArgumentException("参考曲线一步折现因子非法: from=" + from + ", to=" + to
                    + ", discount=" + discount);
        }
        return (1.0 / discount - 1.0) / dt;
    }

    private double shortRateDiscount(double shortRate, double dt, LocalDate date, int node) {
        double denominator = 1.0 + shortRate * dt;
        if (denominator <= 0.0 || !Double.isFinite(denominator)) {
            throw new IllegalArgumentException("节点短率导致折现因子非法: date=" + date
                    + ", node=" + node + ", rate=" + shortRate);
        }
        return 1.0 / denominator;
    }

    private double spreadAdjustedDiscountFactor(double calibratedDiscountFactor,
            double dt,
            double spreadOverYield,
            LocalDate date,
            int node) {
        if (spreadOverYield == 0.0) {
            return calibratedDiscountFactor;
        }
        if (calibratedDiscountFactor <= 0.0 || !Double.isFinite(calibratedDiscountFactor)) {
            throw new IllegalArgumentException("校准折现因子非法: date=" + date
                    + ", node=" + node + ", discountFactor=" + calibratedDiscountFactor);
        }
        double shortRate = (1.0 / calibratedDiscountFactor - 1.0) / dt;
        return shortRateDiscount(shortRate + spreadOverYield, dt, date, node);
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum;
    }

    private static double[] normalize(double[] values, double sum) {
        double[] weights = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            weights[i] = values[i] / sum;
        }
        return weights;
    }

    private Bond.BondMeasure buildMeasure(double unitValue,
            List<StructuredCashflow.Cashflow> cashflows,
            List<LocalDate> timeline,
            Map<LocalDate, String> exerciseByDate,
            CalibratedTree calibratedTree,
            SpreadCalibrationResult spreadResult) {
        FxSpot fxSpot = new FxSpot(Configure.getInstance().getValue(Constants.CFG.FX_BASE_CODE), marketData.fxSpot);
        double position = info.positionTrade;
        Bond.BondMeasure measure = new Bond.BondMeasure();
        measure.instrumentId = info.instrumentId;
        measure.productCode = Constants.PRODUCT_CODE.WILLOW_BOND;
        measure.dataDate = dataDate;
        measure.position = position;
        measure.valuationCcy = info.currencyCode;
        measure.valuation = unitValue * position;
        measure.valuationCny = measure.valuation * fxSpot.getFxrate(info.currencyCode);
        measure.valuationUnit = position == 0.0 ? 0.0 : measure.valuation / position;
        measure.spreadOverYield = spreadResult.spread;
        measure.cashFlowList = buildOutputCashFlowList(cashflows);
        measure.status = "SUCCESS";
        measure.logs = new ArrayList<>();
        if (spreadResult.warning != null) {
            measure.logs.add(Measure.warningLog(spreadResult.warning));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("willow_reference_curve", info.willowReferenceCurve);
        detail.put("willow_model_type", parseModelType(info.willowModelType).name());
        detail.put("willow_volatility", info.willowVolatility);
        detail.put("transition_source", "GENERATED_PDF_500_GRID_LINEAR_K_INTERPOLATION_MIN_LOCAL_VARIANCE");
        detail.put("short_rate_source", "WILLOW_REFERENCE_CURVE");
        detail.put("curve_fitting", "STATE_PRICE_ALPHA_CALIBRATION");
        detail.put("alpha_count", calibratedTree.alphas.length);
        detail.put("max_curve_fit_error", calibratedTree.maxFitError);
        detail.put("time_step_count", timeline.size() - 1);
        detail.put("exercise_dates", new ArrayList<>(exerciseByDate.keySet()));
        measure.detail = detail;
        measure.sensitivityList = new ArrayList<>();
        return measure;
    }

    private List<BaseCashFlow> buildOutputCashFlowList(List<StructuredCashflow.Cashflow> src) {
        List<BaseCashFlow> res = new ArrayList<>();
        if (src == null) {
            return res;
        }
        for (StructuredCashflow.Cashflow cf : src) {
            ScfCashFlow out = new ScfCashFlow();
            out.dataDate = dataDate;
            out.currencyCode = info.currencyCode;
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

    private static double[] filled(double value) {
        double[] values = new double[WillowNodeDefinition.NODE_COUNT];
        for (int i = 0; i < values.length; i++) {
            values[i] = value;
        }
        return values;
    }

    private double yearFraction(LocalDate from, LocalDate to) {
        return CurveFunc.timeFactor(from, to, info.dayCountBasis);
    }

    private static WillowModelType parseModelType(String raw) {
        String value = requireText(raw, "WILLOW_MODEL_TYPE").trim().toUpperCase();
        return WillowModelType.valueOf(value);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.trim();
    }

    private static class CalibratedTree {
        private final double[][] discountFactors;
        private final double[] alphas;
        private final double maxFitError;

        private CalibratedTree(double[][] discountFactors,
                double[] alphas,
                double maxFitError) {
            this.discountFactors = discountFactors;
            this.alphas = alphas;
            this.maxFitError = maxFitError;
        }

        private static CalibratedTree empty() {
            return new CalibratedTree(new double[0][0], new double[0], 0.0);
        }

        private double discountFactor(int step, int node) {
            return discountFactors[step][node];
        }
    }

    private static class SpreadCalibrationResult {
        private final double spread;
        private final String warning;

        private SpreadCalibrationResult(double spread, String warning) {
            this.spread = spread;
            this.warning = warning;
        }

        private static SpreadCalibrationResult success(double spread) {
            return new SpreadCalibrationResult(spread, null);
        }

        private static SpreadCalibrationResult warning(double spread, String warning) {
            return new SpreadCalibrationResult(spread, warning);
        }
    }

    public static class WillowBondInfo extends Bond.BondInfo {
        @JSONField(name = "WILLOW_REFERENCE_CURVE")
        public String willowReferenceCurve;
        @JSONField(name = "WILLOW_MODEL_TYPE")
        public String willowModelType;
        @JSONField(name = "WILLOW_VOLATILITY")
        public Double willowVolatility;
        @JSONField(name = "WILLOW_STEP_DAYS")
        public Integer willowStepDays = 30;
    }
}
