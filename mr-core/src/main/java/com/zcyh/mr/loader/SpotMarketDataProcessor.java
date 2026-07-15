package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Spot 市场数据处理器。
 */
final class SpotMarketDataProcessor {
    private final LocalDate dataDate;
    private final String fxSpotBaseCurrency;
    private final MarketDataValidationCollector validationCollector;

    SpotMarketDataProcessor(
            LocalDate dataDate,
            String fxSpotBaseCurrency,
            MarketDataValidationCollector validationCollector) {
        this.dataDate = dataDate;
        this.fxSpotBaseCurrency = fxSpotBaseCurrency;
        this.validationCollector = validationCollector;
    }

    boolean processFxSpot(
            MarketData target,
            boolean firstFxContainer,
            JSONObject marketJson,
            JSONArray curveData,
            String curveType) {
        if (curveData == null || curveData.isEmpty()) {
            validationCollector.error(curveType, "", "CURVE_DATA 为空");
            return firstFxContainer;
        }

        FxSpot.FxSpotInfo fxSpotInfo = MarketDataInputMapper.parseCurveMeta(
                marketJson, FxSpot.FxSpotInfo.class);
        if (firstFxContainer) {
            target.fxSpot = fxSpotInfo;
            firstFxContainer = false;
        }

        for (Object pointObj : curveData) {
            JSONObject pointJson = (JSONObject) pointObj;
            String currency = pointJson.getString("CURRENCY");
            Object rate = pointJson.get("RATE");
            if (currency == null || currency.isEmpty()) {
                validationCollector.error(curveType, "", "CURRENCY 为空, 点位被剔除");
                continue;
            }
            String normalizedCurrency = normalizeCurrencyPair(currency);
            if (normalizedCurrency == null) {
                validationCollector.error(curveType, currency, "CURRENCY 货币对格式错误, 点位被剔除");
                continue;
            }
            String[] currencies = normalizedCurrency.split("/");
            if (currencies[0].equals(currencies[1])) {
                validationCollector.error(curveType, normalizedCurrency,
                        "CURRENCY 两侧币种相同, 点位被剔除");
                continue;
            }
            if (!fxSpotBaseCurrency.equals(currencies[0]) && !fxSpotBaseCurrency.equals(currencies[1])) {
                validationCollector.error(curveType, normalizedCurrency, "CURRENCY 必须包含基础币种 "
                        + fxSpotBaseCurrency + ", 点位被剔除");
                continue;
            }
            if (rate == null) {
                validationCollector.error(curveType, normalizedCurrency, "RATE 为空, 点位被剔除");
                continue;
            }
            if (!(rate instanceof Number)) {
                try {
                    Double.parseDouble(rate.toString());
                } catch (NumberFormatException ex) {
                    validationCollector.error(curveType, normalizedCurrency,
                            "RATE 不是数字: " + rate + ", 点位被剔除");
                    continue;
                }
            }
            double rateValue = pointJson.getDoubleValue("RATE");
            if (Double.isNaN(rateValue) || Double.isInfinite(rateValue) || rateValue <= 0) {
                validationCollector.error(curveType, normalizedCurrency,
                        "RATE 必须大于 0: " + rate + ", 点位被剔除");
                continue;
            }
            String reverseCurrency = currencies[1] + "/" + currencies[0];
            if (target.fxSpot.curveData.containsKey(normalizedCurrency)
                    || target.fxSpot.curveData.containsKey(reverseCurrency)) {
                validationCollector.error(curveType, normalizedCurrency,
                        "CURRENCY 正向或反向报价重复, 点位被剔除");
                continue;
            }
            target.fxSpot.curveData.put(normalizedCurrency, rateValue);
        }

        fxSpotInfo.pDataDate = dataDate;
        return firstFxContainer;
    }

    void processIrSpot(
            MarketData target,
            JSONObject marketJson,
            JSONArray curveData,
            String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            validationCollector.error(curveType, "", "CURVE_ID 为空");
            return;
        }
        IrSpot.IrSpotInfo irSpotInfo = MarketDataInputMapper.parseCurveMeta(
                marketJson, IrSpot.IrSpotInfo.class);
        try {
            irSpotInfo.interpolateType = MarketDataInputMapper.normalizeInterpolateType(
                    irSpotInfo.interpolateType, Interpolation.Type.LINEAR, "INTERPOLATE_TYPE");
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        for (Object pointObj : curveData) {
            JSONObject pointJson = (JSONObject) pointObj;
            Object term = pointJson.get("TERM");
            Object rate = pointJson.get("RATE");
            if (term == null || rate == null) {
                validationCollector.error(curveType, curveId, "TERM 或 RATE 为空, 点位被剔除");
                continue;
            }
            if (!(term instanceof Number)) {
                validationCollector.error(curveType, curveId,
                        "TERM 不是整数: " + term + ", 点位被剔除");
                continue;
            }
            if (!(rate instanceof Number)) {
                try {
                    Double.parseDouble(rate.toString());
                } catch (NumberFormatException ex) {
                    validationCollector.error(curveType, curveId,
                            "RATE 不是数字: " + rate + " (TERM=" + term + "), 点位被剔除");
                    continue;
                }
            }
            double rateValue = pointJson.getDoubleValue("RATE");
            if (rateValue > 1) {
                validationCollector.warning(curveType, curveId,
                        "RATE 值大于 1: " + rateValue + " (TERM=" + term + "), 请确认是否正确");
            }
            irSpotInfo.curveData.put(pointJson.getInteger("TERM"), rateValue);
        }
        irSpotInfo.pDataDate = dataDate;
        target.irSpot.put(curveId, irSpotInfo);
    }

