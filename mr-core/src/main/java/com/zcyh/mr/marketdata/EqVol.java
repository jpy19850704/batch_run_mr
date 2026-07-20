package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.support.CommUtils;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 权益波动率曲面。
 */
public class EqVol implements Serializable {

    public static void validateInput(String curveId, EqVolInfo info, List<String> errors) {
        MarketDataValidationSupport.validateVolSurface(
                curveId,
                info == null ? null : info.curveData,
                info == null ? null : info.axis2Type,
                null,
                errors);
    }

    private static final String FRTB_VEGA_SHOCK_APPLIED = "_FRTB_VEGA_SHOCK_APPLIED";

    private EqVolInfo eqVolInfo;

    public EqVol(EqVolInfo eqVolInfo) {
        this.eqVolInfo = eqVolInfo;
    }

    public List<Map<String, Object>> getVolCur(int term) {
        List<Map<String, Object>> baseVol = renameToVertex(eqVolInfo.curveData);
        List<Map<String, Object>> baseVolCur = VolUtil.getVolCur(term, baseVol,
                eqVolInfo.termInterpolateType, eqVolInfo.axis2InterpolateType);
        if (eqVolInfo.shockCurveData == null || eqVolInfo.shockCurveData.isEmpty()) {
            return renameToOptionTerm(baseVolCur);
        }

        List<Map<String, Object>> shockVol = renameToVertex(eqVolInfo.shockCurveData);
        List<Map<String, Object>> shockVolCur = VolUtil.getShockVolCurByLinearOptionTerm(
                term, shockVol, eqVolInfo.axis2InterpolateType);
        return markShockApplied(renameToOptionTerm(VolUtil.mergeVolCurve(baseVolCur, shockVolCur)));
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
        return VolUtil.resolveAxis2Field(eqVolInfo.axis2Type);
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

    public static class EqVolInfo implements Serializable {
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
        @JSONField(name = "P_DATA_DATE", format = "yyyyMMdd")
        public LocalDate pDataDate;
        @JSONField(name = "CURVE_DATA")
        public List<Map<String, Object>> curveData = new ArrayList<>();
        /**
         * FRTB Vega 专用 shock 比例面，只在 Vega 情景重估时使用。
         * 基础波动率曲面仍保留在 curveData 中。
         */
        public List<Map<String, Object>> shockCurveData = new ArrayList<>();
    }
}
