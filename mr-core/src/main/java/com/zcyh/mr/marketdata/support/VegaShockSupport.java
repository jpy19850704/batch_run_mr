package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vega FRTB shock 支持类。
 */
public final class VegaShockSupport {

    private VegaShockSupport() {
    }

    /**
     * 构造 Vega tenor 情景列表。
     */
    public static List<FrtbMarketData> buildTenorShockMarkets(
            MarketData marketData,
            LocalDate dataDate,
            String riskFactorClass,
            String volatilitySurface) {
        List<FrtbMarketData> frtbMarketDataList = new ArrayList<>();
        if (marketData == null || dataDate == null || !hasText(riskFactorClass) || !hasText(volatilitySurface)) {
            return frtbMarketDataList;
        }
        List<Map<String, Object>> baseCurveData = getVegaCurveData(marketData, riskFactorClass, volatilitySurface);
        if (baseCurveData == null || baseCurveData.isEmpty()) {
            return frtbMarketDataList;
        }
        List<Map<String, Object>> baseCurveByVertex = renameVegaCurveToVertex(baseCurveData, riskFactorClass);
        if (baseCurveByVertex.isEmpty()) {
            return frtbMarketDataList;
        }
        String[] tenorCodes = FrtbParamsCache.getVegaTenorCodes();
        String[] tenorVertices = FrtbParamsCache.getVegaTenorVertices();
        double shockRatio = FrtbParamsCache.getVegaShockRatio();
        int[] tenorDays = CommUtils.tranfToDays(dataDate, tenorCodes);
        for (int i = 0; i < tenorDays.length; i++) {
            MarketData marketNew = CommUtils.deepCopy(marketData);
            List<Map<String, Object>> shockCurveData = renameVegaCurveFromVertex(
                    VolUtil.buildSingleTenorShockCurve(baseCurveByVertex, tenorDays, tenorDays[i], shockRatio),
                    riskFactorClass);
            if (!applyVegaShockCurve(marketNew, riskFactorClass, volatilitySurface, shockCurveData)) {
                continue;
            }
            FrtbMarketData frtbMarketData = new FrtbMarketData(marketNew);
            frtbMarketData.riskFactorId = volatilitySurface;
            frtbMarketData.riskFactorClass = riskFactorClass;
            frtbMarketData.riskFactorType = resolveVegaRiskFactorType(riskFactorClass);
            frtbMarketData.sensitivityType = "Vega";
            frtbMarketData.riskFactorVertex1 = tenorVertices[i];
            HashMap<String, Object> map = new HashMap<>();
            map.put("PERCENT", shockRatio);
            frtbMarketData.param = map;
            frtbMarketDataList.add(frtbMarketData);
        }
        return frtbMarketDataList;
    }

    private static List<Map<String, Object>> getVegaCurveData(
            MarketData marketData,
            String riskFactorClass,
            String volatilitySurface) {
        if (EngineConstants.FRTB.SA.RISK_CLASS.GIRR.equalsIgnoreCase(riskFactorClass)) {
            IrVol.IrVolInfo info = marketData.irVol.get(volatilitySurface);
            return info == null ? null : info.curveData;
        }
        if (EngineConstants.FRTB.SA.RISK_CLASS.FXR.equalsIgnoreCase(riskFactorClass)) {
            FxVol.FxVolInfo info = marketData.fxVol.get(volatilitySurface);
            return info == null ? null : info.curveData;
        }
        if (EngineConstants.FRTB.SA.RISK_CLASS.ER.equalsIgnoreCase(riskFactorClass)) {
            EqVol.EqVolInfo info = marketData.eqVol.get(volatilitySurface);
            return info == null ? null : info.curveData;
        }
        if (EngineConstants.FRTB.SA.RISK_CLASS.CR.equalsIgnoreCase(riskFactorClass)) {
            CommVol.CommVolInfo info = marketData.commVol.get(volatilitySurface);
            return info == null ? null : info.curveData;
        }
        return null;
    }

    private static boolean applyVegaShockCurve(
            MarketData marketData,
            String riskFactorClass,
            String volatilitySurface,
            List<Map<String, Object>> shockCurveData) {
        if (EngineConstants.FRTB.SA.RISK_CLASS.GIRR.equalsIgnoreCase(riskFactorClass)) {
            IrVol.IrVolInfo info = marketData.irVol.get(volatilitySurface);
            if (info == null) {
                return false;
            }
            info.shockCurveData = shockCurveData;
            return true;
        }
        if (EngineConstants.FRTB.SA.RISK_CLASS.FXR.equalsIgnoreCase(riskFactorClass)) {
            FxVol.FxVolInfo info = marketData.fxVol.get(volatilitySurface);
            if (info == null) {
                return false;
            }
            info.shockCurveData = shockCurveData;
            return true;
        }
        if (EngineConstants.FRTB.SA.RISK_CLASS.ER.equalsIgnoreCase(riskFactorClass)) {
            EqVol.EqVolInfo info = marketData.eqVol.get(volatilitySurface);
            if (info == null) {
                return false;
            }
            info.shockCurveData = shockCurveData;
            return true;
        }
        if (EngineConstants.FRTB.SA.RISK_CLASS.CR.equalsIgnoreCase(riskFactorClass)) {
            CommVol.CommVolInfo info = marketData.commVol.get(volatilitySurface);
            if (info == null) {
                return false;
            }
            info.shockCurveData = shockCurveData;
            return true;
        }
        return false;
    }

    private static List<Map<String, Object>> renameVegaCurveToVertex(
            List<Map<String, Object>> curveData,
            String riskFactorClass) {
        Map<String, String> rules = new HashMap<>();
        rules.put("OPTION_TERM", "VERTEX1");
        if (EngineConstants.FRTB.SA.RISK_CLASS.GIRR.equalsIgnoreCase(riskFactorClass)) {
            rules.put("UNDERLYING_TERM", "VERTEX2");
        } else {
            rules.put("DELTA", "VERTEX2");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            result.add(CommUtils.mapKeyRename(new LinkedHashMap<>(curveDatum), rules));
        }
        return result;
    }

    private static List<Map<String, Object>> renameVegaCurveFromVertex(
            List<Map<String, Object>> curveData,
            String riskFactorClass) {
        Map<String, String> rules = new HashMap<>();
        rules.put("VERTEX1", "OPTION_TERM");
        if (EngineConstants.FRTB.SA.RISK_CLASS.GIRR.equalsIgnoreCase(riskFactorClass)) {
            rules.put("VERTEX2", "UNDERLYING_TERM");
        } else {
            rules.put("VERTEX2", "DELTA");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            result.add(CommUtils.mapKeyRename(new LinkedHashMap<>(curveDatum), rules));
        }
        return result;
    }

    private static String resolveVegaRiskFactorType(String riskFactorClass) {
        if (EngineConstants.FRTB.SA.RISK_CLASS.ER.equalsIgnoreCase(riskFactorClass)) {
            return "Spot";
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
