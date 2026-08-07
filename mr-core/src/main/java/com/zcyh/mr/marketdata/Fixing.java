package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.support.Series;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.time.temporal.ChronoUnit;

/**
 * 通用定盘数据：用于按日期获取已发生的 fixing 值。
 */
public class Fixing implements Serializable {

    public static void validateInput(String fixingId, FixingInfo info, List<String> errors) {
        if (fixingId == null || fixingId.trim().isEmpty()) {
            errors.add("FIXING_ID 不能为空");
            return;
        }
        if (info == null || info.curveData == null || info.curveData.isEmpty()) {
            errors.add(fixingId + ": CURVE_DATA 不能为空");
            return;
        }
        for (Map.Entry<LocalDate, Double> point : info.curveData.entrySet()) {
            if (point.getKey() == null || point.getValue() == null || !Double.isFinite(point.getValue())) {
                errors.add(fixingId + ": TRADE_DATE和FIXING_VALUE必须为有效值");
            }
        }
    }
    private final FixingInfo fixingInfo;
    private final Interpolation.PreparedInterpolator preparedInterpolator;

    public Fixing(FixingInfo fixingInfo) {
        if (fixingInfo == null || fixingInfo.pDataDate == null
                || fixingInfo.curveData == null || fixingInfo.curveData.isEmpty()) {
            throw new IllegalArgumentException("定盘曲线数据不完整");
        }
        this.fixingInfo = fixingInfo;
        Series<Integer, Double> curveData = new Series<>(Integer.class, Double.class);
        for (LocalDate date : fixingInfo.curveData.keySet()) {
            int term = (int) ChronoUnit.DAYS.between(fixingInfo.pDataDate, date);
            curveData.put(term, fixingInfo.curveData.get(date));
        }
        String interpolateType = fixingInfo.interpolateType;
        if (interpolateType == null || interpolateType.trim().isEmpty()) {
            interpolateType = Interpolation.Type.FORWARD.name();
        }
        this.preparedInterpolator = Interpolation.prepare(curveData, interpolateType);
    }

    public double getRate(LocalDate date) {
        int days = (int) ChronoUnit.DAYS.between(this.fixingInfo.pDataDate, date);
        return preparedInterpolator.interpolate(days);
    }

    public static class FixingInfo implements Serializable {
        @JSONField(name = "CURVE_TYPE")
        public String curveType;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @JSONField(name = "DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate dataDate;
        @JSONField(name = "P_DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate pDataDate;
        @JSONField(name = "INTERPOLATE_TYPE")
        public String interpolateType;
        public Series<LocalDate, Double> curveData = new Series<>(LocalDate.class, Double.class);
    }
}
