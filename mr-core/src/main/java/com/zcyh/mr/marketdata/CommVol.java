package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class CommVol implements Serializable {
    private final CommVolInfo commVolInfo;

    public CommVol(CommVolInfo commVolInfo) {
        this.commVolInfo = commVolInfo;
    }

    public static void validateInput(String curveId, CommVolInfo info, List<String> errors) {
        MarketDataValidationSupport.validateVolSurface(
                curveId,
                info == null ? null : info.curveData,
                info == null ? null : info.axis2Type,
                VolAxisType.DELTA,
                errors);
    }

    public List<VolSurfacePoint> getVolCur(int term) {
        List<VolSurfacePoint> baseVolCur = VolUtil.getVolCur(
                term,
                commVolInfo.curveData,
                commVolInfo.termInterpolateType,
                commVolInfo.axis2InterpolateType);
        if (commVolInfo.shockCurveData == null || commVolInfo.shockCurveData.isEmpty()) {
            return baseVolCur;
        }
        List<VolSurfacePoint> shockVolCur = VolUtil.getShockVolCurByLinearOptionTerm(
                term, commVolInfo.shockCurveData, commVolInfo.axis2InterpolateType);
        return markShockApplied(VolUtil.mergeVolCurve(baseVolCur, shockVolCur));
    }

    private List<VolSurfacePoint> markShockApplied(List<VolSurfacePoint> curveData) {
        List<VolSurfacePoint> result = new ArrayList<VolSurfacePoint>(curveData.size());
        for (VolSurfacePoint point : curveData) {
            result.add(point.markShockApplied());
        }
        return result;
    }

    public static class CommVolInfo implements Serializable {
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
