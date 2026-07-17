package com.zcyh.mr.marketdata.curvegeneration;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.JsonNumberUtils;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将曲线生成结果转换为标准 market_data JSON 数组。
 */
public final class CurveGenerationExport {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private CurveGenerationExport() {
    }

    /**
     * 将曲线生成结果导出为标准 market_data 结构。
     */
    public static JSONArray toJsonArray(CurveGeneration.CurveResult result) {
        JSONArray output = new JSONArray();
        if (result == null) {
            return output;
        }

        Map<String, List<CurveGeneration.IrCurve>> irGrouped = new LinkedHashMap<>();
        for (CurveGeneration.IrCurve point : result.irCurves) {
            if (point == null || point.curveId == null || point.curveId.trim().isEmpty()) {
                continue;
            }
            irGrouped.computeIfAbsent(point.curveId, ignored -> new ArrayList<>()).add(point);
        }

        for (Map.Entry<String, List<CurveGeneration.IrCurve>> entry : irGrouped.entrySet()) {
            List<CurveGeneration.IrCurve> points = entry.getValue();
            if (points.isEmpty()) {
                continue;
            }
            CurveGeneration.IrCurve first = points.get(0);
            JSONObject curve = new JSONObject();
            curve.put("CURVE_TYPE", EngineConstants.RF_TYPE.IR_SPOT);
            curve.put("CURVE_ID", first.curveId);
            if (first.dataDate != null) {
                curve.put("DATA_DATE", first.dataDate.format(BASIC_DATE));
            }
            curve.put("CURVE_DAYCOUNT", first.curveDaycount);
            curve.put("CURVE_FREQ", first.curveFreq);
            curve.put("INTERPOLATE_TYPE", first.interpolateType);

            JSONArray curveData = new JSONArray();
            for (CurveGeneration.IrCurve point : points) {
                JSONObject row = new JSONObject();
                row.put("TERM", (int) Math.round(point.termDays));
                row.put("RATE", point.rate);
                JsonNumberUtils.normalizeNumbersInPlace(row);
                curveData.add(row);
            }
            curve.put("CURVE_DATA", curveData);
            output.add(curve);
        }

        Map<String, List<CurveGeneration.DeltaTermVol>> volGrouped = new LinkedHashMap<>();
        for (CurveGeneration.DeltaTermVol point : result.volPoints) {
            if (point == null || point.curveId == null || point.curveId.trim().isEmpty()) {
                continue;
            }
            volGrouped.computeIfAbsent(point.curveId, ignored -> new ArrayList<>()).add(point);
        }

        for (Map.Entry<String, List<CurveGeneration.DeltaTermVol>> entry : volGrouped.entrySet()) {
            List<CurveGeneration.DeltaTermVol> points = entry.getValue();
            if (points.isEmpty()) {
                continue;
            }
            CurveGeneration.DeltaTermVol first = points.get(0);
            JSONObject curve = new JSONObject();
            curve.put("CURVE_TYPE", EngineConstants.RF_TYPE.FX_VOL);
            curve.put("CURVE_ID", first.curveId);
            if (first.dataDate != null) {
                curve.put("DATA_DATE", first.dataDate.format(BASIC_DATE));
            }

            JSONArray curveData = new JSONArray();
            for (CurveGeneration.DeltaTermVol point : points) {
                JSONObject row = new JSONObject();
                row.put("OPTION_TERM", (int) Math.round(point.termDays));
                row.put("DELTA", point.delta);
                row.put("VOLATILITY_RATE", point.fxVol);
                JsonNumberUtils.normalizeNumbersInPlace(row);
                curveData.add(row);
            }
            curve.put("CURVE_DATA", curveData);
            output.add(curve);
        }

        return output;
    }
}