    void processCommSpot(
            MarketData target,
            JSONObject marketJson,
            JSONArray curveData,
            String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            validationCollector.error(curveType, "", "CURVE_ID 为空");
            return;
        }
        CommSpot.CommSpotInfo commSpotInfo = MarketDataInputMapper.parseCurveMeta(
                marketJson, CommSpot.CommSpotInfo.class);
        try {
            commSpotInfo.interpolateType = MarketDataInputMapper.normalizeInterpolateType(
                    commSpotInfo.interpolateType, Interpolation.Type.LINEAR, "INTERPOLATE_TYPE");
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        for (Object pointObj : curveData) {
            JSONObject pointJson = (JSONObject) pointObj;
            Object term = pointJson.get("TERM");
            Object price = pointJson.get("COMM_PRICE");
            if (term == null || price == null) {
                validationCollector.error(curveType, curveId,
                        "TERM 或 COMM_PRICE 为空, 点位被剔除");
                continue;
            }
            if (!(term instanceof Number)) {
                validationCollector.error(curveType, curveId,
                        "TERM 不是整数: " + term + ", 点位被剔除");
                continue;
            }
            if (!(price instanceof Number)) {
                try {
                    Double.parseDouble(price.toString());
                } catch (NumberFormatException ex) {
                    validationCollector.error(curveType, curveId,
                            "COMM_PRICE 不是数字: " + price + " (TERM=" + term + "), 点位被剔除");
                    continue;
                }
            }
            commSpotInfo.curveData.put(
                    pointJson.getInteger("TERM"), pointJson.getDoubleValue("COMM_PRICE"));
        }
        commSpotInfo.pDataDate = dataDate;
        target.commSpot.put(curveId, commSpotInfo);
    }

    void processEqSpot(
            MarketData target,
            JSONObject marketJson,
            JSONArray curveData,
            String curveType) {
        String curveId = marketJson.getString("CURVE_ID");
        if (curveId == null || curveId.isEmpty()) {
            validationCollector.error(curveType, "", "CURVE_ID 为空");
            return;
        }
        EqSpot.EqSpotInfo eqSpotInfo = MarketDataInputMapper.parseCurveMeta(
                marketJson, EqSpot.EqSpotInfo.class);
        try {
            eqSpotInfo.interpolateType = MarketDataInputMapper.normalizeInterpolateType(
                    eqSpotInfo.interpolateType, Interpolation.Type.LINEAR, "INTERPOLATE_TYPE");
        } catch (IllegalArgumentException ex) {
            validationCollector.error(curveType, curveId, ex.getMessage());
            return;
        }
        for (Object pointObj : curveData) {
            JSONObject pointJson = (JSONObject) pointObj;
            Object term = pointJson.get("TERM");
            Object price = pointJson.get("EQ_PRICE");
            if (term == null || price == null) {
                validationCollector.error(curveType, curveId,
                        "TERM 或 EQ_PRICE 为空, 点位被剔除");
                continue;
            }
            if (!(term instanceof Number)) {
                validationCollector.error(curveType, curveId,
                        "TERM 不是整数: " + term + ", 点位被剔除");
                continue;
            }
            if (!(price instanceof Number)) {
                try {
                    Double.parseDouble(price.toString());
                } catch (NumberFormatException ex) {
                    validationCollector.error(curveType, curveId,
                            "EQ_PRICE 不是数字: " + price + " (TERM=" + term + "), 点位被剔除");
                    continue;
                }
            }
            eqSpotInfo.curveData.put(
                    pointJson.getInteger("TERM"), pointJson.getDoubleValue("EQ_PRICE"));
        }
        eqSpotInfo.pDataDate = dataDate;
        target.eqSpot.put(curveId, eqSpotInfo);
    }

    private String normalizeCurrencyPair(String currencyPair) {
        if (currencyPair == null) {
            return null;
        }
        int separatorIndex = currencyPair.indexOf('/');
        if (separatorIndex <= 0
                || separatorIndex == currencyPair.length() - 1
                || separatorIndex != currencyPair.lastIndexOf('/')) {
            return null;
        }
        String left = currencyPair.substring(0, separatorIndex).trim().toUpperCase(Locale.ROOT);
        String right = currencyPair.substring(separatorIndex + 1).trim().toUpperCase(Locale.ROOT);
        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }
        return left + "/" + right;
    }
}
