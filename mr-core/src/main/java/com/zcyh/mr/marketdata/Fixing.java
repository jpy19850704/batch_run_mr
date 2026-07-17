package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.math.Interpolation;
import com.zcyh.mr.support.Series;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 通用定盘数据：用于按日期获取已发生的 fixing 值。
 */
public class Fixing implements Serializable {
    private FixingInfo fixingInfo;

    public Fixing(FixingInfo fixingInfo) {
        this.fixingInfo = fixingInfo;
    }

    public double getRate(LocalDate date) {
        int days = (int) ChronoUnit.DAYS.between(this.fixingInfo.pDataDate, date);
        Series<Integer, Double> curveData = new Series<>(Integer.class, Double.class);
        for (LocalDate date1 : this.fixingInfo.curveData.keySet()) {
            int term = (int) ChronoUnit.DAYS.between(this.fixingInfo.pDataDate, date1);
            curveData.put(term, this.fixingInfo.curveData.get(date1));
        }
        String interpolateType = fixingInfo.interpolateType;
        if (interpolateType == null || interpolateType.trim().isEmpty()) {
            interpolateType = Interpolation.Type.FORWARD.name();
        }
        return Interpolation.interpolate(curveData, days, interpolateType);
    }

    public static class FixingInfo implements Serializable {
        @JSONField(name = "CURVE_TYPE")
        public String curveType;
        @JSONField(name = "FIXING_ID")
        public String fixingId;
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;
        @JSONField(name = "P_DATA_DATE", format = "yyyyMMdd")
        public LocalDate pDataDate;
        @JSONField(name = "INTERPOLATE_TYPE")
        public String interpolateType;
        public Series<LocalDate, Double> curveData = new Series<>(LocalDate.class, Double.class);
    }
}
