package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * CSR FRTB shock 支持类。
 * 负责 CSR 标准期限常量和 Delta/Curvature 曲线情景构造。
 */
public final class CsrShockSupport {

    private CsrShockSupport() {
    }

    /**
     * 构造 CSR Delta 单期限点 shock 曲线集合。
     */
    public static HashMap<String, Series<Integer, Double>> buildDeltaCurveDataDayMap(
            Series<Integer, Double> curveData,
            LocalDate dataDate,
            String interpolateType) {
        return FrtbShockSupport.getDeltaCurveDataDayMap(
                curveData,
                dataDate,
                FrtbParamsCache.getCsrTenorCodes(),
                FrtbParamsCache.getCsrTenorVertices(),
                interpolateType);
    }

    /**
     * 对 CSR 曲线做整条平移。
     */
    public static Series<Integer, Double> shiftCurveDataByPercent(Series<Integer, Double> curveData, double percent) {
        return FrtbShockSupport.shiftCurveDataByPercent(curveData, percent);
    }

    /**
     * 构造 CSR Delta 情景列表。
     */
    public static List<FrtbMarketData> buildDeltaShockMarkets(
            MarketData marketData,
            LocalDate dataDate,
            HashMap<String, String> curveMap) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || marketData.irSpot == null || marketData.irSpot.isEmpty() || curveMap == null) {
            return frtbMarketDataList;
        }
        for (String key : curveMap.keySet()) {
            if (key == null || !marketData.irSpot.containsKey(key)) {
                continue;
            }
            HashMap<String, Series<Integer, Double>> shockOverlayMap = buildDeltaCurveDataDayMap(
                    marketData.irSpot.get(key).curveData, dataDate, marketData.irSpot.get(key).interpolateType);
            for (String term : shockOverlayMap.keySet()) {
                MarketData newMarketDate = CommUtils.deepCopy(marketData);
                IrSpot.IrSpotInfo irSpotInfo = CommUtils.deepCopy(marketData.irSpot.get(key));
                irSpotInfo.shockCurveData = shockOverlayMap.get(term);
                FrtbMarketData frtbMarketData = new FrtbMarketData(newMarketDate);
                frtbMarketData.marketData.irSpot.put(key, irSpotInfo);
                frtbMarketData.riskFactorId = key;
                frtbMarketData.riskFactorClass = "CSR";
                frtbMarketData.riskFactorType = "Interest Rate";
                frtbMarketData.sensitivityType = "Delta";
                frtbMarketData.riskFactorVertex1 = term;
                frtbMarketDataList.add(frtbMarketData);
            }
        }
        return frtbMarketDataList;
    }

    /**
     * 构造 CSR Curvature 情景列表。
     */
    public static List<FrtbMarketData> buildCurvatureShockMarkets(
            MarketData marketData,
            String csrType,
            Integer bucket,
            HashMap<String, List<String>> curveMapByBucket) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || curveMapByBucket == null) {
            return frtbMarketDataList;
        }
        double rw = getCsrCurvatureRw(csrType, bucket);
        for (String key : curveMapByBucket.keySet()) {
            MarketData newMarketDateUp = CommUtils.deepCopy(marketData);
            FrtbMarketData frtbMarketDataUp = new FrtbMarketData(newMarketDateUp);
            MarketData newMarketDateDown = CommUtils.deepCopy(marketData);
            FrtbMarketData frtbMarketDataDown = new FrtbMarketData(newMarketDateDown);
            for (String curveName : curveMapByBucket.get(key)) {
                Series<Integer, Double> curveDataUp = shiftCurveDataByPercent(
                        newMarketDateUp.irSpot.get(curveName).curveData, rw);
                IrSpot.IrSpotInfo irSpotInfoUp = CommUtils.deepCopy(newMarketDateUp.irSpot.get(curveName));
                irSpotInfoUp.curveData = curveDataUp;
                frtbMarketDataUp.marketData.irSpot.put(curveName, irSpotInfoUp);

                Series<Integer, Double> curveDataDown = shiftCurveDataByPercent(
                        newMarketDateDown.irSpot.get(curveName).curveData, -rw);
                IrSpot.IrSpotInfo irSpotInfoDown = CommUtils.deepCopy(newMarketDateDown.irSpot.get(curveName));
                irSpotInfoDown.curveData = curveDataDown;
                frtbMarketDataDown.marketData.irSpot.put(curveName, irSpotInfoDown);
            }
            frtbMarketDataUp.riskFactorId = key;
            frtbMarketDataUp.riskFactorClass = csrType;
            frtbMarketDataUp.riskFactorType = "Interest Rate";
            frtbMarketDataUp.sensitivityType = "Curvature Up";
            frtbMarketDataUp.riskWeight = -rw;
            frtbMarketDataList.add(frtbMarketDataUp);

            frtbMarketDataDown.riskFactorId = key;
            frtbMarketDataDown.riskFactorClass = csrType;
            frtbMarketDataDown.riskFactorType = "Interest Rate";
            frtbMarketDataDown.sensitivityType = "Curvature Down";
            frtbMarketDataDown.riskWeight = rw;
            frtbMarketDataList.add(frtbMarketDataDown);
        }
        return frtbMarketDataList;
    }

    private static double getCsrCurvatureRw(String csrType, Integer bucket) {
        if (bucket == null) {
            throw new IllegalArgumentException("CSR Curvature bucket 不能为空");
        }
        String bucketKey = bucket.toString();
        Double rw;
        if ("CSR (non-sec)".equalsIgnoreCase(csrType)) {
            rw = FrtbParamsCache.getCsrnsWeights().get(bucketKey);
        } else if ("CSR (non-ctp)".equalsIgnoreCase(csrType)) {
            rw = FrtbParamsCache.getCsrncWeights().get(bucketKey);
        } else {
            throw new IllegalArgumentException("不支持的 CSR Curvature 风险类别: " + csrType);
        }
        if (rw == null) {
            throw new IllegalArgumentException("未配置 " + csrType + " Curvature 风险权重: " + bucketKey);
        }
        return rw;
    }
}
