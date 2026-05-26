package com.zcyh.mr.product.basic.option;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.marketdata.Fixing;
import com.zcyh.mr.marketdata.MarketData;
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
public abstract class AsianBase<I extends AsianBase.AsianBaseInfo, M extends OptionMeasure> {

    protected final LocalDate dataDate;
    protected final I info;
    protected final MarketData marketData;
    protected final double pos;
    protected M measure;

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
        double settleT = settleDays / 365.0;

        MarketFactors factors = resolveMarketFactors(
                md, call, cash, maturityDays, settleDays, maturityT, settleT);
        ObservationStat stat = buildObservationStat(md);
        double obsStartT = ChronoUnit.DAYS.between(dataDate, info.obsStartDate) / 365.0;
        double obsEndT = ChronoUnit.DAYS.between(dataDate, info.obsEndDate) / 365.0;

        AsianUtil asianUtil = new AsianUtil(call, info.strikePrice, factors.rd, factors.rf);
        AsianUtil.PriceResult priceResult = asianUtil.price(
                factors.spot,
                factors.baseSigma,
                settleT,
                obsStartT,
                obsEndT,
                stat.totalObsCount,
                stat.pastObsCount,
                stat.averagePast);

        middle.call = call;
        middle.spot = factors.spot;
        middle.baseSigma = factors.baseSigma;
        middle.rd = factors.rd;
        middle.rf = factors.rf;
        middle.settleT = settleT;
        middle.obsStartT = obsStartT;
        middle.obsEndT = obsEndT;
        middle.totalObsCount = stat.totalObsCount;
        middle.pastObsCount = stat.pastObsCount;
        middle.averagePast = stat.averagePast;
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

    private double reprice(double spot, double sigma, double settleT, double obsStartT, double obsEndT) {
        AsianUtil asianUtil = new AsianUtil(middle.call, info.strikePrice, middle.rd, middle.rf);
        AsianUtil.PriceResult result = asianUtil.price(
                spot,
                sigma,
                settleT,
                obsStartT,
                obsEndT,
                middle.totalObsCount,
                middle.pastObsCount,
                middle.averagePast);
        return result.price;
    }

    private double getDelta() {
        double eps = Math.max(1e-6, Math.abs(middle.spot) * EurOptUtil.GreekEps.delta);
        double up = reprice(middle.spot + eps, middle.baseSigma, middle.settleT, middle.obsStartT, middle.obsEndT);
        double down = reprice(Math.max(middle.spot - eps, 1e-8), middle.baseSigma,
                middle.settleT, middle.obsStartT, middle.obsEndT);
        return (up - down) / (2.0 * eps);
    }

    private double getGamma() {
        double eps = Math.max(1e-6, Math.abs(middle.spot) * EurOptUtil.GreekEps.gamma);
        double up = reprice(middle.spot + eps, middle.baseSigma, middle.settleT, middle.obsStartT, middle.obsEndT);
        double mid = middle.basePrice;
        double down = reprice(Math.max(middle.spot - eps, 1e-8), middle.baseSigma,
                middle.settleT, middle.obsStartT, middle.obsEndT);
        return (up - 2.0 * mid + down) / (eps * eps);
    }

    private double getTheta() {
        double eps = EurOptUtil.GreekEps.theta;
        double up = reprice(middle.spot, middle.baseSigma,
                middle.settleT + eps, middle.obsStartT + eps, middle.obsEndT + eps);
        return up - middle.basePrice;
    }

    private double getVega() {
        double eps = EurOptUtil.GreekEps.vega;
        double sigmaUp = middle.baseSigma + eps;
        double sigmaDown = Math.max(middle.baseSigma - eps, 1e-8);
        double up = reprice(middle.spot, sigmaUp, middle.settleT, middle.obsStartT, middle.obsEndT);
        double down = reprice(middle.spot, sigmaDown, middle.settleT, middle.obsStartT, middle.obsEndT);
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
        } else if (!info.maturityDate.isAfter(dataDate)) {
            errors.add("MATURITY_DATE 已过期: " + info.maturityDate + " <= " + dataDate);
        }
        if (info.settleDate == null) {
            errors.add("SETTLE_DATE 未设置");
        }
        if (info.obsStartDate == null) {
            errors.add("OBS_START_DATE 未设置");
        }
        if (info.obsEndDate == null) {
            errors.add("OBS_END_DATE 未设置");
        }
        if (info.obsStartDate != null && info.obsEndDate != null && info.obsStartDate.isAfter(info.obsEndDate)) {
            errors.add("OBS_START_DATE 大于 OBS_END_DATE");
        }
        if (info.maturityDate != null && info.obsEndDate != null && info.obsEndDate.isAfter(info.maturityDate)) {
            errors.add("OBS_END_DATE 不得晚于 MATURITY_DATE");
        }
        int totalObsCount = countObservationDays(info.obsStartDate, info.obsEndDate);
        if (totalObsCount <= 0) {
            errors.add("观察区间无有效观察点");
        }

