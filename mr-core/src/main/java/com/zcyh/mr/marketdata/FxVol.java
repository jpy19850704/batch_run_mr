package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.CommUtils;
import com.zcyh.mr.support.Convert;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FxVol implements Serializable {

    public static void validateInput(String curveId, FxVolInfo info, List<String> errors) {
        MarketDataValidationSupport.validateVolSurface(
                curveId,
                info == null ? null : info.curveData,
                info == null ? null : info.axis2Type,
                null,
                errors);
    }

    private static final String FRTB_VEGA_SHOCK_APPLIED = "_FRTB_VEGA_SHOCK_APPLIED";

    private FxVolInfo fxVolInfo;

    public FxVol(FxVolInfo fxVolInfo) {
        this.fxVolInfo = fxVolInfo;
    }

    public List<Map<String, Object>> getVolCur(int term) {
        try {
            List<Map<String, Object>> baseVol = renameToVertex(fxVolInfo.curveData);
            List<Map<String, Object>> baseVolCur = VolUtil.getVolCur(term, baseVol,
                    fxVolInfo.termInterpolateType, fxVolInfo.axis2InterpolateType);
            if (fxVolInfo.shockCurveData == null || fxVolInfo.shockCurveData.isEmpty()) {
                return renameToOptionTerm(baseVolCur);
            }

            List<Map<String, Object>> shockVol = renameToVertex(fxVolInfo.shockCurveData);
            List<Map<String, Object>> shockVolCur = VolUtil.getShockVolCurByLinearOptionTerm(
                    term, shockVol, fxVolInfo.axis2InterpolateType);
            return markShockApplied(renameToOptionTerm(VolUtil.mergeVolCurve(baseVolCur, shockVolCur)));
        } catch (NullPointerException e) {
            throw new IllegalArgumentException(
                    "FX_VOL 曲线 [" + fxVolInfo.curveCode
                            + "] 数据异常，请检查 CURVE_DATA 中是否包含 OPTION_TERM、DELTA、VOLATILITY_RATE 字段",
                    e);
        }
    }

    public List<Map<String, Object>> getVegaVolCur(int term, double eps) {
        try {
            List<Map<String, Object>> vol = renameToVertex(fxVolInfo.curveData);
            for (Map<String, Object> map : vol) {
                map.put("VOLATILITY_RATE", eps + Convert.toDouble(map.get("VOLATILITY_RATE")));
            }
            List<Map<String, Object>> volCur = VolUtil.getVolCur(term, vol,
                    fxVolInfo.termInterpolateType, fxVolInfo.axis2InterpolateType);
            return renameToOptionTerm(volCur);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException(
                    "FX_VOL 曲线 [" + fxVolInfo.curveCode
                            + "] 数据异常，请检查 CURVE_DATA 中是否包含 OPTION_TERM、DELTA、VOLATILITY_RATE 字段",
                    e);
        }
    }

    private List<Map<String, Object>> renameToVertex(List<Map<String, Object>> curveData) {
        String axis2Field = axis2Field();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new HashMap<>(curveDatum);
            newMap.put("VERTEX1", curveDatum.get("OPTION_TERM"));
            newMap.put("VERTEX2", axis2Field == null ? 0.0 : curveDatum.get(axis2Field));
            result.add(newMap);
        }
        return result;
    }

    private List<Map<String, Object>> renameToOptionTerm(List<Map<String, Object>> curveData) {
        String axis2Field = axis2Field();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new HashMap<>(curveDatum);
            newMap.put("OPTION_TERM", curveDatum.get("VERTEX1"));
            if (axis2Field != null) {
                newMap.put(axis2Field, curveDatum.get("VERTEX2"));
            }
            newMap.remove("VERTEX1");
            newMap.remove("VERTEX2");
            result.add(newMap);
        }
        return result;
    }

    private String axis2Field() {
        return VolUtil.resolveAxis2Field(fxVolInfo.axis2Type);
    }

    private List<Map<String, Object>> markShockApplied(List<Map<String, Object>> curveData) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new HashMap<>(curveDatum);
            newMap.put(FRTB_VEGA_SHOCK_APPLIED, true);
            result.add(newMap);
        }
        return result;
    }

    public static class FxVolInfo implements Serializable {
        @JSONField(name = "CURVE_TYPE")
        public String curveType;
        @JSONField(name = "CURVE_ID")
        public String curveCode;
        @JSONField(name = "TERM_INTERPOLATE_TYPE")
        public String termInterpolateType;
        @JSONField(name = "AXIS2_TYPE")
        public String axis2Type;
        @JSONField(name = "AXIS2_INTERPOLATE_TYPE")
        public String axis2InterpolateType;
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;
        @JSONField(name = "CURVE_DATA")
        public List<Map<String, Object>> curveData = new ArrayList<>();
        /**
         * FRTB Vega 专用 shock 比例面，只在 Vega 情景重估时使用。
         * 基础波动率曲面仍保留在 curveData 中。
         */
        public List<Map<String, Object>> shockCurveData = new ArrayList<>();
        @JSONField(name = "P_DATA_DATE", format = "yyyyMMdd")
        public LocalDate pDataDate;
    }
}
