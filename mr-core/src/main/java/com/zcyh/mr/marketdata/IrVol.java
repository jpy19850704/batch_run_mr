package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Convert;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author xujg
 * @date 2024-09-23 18:10
 */
public class IrVol implements Serializable {

    private static final String FRTB_VEGA_SHOCK_APPLIED = "_FRTB_VEGA_SHOCK_APPLIED";

    private IrVol.IrVolInfo irVolInfo;

    public IrVol(IrVol.IrVolInfo irVolInfo) {
        this.irVolInfo = irVolInfo;
    }

    /**
     * @description 通过线性插值方法获取波动率
     * @date 2024-09-23 18:12:967
     * @author xujg
     */
    public List<Map<String,Object>> getVolCur(int term) {
        List<Map<String,Object>> baseVol = renameToVertex(irVolInfo.curveData);
        List<Map<String, Object>> baseVolCur = VolUtil.getVolCur(term, baseVol,
                irVolInfo.termInterpolateType, irVolInfo.axis2InterpolateType);
        if (irVolInfo.shockCurveData == null || irVolInfo.shockCurveData.isEmpty()) {
            return renameToOptionTerm(baseVolCur);
        }

        // FRTB Vega 场景下，基础面继续沿用方差插值，shock 比例面单独按 option tenor 线性传播。
        List<Map<String,Object>> shockVol = renameToVertex(irVolInfo.shockCurveData);
        List<Map<String, Object>> shockVolCur = VolUtil.getShockVolCurByLinearOptionTerm(
                term, shockVol, irVolInfo.axis2InterpolateType);
        return markShockApplied(renameToOptionTerm(VolUtil.mergeVolCurve(baseVolCur, shockVolCur)));
    }

    public double underlyingTerm(int term, List<Map<String,Object>> vol) {
        List<Map<String,Object>> baseVol = renameToVertex(vol);
        double baseValue = VolUtil.underlyingTerm(term, baseVol, this.irVolInfo.axis2InterpolateType);
        if (isShockApplied(vol)) {
            return baseValue;
        }
        if (irVolInfo.shockCurveData == null || irVolInfo.shockCurveData.isEmpty()) {
            return baseValue;
        }

        // 底层 tenor 暂时沿用现有插值口径，仅对 option tenor 的 shock 比例传播单独线性处理。
        List<Map<String,Object>> shockVol = renameToVertex(VolUtil.getShockVolCurByLinearOptionTerm(
                Convert.toInt(vol.get(0).get("OPTION_TERM")),
                renameToVertex(irVolInfo.shockCurveData),
                this.irVolInfo.axis2InterpolateType));
        double shockRatio = VolUtil.underlyingTerm(term, shockVol, this.irVolInfo.axis2InterpolateType);
        return baseValue * (1.0 + shockRatio);
    }

    /**
     * 统一把内部字段名转换成 VERTEX 风格，便于公共工具处理。
     */
    private List<Map<String, Object>> renameToVertex(List<Map<String, Object>> curveData) {
        String axis2Field = axis2Field();
        List<Map<String,Object>> vol = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new LinkedHashMap<>(curveDatum);
            newMap.put("VERTEX1", curveDatum.get("OPTION_TERM"));
            newMap.put("VERTEX2", axis2Field == null ? 0.0 : curveDatum.get(axis2Field));
            vol.add(newMap);
        }
        return vol;
    }

    /**
     * 统一把公共工具输出的 VERTEX 风格字段恢复成波动率曲面字段。
     */
    private List<Map<String, Object>> renameToOptionTerm(List<Map<String, Object>> curveData) {
        String axis2Field = axis2Field();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new LinkedHashMap<>(curveDatum);
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
        String axis2Type = irVolInfo.axis2Type == null || irVolInfo.axis2Type.trim().isEmpty()
                ? "UNDERLYING_TERM"
                : irVolInfo.axis2Type;
        return VolUtil.resolveAxis2Field(axis2Type);
    }

    private List<Map<String, Object>> markShockApplied(List<Map<String, Object>> curveData) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> curveDatum : curveData) {
            Map<String, Object> newMap = new LinkedHashMap<>(curveDatum);
            newMap.put(FRTB_VEGA_SHOCK_APPLIED, true);
            result.add(newMap);
        }
        return result;
    }

    private boolean isShockApplied(List<Map<String, Object>> curveData) {
        return curveData != null
                && !curveData.isEmpty()
                && Boolean.TRUE.equals(curveData.get(0).get(FRTB_VEGA_SHOCK_APPLIED));
    }

    public static class IrVolInfo implements Serializable{
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
        // TERM_DAYS,VERTEX2,VOLATILITY_RATE
        @JSONField(name = "CURVE_DATA")
        public List<Map<String,Object>> curveData = new ArrayList<>();
        /**
         * FRTB Vega 专用 shock 比例面，只在 Vega 情景重估时使用。
         * 基础波动率曲面仍保留在 curveData 中，shock 面只保存比例，不参与普通定价。
         */
        public List<Map<String,Object>> shockCurveData = new ArrayList<>();

    }
}
