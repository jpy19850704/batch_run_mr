package com.zcyh.mr.marketdata;

import com.alibaba.fastjson2.annotation.JSONField;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Series;
import com.zcyh.mr.core.CurveFunc;
import com.zcyh.mr.core.Interpolation;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 利率即期曲线
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class IrSpot implements Serializable {

    private IrSpotInfo irSpotInfo;

    public IrSpot(IrSpotInfo irSpotInfo) {
        this.irSpotInfo = irSpotInfo;
    }

    public IrSpotInfo getIrSpotInfo() {
        return irSpotInfo;
    }

    /**
     * 通过现有的利率即期曲线获取任一时间点的即期利率
     *
     * @param date: 某一时间点
     * @return double
     * @author lsd
     * @date 2024/7/10 15:00
     */
    public double spotRate(LocalDate date) {
        int days = (int) ChronoUnit.DAYS.between(this.irSpotInfo.pDataDate, date);
        return rateWithShock(this.irSpotInfo.curveData, days);
    }

    /**
     * 利率即期曲线的折现因子
     *
     * @param date: 某一时间点
     * @return double
     * @author lsd
     * @date 2024/7/10 14:58
     */
    public double discount(LocalDate date) {
        int days = (int) ChronoUnit.DAYS.between(this.irSpotInfo.pDataDate, date);
        double rate = rateWithShock(this.irSpotInfo.curveData, days);
        return CurveFunc.discountFactor(this.irSpotInfo.pDataDate, date, rate, irSpotInfo.freq, irSpotInfo.dayCount);
    }

    public double discount(LocalDate date, Series<Integer, Double> curveData1) {
        int days = (int) ChronoUnit.DAYS.between(this.irSpotInfo.pDataDate, date);
        double rate = rateWithShock(curveData1, days);
        return CurveFunc.discountFactor(this.irSpotInfo.pDataDate, date, rate, irSpotInfo.freq, irSpotInfo.dayCount);
    }

    /**
     * 远期折现因子
     *
     * @param from: 开始日期
     * @param to:   结束日期
     * @return double
     * @author lsd
     * @date 2024/7/10 15:02
     */
    public double fwdDiscount(LocalDate from, LocalDate to) {
        double fwdDisc = 1;
        if (from.isAfter(to)) {
            fwdDisc = 1;
        }
        if (to.isBefore(irSpotInfo.pDataDate)) {
            fwdDisc = 0;
        } else if (from.isBefore(irSpotInfo.pDataDate)) {
            double rate = spotRate(to);
            fwdDisc = CurveFunc.discountFactor(from, to, rate, irSpotInfo.freq, irSpotInfo.dayCount);
        } else {
            double disc1 = this.discount(from);
            double disc2 = this.discount(to);
            if (disc1 != 0) {
                fwdDisc = disc2 / disc1;
            } else {
                fwdDisc = 0;
            }
        }
        return fwdDisc;
    }

    /**
     * 计算某一时间段的远期利率
     *
     * @param from: 开始时间
     * @param to:   结束时间
     * @return
     */
    public double fwdRate(LocalDate from, LocalDate to) {
        // 起始日早于结束日
        LocalDate originDate = irSpotInfo.pDataDate;
        double fwdRate = 0;
        if ((!from.isAfter(originDate) && (!to.isAfter(originDate)))) {
            // 起始日到结束日均不晚于原始日期
            fwdRate = 0;
        } else if (!from.isAfter(originDate)) {
            // 原始日期位于起始日和结束日之间
            fwdRate = this.spotRate(to);
        } else {
            // 原始日期早于起始日，起始日早于结束日
            double fwddisc = fwdDiscount(from, to);
            fwdRate = CurveFunc.rateFromDiscountFactor(from, to, fwddisc, irSpotInfo.freq, irSpotInfo.dayCount);
        }
        return fwdRate;
    }

    /**
     * 利率即期曲线的折现因子
     *
     * @param date:   给定日期
     * @param spread: 对给定日期的利率新增的变动值
     * @return double
     * @author lsd
     * @date 2024/7/10 15:39
     */
    public double discount(LocalDate date, double spread) {
        int days = (int) ChronoUnit.DAYS.between(this.irSpotInfo.pDataDate, date);
        double rate = rateWithShock(this.irSpotInfo.curveData, days);
        return CurveFunc.discountFactor(this.irSpotInfo.pDataDate, date, rate + spread, irSpotInfo.freq,
                irSpotInfo.dayCount);
    }

    private double rateWithShock(Series<Integer, Double> curveData, int days) {
        double rate = Interpolation.interpolate(curveData, days, this.irSpotInfo.interpolateType);
        if (this.irSpotInfo.shockCurveData != null && !this.irSpotInfo.shockCurveData.isEmpty()) {
            rate += Interpolation.interpolate(this.irSpotInfo.shockCurveData, days, "linear");
        }
        return rate;
    }

    /**
     * 平价互换利率：使固定腿与浮动腿 PV 相等的固定利率
     * f = (1 - DF(Tn)/DF(T0)) / Σ(DF(Ti) × Δti / DF(T0))
     *
     * @param startDate 互换起始日
     * @param termCode  互换总期限代码，如 "10Y", "5Y"
     * @param freqCode  付息频率代码，如 "1Y", "6M", "3M"，null 时默认 "1Y"
     * @return 平价互换利率
     */
    public double parSwapRate(LocalDate startDate, String termCode, String freqCode) {
        LocalDate endDate = parseTermCodeToDate(startDate, termCode);
        int freqMonths = resolveFreqMonths(freqCode);
        long totalMonths = ChronoUnit.MONTHS.between(startDate, endDate);
        int totalPeriods = Math.max(1, (int) (totalMonths / freqMonths));

        double dfBase = discount(startDate);
        double annuity = 0.0;
        LocalDate prevDate = startDate;
        LocalDate periodEnd = startDate;
        for (int i = 1; i <= totalPeriods; i++) {
            periodEnd = startDate.plusMonths((long) i * freqMonths);
            double df = discount(periodEnd);
            double interval = ChronoUnit.DAYS.between(prevDate, periodEnd) / 365.0;
            annuity += df * interval / dfBase;
            prevDate = periodEnd;
        }
        double dfEnd = discount(periodEnd);
        if (Math.abs(annuity) < 1e-15) {
            return 0.0;
        }
        return (1 - dfEnd / dfBase) / annuity;
    }

    /**
     * 将频率代码解析为月数，如 "1Y"→12, "6M"→6, "3M"→3，null/空→12
     */
    private static int resolveFreqMonths(String freqCode) {
        if (freqCode == null || freqCode.trim().isEmpty()) {
            return 12;
        }
        String code = freqCode.trim().toUpperCase();
        if (code.endsWith("Y")) {
            return Integer.parseInt(code.substring(0, code.length() - 1)) * 12;
        } else if (code.endsWith("M")) {
            return Integer.parseInt(code.substring(0, code.length() - 1));
        }
        return 12;
    }

    /**
     * 将期限代码解析为目标日期，委托 Calendar.dateAdd 处理
     */
    public static LocalDate parseTermCodeToDate(LocalDate base, String termCode) {
        if (termCode == null || termCode.trim().length() < 2) {
            return base.plusYears(10);
        }
        String code = termCode.trim().toUpperCase();
        String mark = code.substring(code.length() - 1);
        int amount;
        try {
            amount = Integer.parseInt(code.substring(0, code.length() - 1));
        } catch (NumberFormatException e) {
            return base.plusYears(10);
        }
        String unit;
        switch (mark) {
            case "D":
                unit = "days";
                break;
            case "W":
                unit = "weeks";
                break;
            case "M":
                unit = "months";
                break;
            case "Y":
                unit = "years";
                break;
            default:
                return base.plusYears(10);
        }
        return Calendar.dateAdd(base, amount, unit);
    }

    public static class IrSpotInfo implements Serializable {
        @JSONField(name = "CURVE_TYPE")
        public String curveType;
        @JSONField(name = "CURVE_ID")
        public String curveCode;
        @JSONField(name = "DATA_DATE", format = "yyyyMMdd")
        public LocalDate dataDate;
        @JSONField(name = "DAYCOUNT")
        public String dayCount;
        @JSONField(name = "FREQ")
        public String freq;
        @JSONField(name = "P_DATA_DATE", format = "yyyyMMdd")
        public LocalDate pDataDate;
        @JSONField(name = "INTERPOLATE_TYPE")
        public String interpolateType;
        public Series<Integer, Double> curveData = new Series<>(Integer.class, Double.class);
        /**
         * FRTB GIRR/CSR Delta 专用 shock overlay，只在敏感性重估时叠加。
         * 基础利率曲线仍保留在 curveData 中，overlay 仅保存标准期限点增量。
         */
        public Series<Integer, Double> shockCurveData = new Series<>(Integer.class, Double.class);

        public void shift(Double delta) {
            for (Integer key : curveData.keySet()) {
                curveData.put(key, curveData.get(key) + delta);
            }
        }

        public void shift(Series<Integer, Double> deltas) {
            for (Integer key : curveData.keySet()) {
                if (deltas.get(key) != null) {
                    curveData.put(key, curveData.get(key) + deltas.get(key));
                } else {
                    double rate = Interpolation.interpolate(deltas, key, this.interpolateType);
                    curveData.put(key, curveData.get(key) + rate);
                }
            }
        }

        @Override
        public String toString() {
            return "IrSpotInfo{" + "curveType='" + curveType + '\'' + ", curveCode='" + curveCode + '\'' + ", dataDate="
                    + dataDate + ", dayCount='" + dayCount + '\'' + ", freq='" + freq + '\'' + ", curveData="
                    + curveData + '}';
        }
    }
}
