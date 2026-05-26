package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Series;

import java.time.LocalDate;
import java.util.HashMap;

/**
 * FRTB 情景构造公共支持类。
 * 负责曲线平移和单期限点 shock 线性传播等通用能力。
 */
public final class FrtbShockSupport {

    private FrtbShockSupport() {
    }

    /**
     * 构造单期限点上移 1bp 的标准期限 overlay 集合。
     * 返回值只包含标准期限点上的增量，不依赖原始曲线期限结构。
     */
    public static HashMap<String, Series<Integer, Double>> getDeltaCurveDataDayMap(
            Series<Integer, Double> curveData,
            LocalDate dataDate,
            String[] tenorCodes,
            String[] tenorVertices,
            String interpolateType) {
        if (curveData == null || curveData.isEmpty()
                || dataDate == null || tenorCodes == null || tenorVertices == null
                || tenorCodes.length == 0 || tenorCodes.length != tenorVertices.length) {
            return new HashMap<>();
        }
        int[] tenorDays = CommUtils.tranfToDays(dataDate, tenorCodes);
        HashMap<String, Series<Integer, Double>> curveDataMap = new HashMap<>();

        for (int i = 0; i < tenorDays.length; i++) {
            curveDataMap.put(tenorVertices[i], buildSingleTenorShockOverlay(tenorDays, i));
        }

        return curveDataMap;
    }

    private static Series<Integer, Double> buildSingleTenorShockOverlay(int[] tenorDays, int targetIndex) {
        Series<Integer, Double> shockOverlay = new Series<>(Integer.class, Double.class);
        int targetTenorDay = tenorDays[targetIndex];
        for (int tenorDay : tenorDays) {
            shockOverlay.put(tenorDay, tenorDay == targetTenorDay ? 0.0001 : 0.0);
        }
        return shockOverlay;
    }

    /**
     * 对整条曲线做平移。
     */
    public static Series<Integer, Double> shiftCurveDataByPercent(Series<Integer, Double> curveData, double percent) {
        Series<Integer, Double> curveDataNew = curveData;
        for (int day : curveData.keySet()) {
            curveDataNew.put(day, curveData.get(day) + percent);
        }
        return curveDataNew;
    }

    /**
     * 计算中间期限点在线性传播下应叠加的 1bp shock。
     */
    public static double getLinearShock(int currentDay, int smallDay, int largeDay, boolean greaterSide) {
        if (greaterSide) {
            return 0.0001 / (largeDay - smallDay) * (currentDay - smallDay);
        }
        return 0.0001 / (largeDay - smallDay) * (largeDay - currentDay);
    }
}