        String settleType = resolveSettleType(info.settleType);
        if (!"CASH".equalsIgnoreCase(settleType) && !"PHYSICAL".equalsIgnoreCase(settleType)) {
            errors.add("SETTLE_TYPE 非法: " + info.settleType);
        }

        int pastCount = countPastObservationDays(info.obsStartDate, info.obsEndDate, dataDate);
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
        int totalObsCount = countObservationDays(info.obsStartDate, info.obsEndDate);
        int pastObsCount = countPastObservationDays(info.obsStartDate, info.obsEndDate, dataDate);
        Double averagePast = null;
        if (pastObsCount > 0) {
            Fixing fixing = new Fixing(md.fixingRate.get(info.fixingId));
            LocalDate pastEnd = info.obsEndDate.isBefore(dataDate) ? info.obsEndDate : dataDate;
            double sum = 0.0;
            int count = 0;
            for (LocalDate d = info.obsStartDate; !d.isAfter(pastEnd); d = d.plusDays(1)) {
                double value = fixing.getRate(d);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("FIXING值非法: " + d + "=" + value);
                }
                sum += value;
                count++;
            }
            pastObsCount = count;
            averagePast = count == 0 ? null : sum / count;
        }
        return new ObservationStat(totalObsCount, pastObsCount, averagePast);
    }

    protected static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    protected static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    protected static String resolveSettleType(String settleType) {
        if (!hasText(settleType)) {
            return "CASH";
        }
        return settleType.trim().toUpperCase(Locale.ROOT);
    }

    protected static boolean isCashSettle(String settleType) {
        return "CASH".equalsIgnoreCase(settleType);
    }

    private int countObservationDays(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        return (int) Math.max(days, 0);
    }

    private int countPastObservationDays(LocalDate startDate, LocalDate endDate, LocalDate valuationDate) {
        if (startDate == null || endDate == null || valuationDate == null || startDate.isAfter(endDate)) {
            return 0;
        }
        if (valuationDate.isBefore(startDate)) {
            return 0;
        }
        LocalDate pastEnd = endDate.isBefore(valuationDate) ? endDate : valuationDate;
        long days = ChronoUnit.DAYS.between(startDate, pastEnd) + 1;
        return (int) Math.max(days, 0);
    }

    /**
     * 亚式通用输入字段。
     */
    public static class AsianBaseInfo {
        @JSONField(name = "INSTRUMENT_ID")
        public String instrumentId;
        @JSONField(name = "PRODUCT_CODE")
        public String productCode;
        @JSONField(name = "CALL_OR_PUT")
        public String callOrPut;
        @JSONField(name = "BUY_OR_SELL")
        public String buyOrSell;
        @JSONField(name = "CONTRACT_SIZE", defaultValue = "1")
        public Double contractSize;
        @JSONField(name = "STRIKE_PRICE")
        public Double strikePrice;
        @JSONField(name = "MATURITY_DATE", format = "yyyyMMdd")
        public LocalDate maturityDate;
        @JSONField(name = "SETTLE_DATE", format = "yyyyMMdd")
        public LocalDate settleDate;
        @JSONField(name = "SETTLE_TYPE")
        public String settleType;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @JSONField(name = "OBS_START_DATE", format = "yyyyMMdd")
        public LocalDate obsStartDate;
        @JSONField(name = "OBS_END_DATE", format = "yyyyMMdd")
        public LocalDate obsEndDate;
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
        public final double baseSigma;

        public MarketFactors(double spot, double rd, double rf, double baseSigma) {
            this.spot = spot;
            this.rd = rd;
            this.rf = rf;
            this.baseSigma = baseSigma;
        }
    }

    private static final class ObservationStat {
        private final int totalObsCount;
        private final int pastObsCount;
        private final Double averagePast;

        private ObservationStat(int totalObsCount, int pastObsCount, Double averagePast) {
            this.totalObsCount = totalObsCount;
            this.pastObsCount = pastObsCount;
            this.averagePast = averagePast;
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
        private double settleT;
        private double obsStartT;
        private double obsEndT;
        private int totalObsCount;
        private int pastObsCount;
        private Double averagePast;
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
