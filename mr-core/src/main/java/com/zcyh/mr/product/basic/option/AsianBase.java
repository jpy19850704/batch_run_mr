package com.zcyh.mr.product.basic.option;

import com.zcyh.mr.product.basic.validation.TradeInfo;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.product.basic.validation.ProductInputField;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.VolSurfacePoint;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.basic.common.OptionMeasure;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 亚式产品通用基类。
 * 职责：
 * 1. 统一处理输入校验、历史均值计算、估值流程与 Greeks 数值法；
 * 2. 子类仅负责市场参数装配与结果后处理（如敏感性）。
 */
public abstract class AsianBase<I extends AsianBase.AsianBaseTradeInfo, M extends OptionMeasure> {

    protected final LocalDate dataDate;
    protected final I info;
    protected final MarketData marketData;
    protected final double pos;
    protected M measure;
    private List<LocalDate> observationDates;

    protected final Middle middle = new Middle();

    protected AsianBase(LocalDate dataDate, I tradeInfo, MarketData marketData) {
        this.dataDate = dataDate;
        this.info = tradeInfo;
        this.marketData = marketData;
        double sign = "B".equalsIgnoreCase(safeText(tradeInfo == null ? null : tradeInfo.buyOrSell)) ? 1.0 : -1.0;
        double contractSize = tradeInfo == null || tradeInfo.contractSize == null ? 0.0 : tradeInfo.contractSize;
        this.pos = sign * contractSize;
    }

    /**
     * 完整估值（含 Greeks）。
     */
    public M calc() {
        List<String> validationErrors = validate();
        if (!validationErrors.isEmpty()) {
            M err = newMeasure();
            err.instrumentId = info == null ? null : info.instrumentId;
            err.productCode = resolveProductCode();
            err.dataDate = dataDate;
            err.status = "ERROR";
            err.logs = Measure.errorLogs(validationErrors);
            this.measure = err;
            return err;
        }

        try {
            M result = calc(this.marketData);
            result.impliedVol = middle.sigmaEq;
            result.delta = getDelta();
            result.gamma = getGamma();
            result.theta = getTheta();
            result.vega = getVega();
            result.productCode = resolveProductCode();
            result.dataDate = dataDate;
            result.status = "SUCCESS";
            result.logs = Collections.emptyList();
            result.detail = buildDetail();

            this.measure = result;
            afterSuccessfulCalc(result);
            return result;
        } catch (Exception ex) {
            M err = newMeasure();
            err.instrumentId = info == null ? null : info.instrumentId;
            err.productCode = resolveProductCode();
            err.dataDate = dataDate;
            err.status = "ERROR";
            err.logs = Measure.errorLogs(resolveCalcErrorPrefix() + ": " + ex.getMessage());
            this.measure = err;
            return err;
        }
    }

    /**
     * 场景估值（仅重算估值，不计算 Greeks）。
     */
    public M calc(MarketData md) {
        String settleType = resolveSettleType(info.settleType);
        boolean call = "CALL".equalsIgnoreCase(info.callOrPut);
        boolean cash = isCashSettle(settleType);
        int maturityDays = (int) ChronoUnit.DAYS.between(dataDate, info.maturityDate);
        int settleDays = (int) ChronoUnit.DAYS.between(dataDate, info.settleDate);
        double maturityT = maturityDays / 365.0;

        MarketFactors factors = resolveMarketFactors(
                md, call, cash, maturityDays, settleDays, maturityT, settleDays / 365.0);
        ObservationStat stat = buildObservationStat(md);
        double physicalForwardRatio = cash ? 1.0 : factors.physicalForwardRatio;
        if (!Double.isFinite(physicalForwardRatio) || physicalForwardRatio <= 0.0) {
            throw new IllegalArgumentException("实物交割远期比率必须为正有限数");
        }
        double pricingStrike = info.strikePrice / physicalForwardRatio;
        double presentValueFactor = factors.paymentDiscountFactor * physicalForwardRatio;

        AsianUtil asianUtil = new AsianUtil(call, pricingStrike, factors.rd, factors.rf);
        double equivalentStrike = asianUtil.equivalentStrike(
                stat.totalObsCount, stat.pastObsCount, stat.averagePast);
        double sigma = resolveMarketSigma(
                call, factors, equivalentStrike, maturityT, stat.futureObservationTimes);
        AsianUtil.PriceResult priceResult = asianUtil.price(
                factors.spot,
                sigma,
                presentValueFactor,
                stat.futureObservationTimes,
                stat.pastObsCount,
                stat.averagePast);

        middle.call = call;
        middle.spot = factors.spot;
        middle.baseSigma = sigma;
        middle.rd = factors.rd;
        middle.rf = factors.rf;
        middle.presentValueFactor = presentValueFactor;
        middle.futureObservationTimes = stat.futureObservationTimes;
        middle.pastObsCount = stat.pastObsCount;
        middle.averagePast = stat.averagePast;
        middle.pricingStrike = pricingStrike;
        middle.basePrice = priceResult.price;
        middle.forwardEq = priceResult.forwardEq;
        middle.strikeEq = priceResult.strikeEq;
        middle.sigmaEq = priceResult.sigmaEq;
        middle.d1Eq = priceResult.d1Eq;
        middle.d2Eq = priceResult.d2Eq;
        middle.pastWeight = priceResult.pastWeight;
        middle.futureWeight = priceResult.futureWeight;

        M m = newMeasure();
        m.instrumentId = info.instrumentId;
        m.position = pos;
        m.spotPrice = factors.spot;
        m.fwdPrice = priceResult.forwardEq;
        m.valuationUnit = priceResult.price;
        m.valuation = m.valuationUnit * pos;
        m.valuationCcy = resolveValuationCurrency(md);
        m.valuationCny = m.valuation * resolveValuationToCnyFx(md, m.valuationCcy);
        m.impliedVol = priceResult.sigmaEq;
        return m;
    }

