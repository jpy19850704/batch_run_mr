package com.zcyh.mr.springboot.scenario;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.basic.util.Configure;
import com.zcyh.mr.core.Constants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * market_input 数据准入校验器。
 */
public class MarketInputScenarioValidator {
    private static final String FX_SPOT = "FX_SPOT";
    private final String fxSpotBaseCurrency;

    public MarketInputScenarioValidator() {
        this(resolveDefaultFxSpotBaseCurrency());
    }

    public MarketInputScenarioValidator(String fxSpotBaseCurrency) {
        this.fxSpotBaseCurrency = normalizeCurrency(fxSpotBaseCurrency, "USD");
    }

    public void validateFxSpotRows(List<Map<String, Object>> rows) {
        validateParsedFxSpotRows(ScenarioMarketCurveRow.parseAll(rows));
    }

    void validateParsedFxSpotRows(List<ScenarioMarketCurveRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<LocalDate, FxSpotDateState> states = new LinkedHashMap<LocalDate, FxSpotDateState>();
        for (ScenarioMarketCurveRow row : rows) {
            String marketDataType = row.getCurveType();
            if (!FX_SPOT.equals(marketDataType)) {
                continue;
            }
            LocalDate dataDate = row.getDataDate();
            String curveId = row.getCurveId();
            String contentText = row.getContentText();
            if (dataDate == null || curveId == null || contentText == null) {
                throw new IllegalStateException("FX_SPOT 市场数据字段缺失: dataDate=" + safeText(dataDate)
                        + ", curveId=" + safeText(curveId));
            }
            if (row.getParseException() != null) {
                RuntimeException ex = row.getParseException();
                throw new IllegalStateException("FX_SPOT 市场数据JSON解析失败: dataDate=" + dataDate
                        + ", curveId=" + curveId + ", reason=" + ex.getMessage(), ex);
            }
            if (!row.isCurveDataPresent()) {
                throw new IllegalStateException("FX_SPOT 市场数据JSON解析失败: dataDate=" + dataDate
                        + ", curveId=" + curveId + ", reason=CURVE_DATA 为空");
            }
            FxSpotDateState state = states.computeIfAbsent(dataDate,
                    key -> new FxSpotDateState(key, fxSpotBaseCurrency));
            state.addContainer(curveId);
            List<JSONObject> points = row.getCurveData();
            for (int i = 0; i < points.size(); i++) {
                JSONObject point = points.get(i);
                if (point == null) {
                    continue;
                }
                String currencyPair = normalize(toStringValue(point.get("CURRENCY")));
                if (currencyPair == null) {
                    throw new IllegalStateException("FX_SPOT 货币对字段缺失: dataDate=" + dataDate
                            + ", curveId=" + curveId + ", index=" + i);
                }
                BigDecimal rate = readPositiveRate(point, dataDate, curveId, currencyPair, i);
                state.addCurrencyPair(currencyPair.toUpperCase(), curveId);
            }
        }
        for (FxSpotDateState state : states.values()) {
            state.validate();
        }
    }

    private BigDecimal readPositiveRate(
            JSONObject point,
            LocalDate dataDate,
            String curveId,
            String currencyPair,
            int index) {
        Object value = point.get("RATE");
        if (value == null) {
            throw new IllegalStateException("FX_SPOT 汇率字段缺失: dataDate=" + dataDate
                    + ", curveId=" + curveId
                    + ", currencyPair=" + currencyPair
                    + ", index=" + index);
        }
        try {
            BigDecimal rate = new BigDecimal(value.toString());
            if (rate.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("FX_SPOT 汇率必须大于0: dataDate=" + dataDate
                        + ", curveId=" + curveId
                        + ", currencyPair=" + currencyPair
                        + ", rate=" + value);
            }
            return rate;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("FX_SPOT 汇率格式错误: dataDate=" + dataDate
                    + ", curveId=" + curveId
                    + ", currencyPair=" + currencyPair
                    + ", rate=" + value, ex);
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String toStringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalizeCurrency(String currency, String defaultCurrency) {
        if (currency == null || currency.trim().isEmpty()) {
            return defaultCurrency;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private static String resolveDefaultFxSpotBaseCurrency() {
        String value = Configure.getInstance().getValue(Constants.CFG.FX_SPOT_BASE_CODE);
        if (value == null || value.trim().isEmpty()) {
            return "USD";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static class FxSpotDateState {
        private final LocalDate dataDate;
        private final String fxSpotBaseCurrency;
        private final Map<String, List<String>> pairSources = new LinkedHashMap<String, List<String>>();
        private final Set<String> containers = new LinkedHashSet<String>();

        private FxSpotDateState(LocalDate dataDate, String fxSpotBaseCurrency) {
            this.dataDate = dataDate;
            this.fxSpotBaseCurrency = fxSpotBaseCurrency;
        }

        private void addContainer(String curveId) {
            containers.add(curveId);
        }

        private void addCurrencyPair(String currencyPair, String curveId) {
            String[] currencies = parseCurrencyPair(currencyPair, curveId);
            if (currencies[0].equals(currencies[1])) {
                throw new IllegalStateException("FX_SPOT 货币对币种不能相同: dataDate=" + dataDate
                        + ", currencyPair=" + currencyPair + ", curveId=" + curveId);
            }
            if (!fxSpotBaseCurrency.equals(currencies[0]) && !fxSpotBaseCurrency.equals(currencies[1])) {
                throw new IllegalStateException("FX_SPOT 货币对必须包含基础币种: dataDate=" + dataDate
                        + ", currencyPair=" + currencyPair
                        + ", expectedBaseCurrency=" + fxSpotBaseCurrency
                        + ", curveId=" + curveId);
            }
            String canonicalPair = currencies[0].compareTo(currencies[1]) <= 0
                    ? currencies[0] + "/" + currencies[1]
                    : currencies[1] + "/" + currencies[0];
            pairSources.computeIfAbsent(canonicalPair, key -> new ArrayList<String>())
                    .add(currencyPair + "@" + curveId);
        }

        private String[] parseCurrencyPair(String currencyPair, String curveId) {
            int separatorIndex = currencyPair.indexOf('/');
            if (separatorIndex <= 0
                    || separatorIndex == currencyPair.length() - 1
                    || separatorIndex != currencyPair.lastIndexOf('/')) {
                throw new IllegalStateException("FX_SPOT 货币对格式错误: dataDate=" + dataDate
                        + ", currencyPair=" + currencyPair + ", curveId=" + curveId);
            }
            String left = currencyPair.substring(0, separatorIndex).trim().toUpperCase(Locale.ROOT);
            String right = currencyPair.substring(separatorIndex + 1).trim().toUpperCase(Locale.ROOT);
            if (left.isEmpty() || right.isEmpty()) {
                throw new IllegalStateException("FX_SPOT 货币对格式错误: dataDate=" + dataDate
                        + ", currencyPair=" + currencyPair + ", curveId=" + curveId);
            }
            return new String[]{left, right};
        }

        private void validate() {
            List<String> duplicatePairs = new ArrayList<String>();
            for (Map.Entry<String, List<String>> entry : pairSources.entrySet()) {
                if (entry.getValue().size() > 1) {
                    duplicatePairs.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            if (!duplicatePairs.isEmpty()) {
                throw new IllegalStateException("FX_SPOT 市场数据冲突: dataDate=" + dataDate
                        + ", duplicateCurrencyPairs=" + duplicatePairs
                        + ", containers=" + containers);
            }
        }
    }
}
