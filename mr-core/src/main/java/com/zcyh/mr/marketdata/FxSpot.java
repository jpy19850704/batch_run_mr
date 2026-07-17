package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.EngineConfiguration;
import com.zcyh.mr.support.EngineConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 外汇即期曲线
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class FxSpot implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(FxSpot.class);
    private volatile static FxSpot instance = null;
    private FxSpotInfo fxSpotInfo;
    private HashMap<String, Double> fxRates;
    private String baseCurrency;
    private String fxSpotBaseCurrency;

    public FxSpot(String baseCurrency, FxSpotInfo fxSpotInfos) {
        this(baseCurrency, fxSpotInfos, EngineConfiguration.getInstance().getValue(EngineConstants.CFG.FX_SPOT_BASE_CODE));
    }

    public FxSpot(String baseCurrency, FxSpotInfo fxSpotInfos, String fxSpotBaseCurrency) {
        this.baseCurrency = normalizeCurrency(baseCurrency, "CNY");
        this.fxSpotBaseCurrency = normalizeCurrency(fxSpotBaseCurrency, "USD");
        this.fxSpotInfo = fxSpotInfos;
        this.fxRates = new HashMap<>();
        buildFxRates();
    }

    private void buildFxRates() {
        if (fxSpotInfo == null || fxSpotInfo.curveData == null || fxSpotInfo.curveData.isEmpty()) {
            return;
        }
        Set<String> allKeys = new HashSet<>(fxSpotInfo.curveData.keySet());
        Set<String> directBasePairs = new HashSet<>();
        this.fxRates.put(fxSpotBaseCurrency, 1.0);
        for (String currencyPair : allKeys) {
            double rate = fxSpotInfo.curveData.get(currencyPair);
            validateRate(currencyPair, rate);
            String[] pairs = parseCurrencyPair(currencyPair);
            if (pairs == null) {
                continue;
            }
            if (fxSpotBaseCurrency.equals(pairs[0])) {
                this.fxRates.put(pairs[1], 1 / rate);
                directBasePairs.add(currencyPair);
            } else if (fxSpotBaseCurrency.equals(pairs[1])) {
                this.fxRates.put(pairs[0], rate);
                directBasePairs.add(currencyPair);
            }
        }

        Set<String> diff = new HashSet<>(allKeys);
        diff.removeAll(directBasePairs);
        for (String currencyPair : diff) {
            double rate = fxSpotInfo.curveData.get(currencyPair);
            validateRate(currencyPair, rate);
            String[] pairs = parseCurrencyPair(currencyPair);
            if (pairs == null) {
                continue;
            }
            Set<String> validCurrency = fxRates.keySet();
            if (validCurrency.contains(pairs[0]) && validCurrency.contains(pairs[1])) {
                continue;
            } else if (!validCurrency.contains(pairs[0]) && validCurrency.contains(pairs[1])) {
                this.fxRates.put(pairs[0], rate * fxRates.get(pairs[1]));
            } else if (validCurrency.contains(pairs[0]) && !validCurrency.contains(pairs[1])) {
                this.fxRates.put(pairs[1], fxRates.get(pairs[0]) / rate);
            } else {
                throw new IllegalStateException("FX 货币对无法通过 " + fxSpotBaseCurrency + " 链路推导: " + currencyPair);
            }
        }
        validateAllPairsResolved(allKeys);
    }

    /**
     * 汇率返回，一个单位的currency返回多少baseCurrency
     *
     * @param currency:
     * @return double
     * @author lsd
     * @date 2024/7/17 16:03
     */
    public double getFxrate(String currency) {
        String currencyCode = normalizeCurrency(currency, null);
        if (currencyCode == null) {
            return 1;
        }
        Double currencyRate = fxRates.get(currencyCode);
        Double baseRate = fxRates.get(baseCurrency);
        if (currencyRate == null || baseRate == null || baseRate == 0) {
            throw new IllegalStateException("FX 汇率缺失: currency=" + currencyCode
                    + ", baseCurrency=" + baseCurrency
                    + ", fxSpotBaseCurrency=" + fxSpotBaseCurrency);
        }
        return currencyRate / baseRate;
    }

    /**
     * 获取汇率
     *
     * @param one:
     * @param other:
     * @return double
     * @author lsd
     * @date 2024/7/17 16:06
     */
    public double getFxrate(String one, String other) {
        String oneCurrency = normalizeCurrency(one, null);
        String otherCurrency = normalizeCurrency(other, null);
        Set<String> keys = fxRates.keySet();
        double rate = 1;
        if (oneCurrency == null || otherCurrency == null || !keys.contains(oneCurrency) || !keys.contains(otherCurrency)) {
            throw new IllegalStateException("FX 汇率缺失: one=" + oneCurrency
                    + ", other=" + otherCurrency
                    + ", fxSpotBaseCurrency=" + fxSpotBaseCurrency);
        }
        rate = fxRates.get(otherCurrency) / fxRates.get(oneCurrency);
        return rate;
    }

    public double getSensitivityFxrate(String currency) {
        String currencyCode = normalizeCurrency(currency, null);
        if (fxSpotBaseCurrency.equals(currencyCode)) {
            return 1.01;
        }
        Set<String> keys = fxRates.keySet();
        double rate = 1;
        if (keys.contains(fxSpotBaseCurrency) && keys.contains(currencyCode)) {
            rate = fxRates.get(currencyCode) / fxRates.get(fxSpotBaseCurrency);
            if (rate >= 1) {
                return rate * 1.01;
            } else {
                return rate / 1.01;
            }
        }
        return rate;
    }

    private void validateRate(String currencyPair, double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0) {
            throw new IllegalArgumentException("FX_SPOT 汇率必须大于0: currencyPair=" + currencyPair
                    + ", rate=" + rate
                    + ", fxSpotBaseCurrency=" + fxSpotBaseCurrency);
        }
    }

    private void validateAllPairsResolved(Set<String> currencyPairs) {
        for (String currencyPair : currencyPairs) {
            String[] pairs = parseCurrencyPair(currencyPair);
            if (pairs == null) {
                continue;
            }
            if (!fxRates.containsKey(pairs[0]) || !fxRates.containsKey(pairs[1])) {
                throw new IllegalStateException("FX 货币对无法通过 " + fxSpotBaseCurrency
                        + " 链路完整推导: " + currencyPair
                        + ", resolvedCurrencies=" + fxRates.keySet());
            }
        }
    }

    private String[] parseCurrencyPair(String currencyPair) {
        if (currencyPair == null) {
            return null;
        }
        String[] pairs = currencyPair.toUpperCase(Locale.ROOT).split("/");
        if (pairs.length != 2 || pairs[0].isBlank() || pairs[1].isBlank()) {
            log.warn("FX 货币对格式错误: {}", currencyPair);
            return null;
        }
        return new String[]{pairs[0].trim(), pairs[1].trim()};
    }

    private String normalizeCurrency(String currency, String defaultCurrency) {
        if (currency == null || currency.trim().isEmpty()) {
            return defaultCurrency;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    public static class FxSpotInfo implements Serializable {
        @JSONField(name = "CURVE_TYPE")
        public String curveType;
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;
        @JSONField(name = "P_DATA_DATE", format = "yyyyMMdd")
        public LocalDate pDataDate;
        // 中间值
        public HashMap<String, Double> curveData = new HashMap<>();
    }
}
