package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.FxSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * FX FRTB shock 支持类。
 */
public final class FxShockSupport {

    private FxShockSupport() {
    }

    /**
     * 构造 FX Delta 情景。
     */
    public static FrtbMarketData buildDeltaShockMarket(MarketData marketData, String currency) {
        String riskCurrency = normalizeCurrency(currency, "FX Delta");
        MarketData newMarketDate = CommUtils.deepCopy(marketData);
        FrtbMarketData frtbMarketData = new FrtbMarketData(newMarketDate);
        if (marketData.fxSpot != null) {
            HashMap<String, Double> newCurveData = CommUtils.deepCopy(newMarketDate.fxSpot.curveData);
            FxSpot.FxSpotInfo fxSpotNew = CommUtils.deepCopy(newMarketDate.fxSpot);
            for (String key : fxSpotNew.curveData.keySet()) {
                String[] pair = parseCurrencyPair(key);
                if (pair == null) {
                    continue;
                }
                if (riskCurrency.equals(pair[0])) {
                    newCurveData.put(key, newMarketDate.fxSpot.curveData.get(key) * 1.01);
                    continue;
                }
                if (riskCurrency.equals(pair[1])) {
                    newCurveData.put(key, newMarketDate.fxSpot.curveData.get(key) / 1.01);
                }
            }
            fxSpotNew.curveData = newCurveData;
            frtbMarketData.marketData.fxSpot = fxSpotNew;
            frtbMarketData.riskFactorId = riskCurrency + "/CNY";
            frtbMarketData.riskFactorVertex1 = "";
            frtbMarketData.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.FXR;
            frtbMarketData.riskFactorBucket = riskCurrency;
            frtbMarketData.riskFactorType = "";
            frtbMarketData.sensitivityType = "Delta";
            frtbMarketData.instrumentCurrency = riskCurrency;
        }
        return frtbMarketData;
    }

    /**
     * 构造 FX Curvature 情景列表。
     */
    public static List<FrtbMarketData> buildCurvatureShockMarkets(MarketData marketData, String currency) {
        String riskCurrency = normalizeCurrency(currency, "FX Curvature");
        double rw = FrtbParamsCache.getFxRiskWeight(riskCurrency);
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        MarketData marketDateUp = CommUtils.deepCopy(marketData);
        FrtbMarketData frtbMarketDataUp = new FrtbMarketData(marketDateUp);
        MarketData marketDateDown = CommUtils.deepCopy(marketData);
        FrtbMarketData frtbMarketDataDown = new FrtbMarketData(marketDateDown);
        if (marketData.fxSpot != null) {
            HashMap<String, Double> curveDataUp = CommUtils.deepCopy(marketDateUp.fxSpot.curveData);
            HashMap<String, Double> curveDataDown = CommUtils.deepCopy(marketDateDown.fxSpot.curveData);
            FxSpot.FxSpotInfo fxSpotUp = CommUtils.deepCopy(marketDateUp.fxSpot);
            FxSpot.FxSpotInfo fxSpotDown = CommUtils.deepCopy(marketDateDown.fxSpot);
            for (String key : marketData.fxSpot.curveData.keySet()) {
                String[] pair = parseCurrencyPair(key);
                if (pair == null) {
                    continue;
                }
                if (riskCurrency.equals(pair[0])) {
                    curveDataUp.put(key, marketDateUp.fxSpot.curveData.get(key) * (1 + rw));
                    curveDataDown.put(key, marketDateDown.fxSpot.curveData.get(key) * (1 - rw));
                    continue;
                }
                if (riskCurrency.equals(pair[1])) {
                    curveDataUp.put(key, marketDateUp.fxSpot.curveData.get(key) / (1 + rw));
                    curveDataDown.put(key, marketDateDown.fxSpot.curveData.get(key) / (1 - rw));
                }
            }
            fxSpotUp.curveData = curveDataUp;
            fxSpotDown.curveData = curveDataDown;
            frtbMarketDataUp.marketData.fxSpot = fxSpotUp;
            frtbMarketDataDown.marketData.fxSpot = fxSpotDown;

            frtbMarketDataUp.riskFactorId = riskCurrency + "/CNY";
            frtbMarketDataUp.riskFactorVertex1 = "";
            frtbMarketDataUp.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.FXR;
            frtbMarketDataUp.riskFactorBucket = riskCurrency;
            frtbMarketDataUp.riskFactorType = "";
            frtbMarketDataUp.sensitivityType = "Curvature Up";
            frtbMarketDataUp.riskWeight = -rw;
            frtbMarketDataUp.instrumentCurrency = riskCurrency;
            frtbMarketDataList.add(frtbMarketDataUp);

            frtbMarketDataDown.riskFactorId = riskCurrency + "/CNY";
            frtbMarketDataDown.riskFactorVertex1 = "";
            frtbMarketDataDown.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.FXR;
            frtbMarketDataDown.riskFactorBucket = riskCurrency;
            frtbMarketDataDown.sensitivityType = "Curvature Down";
            frtbMarketDataDown.riskFactorType = "";
            frtbMarketDataDown.riskWeight = rw;
            frtbMarketDataDown.instrumentCurrency = riskCurrency;
            frtbMarketDataList.add(frtbMarketDataDown);
        }
        return frtbMarketDataList;
    }

    private static String normalizeCurrency(String currency, String context) {
        if (currency == null || currency.trim().isEmpty()) {
            throw new IllegalArgumentException(context + " 风险币种不能为空");
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private static String[] parseCurrencyPair(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.trim().toUpperCase(Locale.ROOT).split("/");
        if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return null;
        }
        return new String[] {parts[0].trim(), parts[1].trim()};
    }
}
