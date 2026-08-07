package com.zcyh.mr.loader;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.input.MarketDataInputs;

import java.math.BigDecimal;
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
            String curveType) {
        FxSpot.FxSpotInfo fxSpotInfo = MarketDataInputMapper.parseCurveMeta(
                marketJson, FxSpot.FxSpotInfo.class);
        MarketDataInputs.FxSpotInput input = marketJson.to(MarketDataInputs.FxSpotInput.class);
        if (firstFxContainer) {
            target.fxSpot = fxSpotInfo;
            firstFxContainer = false;
        }

        for (MarketDataInputs.FxSpotPointInput point : input.curveData) {
            String currency = point.currency;
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
            double rateValue = point.rate.doubleValue();
            if (Double.isNaN(rateValue) || Double.isInfinite(rateValue) || rateValue <= 0) {
                validationCollector.error(curveType, normalizedCurrency,
                        "RATE 必须大于 0: " + point.rate + ", 点位被剔除");
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
        if ("CREDIT_SPOT".equals(curveType)) {
            MarketDataInputs.CreditSpotInput input = marketJson.to(MarketDataInputs.CreditSpotInput.class);
            for (MarketDataInputs.CreditSpotPointInput point : input.curveData) {
                addRatePoint(irSpotInfo, point.term, point.rate, curveType, curveId);
            }
        } else {
            MarketDataInputs.IrSpotInput input = marketJson.to(MarketDataInputs.IrSpotInput.class);
            for (MarketDataInputs.IrSpotPointInput point : input.curveData) {
                addRatePoint(irSpotInfo, point.term, point.rate, curveType, curveId);
            }
        }
        irSpotInfo.pDataDate = dataDate;
        target.irSpot.put(curveId, irSpotInfo);
    }

    void processCommSpot(
            MarketData target,
            JSONObject marketJson,
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
        MarketDataInputs.CommSpotInput input = marketJson.to(MarketDataInputs.CommSpotInput.class);
        for (MarketDataInputs.CommSpotPointInput point : input.curveData) {
            commSpotInfo.curveData.put(point.term, point.price.doubleValue());
        }
        commSpotInfo.pDataDate = dataDate;
        target.commSpot.put(curveId, commSpotInfo);
    }

    void processEqSpot(
            MarketData target,
            JSONObject marketJson,
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
        MarketDataInputs.EqSpotInput input = marketJson.to(MarketDataInputs.EqSpotInput.class);
        for (MarketDataInputs.EqSpotPointInput point : input.curveData) {
            eqSpotInfo.curveData.put(point.term, point.price.doubleValue());
        }
        eqSpotInfo.pDataDate = dataDate;
        target.eqSpot.put(curveId, eqSpotInfo);
    }

    private void addRatePoint(
            IrSpot.IrSpotInfo info,
            Integer term,
            BigDecimal rate,
            String curveType,
            String curveId) {
        double rateValue = rate.doubleValue();
        if (rateValue > 1) {
            validationCollector.warning(curveType, curveId,
                    "RATE 值大于 1: " + rateValue + " (TERM=" + term + "), 请确认是否正确");
        }
        info.curveData.put(term, rateValue);
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
