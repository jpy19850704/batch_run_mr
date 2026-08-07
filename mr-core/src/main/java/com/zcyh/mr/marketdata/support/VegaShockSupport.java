package com.zcyh.mr.marketdata.support;

import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.marketdata.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
        List<VolSurfacePoint> baseCurveData = getVegaCurveData(marketData, riskFactorClass, volatilitySurface);
        if (baseCurveData == null || baseCurveData.isEmpty()) {
            return frtbMarketDataList;
        }
        String[] tenorCodes = FrtbParamsCache.getVegaTenorCodes();
        String[] tenorVertices = FrtbParamsCache.getVegaTenorVertices();
        double shockRatio = FrtbParamsCache.getVegaShockRatio();
        int[] tenorDays = CommUtils.tranfToDays(dataDate, tenorCodes);
        for (int i = 0; i < tenorDays.length; i++) {
            MarketData marketNew = CommUtils.deepCopy(marketData);
            List<VolSurfacePoint> shockCurveData = VolUtil.buildSingleTenorShockCurve(
                    baseCurveData, tenorDays, tenorDays[i], shockRatio);
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

    private static List<VolSurfacePoint> getVegaCurveData(
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
            List<VolSurfacePoint> shockCurveData) {
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
