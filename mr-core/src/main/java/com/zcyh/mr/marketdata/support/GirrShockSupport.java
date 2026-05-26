package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * GIRR FRTB shock 支持类。
 * 负责 GIRR 标准期限常量和 Delta/Curvature 曲线情景构造。
 */
public final class GirrShockSupport {

    private GirrShockSupport() {
    }

    /**
     * 构造 GIRR Delta 单期限点 shock 曲线集合。
     */
    public static HashMap<String, Series<Integer, Double>> buildDeltaCurveDataDayMap(
            Series<Integer, Double> curveData,
            LocalDate dataDate,
            String interpolateType) {
        return FrtbShockSupport.getDeltaCurveDataDayMap(
                curveData,
                dataDate,
                FrtbParamsCache.getGirrTenorCodes(),
                FrtbParamsCache.getGirrTenorVertices(),
                interpolateType);
    }

    /**
     * 对 GIRR 曲线做整条平移。
     */
    public static Series<Integer, Double> shiftCurveDataByPercent(Series<Integer, Double> curveData, double percent) {
        return FrtbShockSupport.shiftCurveDataByPercent(curveData, percent);
    }

    /**
     * 构造 GIRR Delta 情景列表。
     */
    public static List<FrtbMarketData> buildDeltaShockMarkets(
            MarketData marketData,
            LocalDate dataDate,
            HashMap<String, String> bucketByCurve) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.irSpot.isEmpty() || bucketByCurve == null) {
            return frtbMarketDataList;
        }
        for (String key : bucketByCurve.keySet()) {
            if (key == null || !marketData.irSpot.containsKey(key)) {
                continue;
            }
            HashMap<String, Series<Integer, Double>> shockOverlayMap = buildDeltaCurveDataDayMap(
                    marketData.irSpot.get(key).curveData, dataDate, marketData.irSpot.get(key).interpolateType);
            for (String term : shockOverlayMap.keySet()) {
                MarketData newMarketDate = CommUtils.deepCopy(marketData);
                IrSpot.IrSpotInfo irSpotInfo = CommUtils.deepCopy(newMarketDate.irSpot.get(key));
                irSpotInfo.shockCurveData = shockOverlayMap.get(term);
                FrtbMarketData frtbMarketData = new FrtbMarketData(newMarketDate);
                frtbMarketData.marketData.irSpot.put(key, irSpotInfo);
                frtbMarketData.riskFactorId = key;
                frtbMarketData.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.GIRR;
                frtbMarketData.sensitivityType = "Delta";
                frtbMarketData.riskFactorType = "Interest Rate";
                frtbMarketData.riskFactorVertex1 = term;
                frtbMarketData.riskFactorBucket = bucketByCurve.get(key);
                frtbMarketDataList.add(frtbMarketData);
            }
        }
        return frtbMarketDataList;
    }

    /**
     * 构造 GIRR Curvature 情景列表。
     */
    public static List<FrtbMarketData> buildCurvatureShockMarkets(
            MarketData marketData,
            HashMap<String, List<String>> curveMapByBucket) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.irSpot.isEmpty() || curveMapByBucket == null) {
            return frtbMarketDataList;
        }
        for (String bucket : curveMapByBucket.keySet()) {
            double percent = FrtbParamsCache.getGirrCurvatureRw(bucket);
            MarketData newMarketDateUp = CommUtils.deepCopy(marketData);
            FrtbMarketData frtbMarketDataUp = new FrtbMarketData(newMarketDateUp);
            MarketData newMarketDateDown = CommUtils.deepCopy(marketData);
            FrtbMarketData frtbMarketDataDown = new FrtbMarketData(newMarketDateDown);
            for (String curveName : curveMapByBucket.get(bucket)) {
                Series<Integer, Double> curveDataUp = shiftCurveDataByPercent(
                        newMarketDateUp.irSpot.get(curveName).curveData, percent);
                IrSpot.IrSpotInfo irSpotInfoUp = CommUtils.deepCopy(newMarketDateUp.irSpot.get(curveName));
                irSpotInfoUp.curveData = curveDataUp;
                frtbMarketDataUp.marketData.irSpot.put(curveName, irSpotInfoUp);

                Series<Integer, Double> curveDataDown = shiftCurveDataByPercent(
                        newMarketDateDown.irSpot.get(curveName).curveData, -percent);
                IrSpot.IrSpotInfo irSpotInfoDown = CommUtils.deepCopy(newMarketDateDown.irSpot.get(curveName));
                irSpotInfoDown.curveData = curveDataDown;
                frtbMarketDataDown.marketData.irSpot.put(curveName, irSpotInfoDown);
            }
            frtbMarketDataUp.riskFactorId = bucket;
            frtbMarketDataUp.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.GIRR;
            frtbMarketDataUp.riskFactorType = "Interest Rate";
            frtbMarketDataUp.sensitivityType = "Curvature Up";
            frtbMarketDataUp.riskFactorBucket = bucket;
            frtbMarketDataUp.riskWeight = -percent;
            frtbMarketDataList.add(frtbMarketDataUp);

            frtbMarketDataDown.riskFactorId = bucket;
            frtbMarketDataDown.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.GIRR;
            frtbMarketDataDown.riskFactorType = "Interest Rate";
            frtbMarketDataDown.sensitivityType = "Curvature Down";
            frtbMarketDataDown.riskFactorBucket = bucket;
            frtbMarketDataDown.riskWeight = percent;
            frtbMarketDataList.add(frtbMarketDataDown);
        }
        return frtbMarketDataList;
    }
}