    private double resolveMarketSigma(
            boolean call,
            MarketFactors factors,
            double equivalentStrike,
            double maturityT,
            List<Double> futureObservationTimes) {
        if (futureObservationTimes.isEmpty() || equivalentStrike <= 0.0) {
            return 0.0;
        }
        EurOptUtil util = new EurOptUtil(
                call,
                true,
                factors.spot,
                equivalentStrike,
                factors.rd,
                factors.rf,
                Math.max(maturityT, 1.0 / 365.0),
                Math.max(maturityT, 1.0 / 365.0),
                factors.volCurve,
                "black");
        return util.getSigma();
    }

    /**
     * 子类可覆盖，成功后追加敏感性等结果。
     */
    protected void afterSuccessfulCalc(M measure) {
        // 默认不做额外处理
    }

    protected String resolveCalcErrorPrefix() {
        return "ASIAN计算失败";
    }

    protected abstract M newMeasure();

    protected abstract String resolveProductCode();

    protected abstract String resolveValuationCurrency(MarketData md);

    protected abstract double resolveValuationToCnyFx(MarketData md, String valuationCurrency);

    protected abstract MarketFactors resolveMarketFactors(
            MarketData md,
            boolean call,
            boolean cash,
            int maturityDays,
            int settleDays,
            double maturityT,
            double settleT);

    protected abstract void validateMarketData(List<String> errors, MarketData md);

