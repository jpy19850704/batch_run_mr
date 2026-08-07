package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class IrVol implements Serializable {
    private final IrVolInfo irVolInfo;

    public IrVol(IrVolInfo irVolInfo) {
        this.irVolInfo = irVolInfo;
    }

    public static void validateInput(String curveId, IrVolInfo info, List<String> errors) {
        MarketDataValidationSupport.validateVolSurface(
                curveId,
                info == null ? null : info.curveData,
                info == null ? null : info.axis2Type,
                VolAxisType.UNDERLYING_TERM,
                errors);
    }

    public List<VolSurfacePoint> getVolCur(int term) {
        List<VolSurfacePoint> baseVolCur = VolUtil.getVolCur(
                term,
                irVolInfo.curveData,
                irVolInfo.termInterpolateType,
                irVolInfo.axis2InterpolateType);
        if (irVolInfo.shockCurveData == null || irVolInfo.shockCurveData.isEmpty()) {
            return baseVolCur;
        }
        List<VolSurfacePoint> shockVolCur = VolUtil.getShockVolCurByLinearOptionTerm(
                term, irVolInfo.shockCurveData, irVolInfo.axis2InterpolateType);
        return markShockApplied(VolUtil.mergeVolCurve(baseVolCur, shockVolCur));
    }

    public double underlyingTerm(int term, List<VolSurfacePoint> vol) {
        double baseValue = VolUtil.underlyingTerm(term, vol, irVolInfo.axis2InterpolateType);
        if (isShockApplied(vol)
                || irVolInfo.shockCurveData == null
                || irVolInfo.shockCurveData.isEmpty()) {
            return baseValue;
        }
        List<VolSurfacePoint> shockVol = VolUtil.getShockVolCurByLinearOptionTerm(
                vol.get(0).getOptionTerm(),
                irVolInfo.shockCurveData,
                irVolInfo.axis2InterpolateType);
        double shockRatio = VolUtil.underlyingTerm(term, shockVol, irVolInfo.axis2InterpolateType);
        return baseValue * (1.0d + shockRatio);
    }

    private List<VolSurfacePoint> markShockApplied(List<VolSurfacePoint> curveData) {
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>(curveData.size());
        for (VolSurfacePoint point : curveData) {
            result.add(point.markShockApplied());
        }
        return result;
    }

    private boolean isShockApplied(List<VolSurfacePoint> curveData) {
        return curveData != null && !curveData.isEmpty() && curveData.get(0).isShockApplied();
    }

    public static class IrVolInfo implements Serializable {
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
        @JSONField(name = "DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate dataDate;
        @JSONField(name = "P_DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate pDataDate;
        public List<VolSurfacePoint> curveData = new ArrayList<VolSurfacePoint>();
        public List<VolSurfacePoint> shockCurveData = new ArrayList<VolSurfacePoint>();
    }
}
