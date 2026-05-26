package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.Interpolation;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.FrtbMarketData;
import com.zcyh.mr.marketdata.MarketData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 商品 FRTB shock 支持类。
 * 负责商品标准期限常量和商品曲线标准期限补齐。
 */
public final class CmtyShockSupport {

    private CmtyShockSupport() {
    }

    /**
     * 为商品曲线补齐标准期限点，保证 tenor shock 可以稳定叠加到基础线性曲线上。
     */
    public static Series<Integer, Double> ensureCurveHasStandardTenors(
            Series<Integer, Double> curveData,
            LocalDate anchorDate,
            String interpolateType) {
        Series<Integer, Double> curveDataNew = CommUtils.deepCopy(curveData);
        int[] tenorDays = CommUtils.tranfToDays(anchorDate, FrtbParamsCache.getCmtyTenorCodes());
        for (int tenorDay : tenorDays) {
            if (!curveDataNew.containsKey(tenorDay)) {
                curveDataNew.put(tenorDay, Interpolation.interpolate(curveData, tenorDay, interpolateType));
            }
        }
        return curveDataNew;
    }

    /**
     * 构造商品 Delta 情景列表。
     */
    public static List<FrtbMarketData> buildDeltaShockMarkets(MarketData marketData, String priceCurve) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || marketData.commSpot == null || marketData.commSpot.isEmpty()) {
            return frtbMarketDataList;
        }
        List<String> targetCurves = new ArrayList<>();
        if (priceCurve != null && !priceCurve.trim().isEmpty()) {
            if (!marketData.commSpot.containsKey(priceCurve)) {
                return frtbMarketDataList;
            }
            targetCurves.add(priceCurve);
        } else {
            targetCurves.addAll(marketData.commSpot.keySet());
        }
        for (String key : targetCurves) {
            CommSpot.CommSpotInfo baseInfo = marketData.commSpot.get(key);
            if (baseInfo == null || baseInfo.curveData == null || baseInfo.curveData.isEmpty()) {
                continue;
            }
            LocalDate anchorDate = baseInfo.pDataDate != null ? baseInfo.pDataDate : baseInfo.dataDate;
            if (anchorDate == null) {
                continue;
            }
            String[] tenorCodes = FrtbParamsCache.getCmtyTenorCodes();
            String[] tenorVertices = FrtbParamsCache.getCmtyTenorVertices();
            int[] tenorDays = CommUtils.tranfToDays(anchorDate, tenorCodes);
            Series<Integer, Double> baseCurve = ensureCurveHasStandardTenors(
                    baseInfo.curveData, anchorDate, baseInfo.interpolateType);
            for (int i = 0; i < tenorDays.length; i++) {
                Series<Integer, Double> shockCurveData = buildSingleTenorShockRatioCurve(tenorDays, tenorDays[i]);
                MarketData newMarketDate = CommUtils.deepCopy(marketData);
                CommSpot.CommSpotInfo commSpotInfo = CommUtils.deepCopy(newMarketDate.commSpot.get(key));
                commSpotInfo.curveData = baseCurve;
                commSpotInfo.shockCurveData = shockCurveData;
                FrtbMarketData frtbMarketData = new FrtbMarketData(newMarketDate);
                frtbMarketData.marketData.commSpot.put(key, commSpotInfo);
                frtbMarketData.riskFactorId = key;
                frtbMarketData.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.CR;
                frtbMarketData.riskFactorType = "Delta";
                frtbMarketData.riskFactorVertex1 = tenorVertices[i];
                frtbMarketDataList.add(frtbMarketData);
            }
        }
        return frtbMarketDataList;
    }

    /**
     * 构造商品 Curvature 情景列表。
     */
    public static List<FrtbMarketData> buildCurvatureShockMarkets(MarketData marketData, String bucket) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || marketData.commSpot == null) {
            return frtbMarketDataList;
        }
        HashMap<String, Double> cmtyWeights = FrtbParamsCache.getCmtyWeights();
        Double configuredRw = cmtyWeights.get(bucket);
        if (configuredRw == null) {
            throw new IllegalArgumentException("未配置 CMTY Curvature 风险权重: " + bucket);
        }
        double rw = configuredRw;
        for (String key : marketData.commSpot.keySet()) {
            MarketData marketDataUp = CommUtils.deepCopy(marketData);
            MarketData marketDataDown = CommUtils.deepCopy(marketData);
            CommSpot.CommSpotInfo commSpotInfo = CommUtils.deepCopy(marketDataUp.commSpot.get(key));
            Series<Integer, Double> curveUp = new Series<>(Integer.class, Double.class);
            Series<Integer, Double> curveDown = new Series<>(Integer.class, Double.class);
            for (Integer i : commSpotInfo.curveData.keySet()) {
                curveUp.put(i, commSpotInfo.curveData.get(i) * (1 + rw));
                curveDown.put(i, commSpotInfo.curveData.get(i) * (1 - rw));
            }
            FrtbMarketData up = new FrtbMarketData(marketDataUp);
            FrtbMarketData down = new FrtbMarketData(marketDataDown);
            CommSpot.CommSpotInfo commSpotInfoUp = CommUtils.deepCopy(marketDataUp.commSpot.get(key));
            commSpotInfoUp.curveData = curveUp;
            up.marketData.commSpot.put(key, commSpotInfoUp);
            up.riskWeight = -rw;
            up.sensitivityType = "Curvature Up";
            up.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.CR;
            frtbMarketDataList.add(up);

            CommSpot.CommSpotInfo commSpotInfoDown = CommUtils.deepCopy(marketDataUp.commSpot.get(key));
            commSpotInfoDown.curveData = curveDown;
            down.marketData.commSpot.put(key, commSpotInfoDown);
            down.riskWeight = rw;
            down.sensitivityType = "Curvature Down";
            down.riskFactorClass = Constants.FRTB.SA.RISK_CLASS.CR;
            frtbMarketDataList.add(down);
        }
        return frtbMarketDataList;
    }

    /**
     * 构造商品 Delta 单期限点 ratio shock 曲线。
     * 仅在被 shock tenor 的相邻两个区间内线性衰减；
     * 若被 shock 的是全局最小或最大 tenor，则在区间外侧保持配置的 shock ratio。
     */
    public static Series<Integer, Double> buildSingleTenorShockRatioCurve(
            int[] tenorDays,
            int targetDay) {
        Series<Integer, Double> shockCurveData = new Series<>(Integer.class, Double.class);
        if (tenorDays == null || tenorDays.length == 0) {
            return shockCurveData;
        }
        double shockRatio = FrtbParamsCache.getCmtyDeltaShockRatio();
        for (int tenorDay : tenorDays) {
            shockCurveData.put(tenorDay, tenorDay == targetDay ? shockRatio : 0.0);
        }
        return shockCurveData;
    }

    /**
     * 按商品 Delta 规则解析指定请求期限上的 ratio shock。
     */
    public static double resolveShockRatio(Series<Integer, Double> shockCurveData, int days) {
        if (shockCurveData == null || shockCurveData.isEmpty()) {
            return 0.0;
        }
        int[] tenorDays = shockCurveData.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        if (tenorDays.length == 0) {
            return 0.0;
        }
        int targetIndex = -1;
        for (int i = 0; i < tenorDays.length; i++) {
            Double ratio = shockCurveData.get(tenorDays[i]);
            if (ratio != null && ratio > 0) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            return 0.0;
        }
        int targetDay = tenorDays[targetIndex];
        double shockRatio = FrtbParamsCache.getCmtyDeltaShockRatio();
        if (days == targetDay) {
            return shockRatio;
        }
        if (targetIndex == 0) {
            int rightDay = tenorDays.length > 1 ? tenorDays[1] : targetDay;
            if (days < targetDay) {
                return shockRatio;
            }
            if (days > rightDay) {
                return 0.0;
            }
            return linearRatio(targetDay, shockRatio, rightDay, 0.0, days);
        }
        if (targetIndex == tenorDays.length - 1) {
            int leftDay = tenorDays[targetIndex - 1];
            if (days > targetDay) {
                return shockRatio;
            }
            if (days < leftDay) {
                return 0.0;
            }
            return linearRatio(leftDay, 0.0, targetDay, shockRatio, days);
        }
        int leftDay = tenorDays[targetIndex - 1];
        int rightDay = tenorDays[targetIndex + 1];
        if (days < leftDay || days > rightDay) {
            return 0.0;
        }
        if (days < targetDay) {
            return linearRatio(leftDay, 0.0, targetDay, shockRatio, days);
        }
        return linearRatio(targetDay, shockRatio, rightDay, 0.0, days);
    }

    /**
     * 线性插值 ratio。
     */
    private static double linearRatio(int leftDay, double leftValue, int rightDay, double rightValue, int days) {
        if (rightDay <= leftDay) {
            return leftValue;
        }
        if (days <= leftDay) {
            return leftValue;
        }
        if (days >= rightDay) {
            return rightValue;
        }
        double weight = (double) (days - leftDay) / (double) (rightDay - leftDay);
        return leftValue + (rightValue - leftValue) * weight;
    }
}