    protected Map<String, Object> buildDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("AVERAGE_PAST", middle.averagePast);
        detail.put("PAST_WEIGHT", middle.pastWeight);
        detail.put("FUTURE_WEIGHT", middle.futureWeight);
        detail.put("FORWARD_EQ", middle.forwardEq);
        detail.put("STRIKE_EQ", middle.strikeEq);
        detail.put("SIGMA_EQ", middle.sigmaEq);
        detail.put("D1_EQ", middle.d1Eq);
        detail.put("D2_EQ", middle.d2Eq);
        return detail;
    }

    private double reprice(
            double spot,
            double sigma,
            double presentValueFactor,
            List<Double> futureObservationTimes) {
        AsianUtil asianUtil = new AsianUtil(middle.call, middle.pricingStrike, middle.rd, middle.rf);
        AsianUtil.PriceResult result = asianUtil.price(
                spot,
                sigma,
                presentValueFactor,
                futureObservationTimes,
                middle.pastObsCount,
                middle.averagePast);
        return result.price;
    }

    private double getDelta() {
        double eps = Math.max(1e-6, Math.abs(middle.spot) * EurOptUtil.GreekEps.delta);
        double up = reprice(middle.spot + eps, middle.baseSigma,
                middle.presentValueFactor, middle.futureObservationTimes);
        double down = reprice(Math.max(middle.spot - eps, 1e-8), middle.baseSigma,
                middle.presentValueFactor, middle.futureObservationTimes);
        return (up - down) / (2.0 * eps);
    }

    private double getGamma() {
        double eps = Math.max(1e-6, Math.abs(middle.spot) * EurOptUtil.GreekEps.gamma);
        double up = reprice(middle.spot + eps, middle.baseSigma,
                middle.presentValueFactor, middle.futureObservationTimes);
        double mid = middle.basePrice;
        double down = reprice(Math.max(middle.spot - eps, 1e-8), middle.baseSigma,
                middle.presentValueFactor, middle.futureObservationTimes);
        return (up - 2.0 * mid + down) / (eps * eps);
    }

    private double getTheta() {
        double eps = EurOptUtil.GreekEps.theta;
        List<Double> shiftedTimes = new ArrayList<>(middle.futureObservationTimes.size());
        for (Double time : middle.futureObservationTimes) {
            shiftedTimes.add(time + eps);
        }
        double shiftedPresentValueFactor = middle.presentValueFactor * Math.exp(-middle.rd * eps);
        double up = reprice(middle.spot, middle.baseSigma, shiftedPresentValueFactor, shiftedTimes);
        return up - middle.basePrice;
    }

    private double getVega() {
        double eps = EurOptUtil.GreekEps.vega;
        double sigmaUp = middle.baseSigma + eps;
        double sigmaDown = Math.max(middle.baseSigma - eps, 1e-8);
        double up = reprice(middle.spot, sigmaUp,
                middle.presentValueFactor, middle.futureObservationTimes);
        double down = reprice(middle.spot, sigmaDown,
                middle.presentValueFactor, middle.futureObservationTimes);
        return (up - down) / (sigmaUp - sigmaDown) / 100.0;
    }

    private List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (info == null) {
            errors.add("交易输入为空");
            return errors;
        }
        if (!hasText(info.callOrPut) || (!"CALL".equalsIgnoreCase(info.callOrPut)
                && !"PUT".equalsIgnoreCase(info.callOrPut))) {
            errors.add("CALL_OR_PUT 非法: " + info.callOrPut);
        }
        if (!hasText(info.buyOrSell) || (!"B".equalsIgnoreCase(info.buyOrSell)
                && !"S".equalsIgnoreCase(info.buyOrSell))) {
            errors.add("BUY_OR_SELL 非法: " + info.buyOrSell);
        }
        if (info.contractSize == null || !Double.isFinite(info.contractSize) || info.contractSize <= 0.0) {
            errors.add("CONTRACT_SIZE 无效: " + info.contractSize);
        }
        if (info.strikePrice == null || info.strikePrice <= 0) {
            errors.add("STRIKE_PRICE 无效: " + info.strikePrice);
        }
        if (info.maturityDate == null) {
            errors.add("MATURITY_DATE 未设置");
        } else if (info.maturityDate.isBefore(dataDate)) {
            errors.add("MATURITY_DATE 已过期: " + info.maturityDate + " < " + dataDate);
        }
        if (info.settleDate == null) {
            errors.add("SETTLE_DATE 未设置");
        } else if (info.maturityDate != null && info.settleDate.isBefore(info.maturityDate)) {
            errors.add("SETTLE_DATE 不得早于 MATURITY_DATE");
        }
        List<LocalDate> dates = Collections.emptyList();
        try {
            dates = getObservationDates();
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
        }
        if (info.maturityDate != null
                && dates.stream().anyMatch(date -> date.isAfter(info.maturityDate))) {
            errors.add("OBS_DATES 不得晚于 MATURITY_DATE");
        }

        String settleType = resolveSettleType(info.settleType);
        if (!"CASH".equalsIgnoreCase(settleType) && !"PHYSICAL".equalsIgnoreCase(settleType)) {
            errors.add("SETTLE_TYPE 非法: " + info.settleType);
        }

        long pastCount = dates.stream().filter(date -> !date.isAfter(dataDate)).count();
        if (pastCount > 0) {
            if (!hasText(info.fixingId)) {
                errors.add("历史观察段存在时 FIXING_ID 必填");
            } else if (marketData == null || marketData.fixingRate == null
                    || !marketData.fixingRate.containsKey(info.fixingId)) {
                errors.add("市场数据缺少定盘序列: " + info.fixingId);
            }
        }
        validateMarketData(errors, marketData);
        return errors;
    }

    private ObservationStat buildObservationStat(MarketData md) {
        List<LocalDate> dates = getObservationDates();
        int totalObsCount = dates.size();
        List<LocalDate> pastDates = new ArrayList<>();
        List<Double> futureTimes = new ArrayList<>();
        for (LocalDate date : dates) {
            if (date.isAfter(dataDate)) {
                futureTimes.add(ChronoUnit.DAYS.between(dataDate, date) / 365.0);
            } else {
                pastDates.add(date);
            }
        }
        int pastObsCount = pastDates.size();
        Double averagePast = null;
        if (pastObsCount > 0) {
            Fixing fixing = new Fixing(md.fixingRate.get(info.fixingId));
            double sum = 0.0;
            for (LocalDate d : pastDates) {
                double value = fixing.getRate(d);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("FIXING值非法: " + d + "=" + value);
                }
                sum += value;
            }
            averagePast = sum / pastObsCount;
        }
        return new ObservationStat(totalObsCount, pastObsCount, averagePast, futureTimes);
    }

    protected static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    protected static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    protected static String resolveSettleType(String settleType) {
        return settleType == null ? "" : settleType.trim().toUpperCase(Locale.ROOT);
    }

    protected static boolean isCashSettle(String settleType) {
        return "CASH".equalsIgnoreCase(settleType);
    }

    private List<LocalDate> getObservationDates() {
        if (observationDates != null) {
            return observationDates;
        }
        if (!hasText(info.obsDates)) {
            throw new IllegalArgumentException("OBS_DATES 不能为空");
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (String value : info.obsDates.split(",")) {
            String dateText = safeText(value);
            if (dateText.isEmpty()) {
                continue;
            }
            try {
                dates.add(LocalDate.parse(dateText));
            } catch (Exception e) {
                throw new IllegalArgumentException("OBS_DATES 日期格式错误(yyyy-MM-dd): " + dateText);
            }
        }
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("OBS_DATES 不能为空");
        }
        observationDates = Collections.unmodifiableList(new ArrayList<>(dates));
        return observationDates;
    }

    /**
     * 亚式通用输入字段。
     */
    public static class AsianBaseTradeInfo implements TradeInfo {
        @ProductInputField(required = true)
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @ProductInputField(required = true)
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @ProductInputField(required = true, allowedValues = {"Call", "Put"}, ignoreCase = true)
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @ProductInputField(required = true, allowedValues = {"B", "S"}, ignoreCase = true)
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @ProductInputField(required = true, finite = true, min = "0", minInclusive = false)
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @ProductInputField(required = true)
        @JSONField(name = "MATURITY_DATE", format = "yyyy-MM-dd")
        public LocalDate maturityDate;
        @ProductInputField(required = true)
        @JSONField(name = "SETTLE_DATE", format = "yyyy-MM-dd")
        public LocalDate settleDate;
        @ProductInputField(required = true, allowedValues = {"CASH", "PHYSICAL"}, ignoreCase = true)
        @JSONField(name = "SETTLE_TYPE")
        public String settleType;
        @ProductInputField
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @ProductInputField(required = true)
        @JSONField(name = "OBS_DATES")
        public String obsDates;
        @ProductInputField(required = true)
        @JSONField(name = "CURRENCY_CODE")
        public String currencyCode;
    }

    /**
     * 市场参数容器。
     */
    protected static final class MarketFactors {
        public final double spot;
        public final double rd;
        public final double rf;
        public final List<VolSurfacePoint> volCurve;
        public final double paymentDiscountFactor;
        public final double physicalForwardRatio;

        public MarketFactors(
                double spot,
                double rd,
                double rf,
                List<VolSurfacePoint> volCurve,
                double paymentDiscountFactor,
                double physicalForwardRatio) {
            this.spot = spot;
            this.rd = rd;
            this.rf = rf;
            this.volCurve = volCurve;
            this.paymentDiscountFactor = paymentDiscountFactor;
            this.physicalForwardRatio = physicalForwardRatio;
        }
    }

    private static final class ObservationStat {
        private final int totalObsCount;
        private final int pastObsCount;
        private final Double averagePast;
        private final List<Double> futureObservationTimes;

        private ObservationStat(
                int totalObsCount,
                int pastObsCount,
                Double averagePast,
                List<Double> futureObservationTimes) {
            this.totalObsCount = totalObsCount;
            this.pastObsCount = pastObsCount;
            this.averagePast = averagePast;
            this.futureObservationTimes = Collections.unmodifiableList(new ArrayList<>(futureObservationTimes));
        }
    }

    /**
     * 中间变量缓存。
     */
    protected static final class Middle {
        private boolean call;
        private double spot;
        private double baseSigma;
        private double rd;
        private double rf;
        private double presentValueFactor;
        private List<Double> futureObservationTimes;
        private int pastObsCount;
        private Double averagePast;
        private double pricingStrike;
        private double basePrice;
        private double forwardEq;
        private double strikeEq;
        private double sigmaEq;
        private Double d1Eq;
        private Double d2Eq;
        private double pastWeight;
        private double futureWeight;
    }
}
