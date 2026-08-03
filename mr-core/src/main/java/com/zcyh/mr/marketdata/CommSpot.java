package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.marketdata.support.CmtyShockSupport;
import com.zcyh.mr.support.Series;
import com.zcyh.mr.math.Interpolation;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.time.temporal.ChronoUnit;


/**
 * 商品即期曲线
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class CommSpot implements Serializable {

    public static void validateInput(String curveId, CommSpotInfo info, List<String> errors) {
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
                errors.add(curveId + ": TERM和COMM_PRICE必须为有效数值");
            }
        }
    }

    private CommSpotInfo commSpotInfo;

    public CommSpot(CommSpotInfo commSpotInfo) {
        this.commSpotInfo = commSpotInfo;
    }

    /**
     * 商品远期价格，通过线性插值方法获取
     *
     * @param date: 远期日期
     * @return double
     * @author lsd
     * @date 2024/7/10 15:21
     */
    public double fwdPrice(LocalDate date) {
        int days = (int) ChronoUnit.DAYS.between(this.commSpotInfo.pDataDate, date);
        double baseValue = Interpolation.interpolate(this.commSpotInfo.curveData, days, this.commSpotInfo.interpolateType);
        if (this.commSpotInfo.shockCurveData == null || this.commSpotInfo.shockCurveData.isEmpty()) {
            return baseValue;
        }
        double shockRatio = CmtyShockSupport.resolveShockRatio(this.commSpotInfo.shockCurveData, days);
        return baseValue * (1 + shockRatio);
    }

    public static class CommSpotInfo implements Serializable {
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
        /**
         * FRTB 商品 Delta 专用 shock 比例曲线。
         * 基础商品曲线仍保留在 curveData 中，shock 曲线仅在情景重估时按比例叠加。
         */
        public Series<Integer, Double> shockCurveData = new Series<>(Integer.class, Double.class);

        public void shift(Double delta) {
            for(Integer key: curveData.keySet()) {
                curveData.put(key, curveData.get(key) + delta);
            }
        }
    }
}
