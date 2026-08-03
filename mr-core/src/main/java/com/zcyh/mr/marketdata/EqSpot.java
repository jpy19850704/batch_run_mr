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
 * 权益即期曲线。
 */
public class EqSpot implements Serializable {

    public static void validateInput(String curveId, EqSpotInfo info, List<String> errors) {
        if (curveId == null || curveId.trim().isEmpty()) {
            errors.add("CURVE_ID 不能为空");
            return;
        }
        if (info == null || info.curveData == null || info.curveData.isEmpty()) {
            errors.add(curveId + ": CURVE_DATA 不能为空");
            return;
        }
        for (Map.Entry<Integer, Double> point : info.curveData.entrySet()) {
            if (point.getKey() == null || point.getValue() == null || !Double.isFinite(point.getValue())) {
                errors.add(curveId + ": TERM和EQ_PRICE必须为有效数值");
            }
        }
    }

    private EqSpotInfo eqSpotInfo;

    public EqSpot(EqSpotInfo eqSpotInfo) {
        this.eqSpotInfo = eqSpotInfo;
    }

    /**
     * 权益远期价格，通过线性插值方法获取。
     *
     * @param date 远期日期
     * @return 远期价格
     */
    public double fwdPrice(LocalDate date) {
        int days = (int) ChronoUnit.DAYS.between(this.eqSpotInfo.pDataDate, date);
        return Interpolation.interpolate(this.eqSpotInfo.curveData, days, this.eqSpotInfo.interpolateType);
    }

    public static class EqSpotInfo implements Serializable {
        @JSONField(name = "CURVE_TYPE")
        public String curveType;
        @JSONField(name = "CURVE_ID")
        public String curveCode;
        @JSONField(name = "DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate dataDate;
        @JSONField(name = "BASE_CURRENCY_CODE")
        public String currency;
        @JSONField(name = "P_DATA_DATE", format = "yyyy-MM-dd")
        public LocalDate pDataDate;
        @JSONField(name = "INTERPOLATE_TYPE")
        public String interpolateType;
        public Series<Integer, Double> curveData = new Series<>(Integer.class, Double.class);

        public void shift(Double delta) {
            for (Integer key : curveData.keySet()) {
                curveData.put(key, curveData.get(key) + delta);
            }
        }
    }
}
