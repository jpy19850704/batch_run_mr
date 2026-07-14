package com.zcyh.mr.calc.scenario;

import com.zcyh.mr.marketdata.IrSpot;
import com.zcyh.mr.marketdata.MarketData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 场景市场数据切片器。
 */
final class ScenarioMarketDataSlicer {

    MarketData slice(MarketData source, String group) {
        MarketData result = new MarketData();
        if (source == null || "ALL".equals(group)) {
            return source == null ? result : source;
        }
        switch (group) {
            case "IR":
                result.irSpot = filterIrSpotByCurveType(source.irSpot, "IR_SPOT");
                result.irVol = source.irVol;
                break;
            case "CS":
                result.irSpot = filterIrSpotByCurveType(source.irSpot, "CREDIT_SPOT");
                break;
            case "FX":
                result.fxSpot = source.fxSpot;
                result.fxVol = source.fxVol;
                break;
            case "EQ":
                result.eqSpot = source.eqSpot;
                result.eqVol = source.eqVol;
                break;
            case "COMM":
                result.commSpot = source.commSpot;
                result.commVol = source.commVol;
                break;
            default:
                break;
        }
        return result;
    }

    Set<String> deriveImpactKeys(MarketData marketData, String group) {
        Set<String> keys = new LinkedHashSet<>();
        if (marketData == null) {
            return keys;
        }
        if ("ALL".equals(group)) {
            addIrSpotKeysByCurveType(keys, marketData.irSpot);
            addMapKeys(keys, "IR_VOL", marketData.irVol);
            addMapKeys(keys, "EQ_SPOT", marketData.eqSpot);
            addMapKeys(keys, "EQ_VOL", marketData.eqVol);
            addMapKeys(keys, "COMM_SPOT", marketData.commSpot);
            addMapKeys(keys, "COMM_VOL", marketData.commVol);
            addMapKeys(keys, "FX_VOL", marketData.fxVol);
            addFxSpotKeys(keys, marketData);
            return keys;
        }
        switch (group) {
            case "IR":
                addMapKeys(keys, "IR_SPOT", marketData.irSpot);
                addMapKeys(keys, "IR_VOL", marketData.irVol);
                break;
            case "CS":
                addMapKeys(keys, "CREDIT_SPOT", marketData.irSpot);
                break;
            case "FX":
                addMapKeys(keys, "FX_VOL", marketData.fxVol);
                addFxSpotKeys(keys, marketData);
                break;
            case "EQ":
                addMapKeys(keys, "EQ_SPOT", marketData.eqSpot);
                addMapKeys(keys, "EQ_VOL", marketData.eqVol);
                break;
            case "COMM":
                addMapKeys(keys, "COMM_SPOT", marketData.commSpot);
                addMapKeys(keys, "COMM_VOL", marketData.commVol);
                break;
            default:
                break;
        }
        return keys;
    }

    private static HashMap<String, IrSpot.IrSpotInfo> filterIrSpotByCurveType(
            HashMap<String, IrSpot.IrSpotInfo> source, String curveType) {
        HashMap<String, IrSpot.IrSpotInfo> result = new HashMap<>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, IrSpot.IrSpotInfo> entry : source.entrySet()) {
            IrSpot.IrSpotInfo info = entry.getValue();
            if (info != null && curveType.equals(info.curveType)) {
                result.put(entry.getKey(), info);
            }
        }
        return result;
    }

    private static void addIrSpotKeysByCurveType(Set<String> target,
            HashMap<String, IrSpot.IrSpotInfo> irSpot) {
        if (irSpot == null || irSpot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, IrSpot.IrSpotInfo> entry : irSpot.entrySet()) {
            String key = entry.getKey();
            IrSpot.IrSpotInfo info = entry.getValue();
            if (key == null || info == null || info.curveType == null) {
                continue;
            }
            if ("IR_SPOT".equals(info.curveType) || "CREDIT_SPOT".equals(info.curveType)) {
                target.add(info.curveType + ":" + key.trim().toUpperCase());
            }
        }
    }

    private static void addFxSpotKeys(Set<String> target, MarketData marketData) {
        if (marketData.fxSpot == null || marketData.fxSpot.curveData == null) {
            return;
        }
        for (String currencyPair : marketData.fxSpot.curveData.keySet()) {
            target.add("FX_SPOT:" + currencyPair.trim().toUpperCase());
        }
    }

    @SuppressWarnings("rawtypes")
    private static void addMapKeys(Set<String> target, String type, HashMap values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Object key : values.keySet()) {
            if (key != null) {
                target.add(type + ":" + key.toString().trim().toUpperCase());
            }
        }
    }
}
