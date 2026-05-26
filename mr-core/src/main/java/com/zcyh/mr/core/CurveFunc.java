package com.zcyh.mr.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 曲线相关的公共函数类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class CurveFunc {

    /**
     * 日历规则下，某一时间段内的天数
     *
     * @param start: 开始日期
     * @param end:   结束日期
     * @param dcb:   日历规则
     * @return int
     * @author lsd
     * @date 2024/7/10 15:09
     */
    public static int daysBetweenDCB(LocalDate start, LocalDate end, String dcb) {
        int yy1 = start.getYear();
        int mm1 = start.getMonthValue();
        int dd1 = start.getDayOfMonth();

        int yy2 = end.getYear();
        int mm2 = end.getMonthValue();
        int dd2 = end.getDayOfMonth();

        int n = 0;
        switch (dcb.toLowerCase()) {
            case "actual/actual":
            case "actual/360":
            case "actual/364":
            case "actual/365":
            case "actual/365 canadian":
                n = (int) ChronoUnit.DAYS.between(start, end);
                break;
            case "30/360":
                if ((mm2 == 2 && dd2 == end.lengthOfMonth()) && (mm1 == 2 && dd1 == start.lengthOfMonth())) {
                    dd2 = 30;
                }
                if (mm1 == 2 && dd1 == start.lengthOfMonth()) {
                    dd1 = 30;
                }
                if ((dd2 == 31) && (dd1 == 30 || dd1 == 31)) {
                    dd2 = 30;
                }
                if (dd1 == 31) {
                    dd1 = 30;
                }
                n = ((yy2 - yy1) * 360 + (mm2 - mm1) * 30 + (dd2 - dd1));
                break;
            case "30/360 european":
            case "30/360 french":
                if (dd1 == 31) {
                    dd1 = 30;
                }
                if (dd2 == 31) {
                    dd2 = 30;
                }
                n = ((yy2 - yy1) * 360 + (mm2 - mm1) * 30 + (dd2 - dd1));
                break;
            default:
                n = 0;
        }
        return n;
    }

    /**
     * 日算规则下，某一年的总天数
     *
     * @param date: 输入日期
     * @param dcb:  日算规则
     * @return int
     * @author lsd
     * @date 2024/7/10 15:10
     */
    public static int baseYearDCB(LocalDate date, String dcb) {
        int n = 365;
        switch (dcb.toLowerCase()) {
            case "actual/actual":
            case "actual/actual french":
                n = date.lengthOfYear();
                break;
            case "actual/360":
                n = 360;
                break;
            case "actual/364":
                n = 364;
                break;
            case "actual/365":
            case "actual/365 canadian":
                n = 365;
                break;
            case "30/360":
            case "30/360 european":
            case "30/360 french":
                n = 360;
                break;
            case "business/252":
                n = 252;
                break;
            default:
                n = 365;
                break;

        }
        return n;
    }

    /**
     * 时间因子： 某一日算规则下，时间段等效为多少年
     *
     * @param start: 开始日期
     * @param end:   结束日期
     * @param dcb:   日算规则
     * @return double
     * @author lsd
     * @date 2024/7/10 15:11
     */
    public static double timeFactor(LocalDate start, LocalDate end, String dcb) {
        double tf = 0;
        if (start.isEqual(end)) {
            tf = 0;
        } else if (start.isAfter(end)) {
            double n = daysBetweenDCB(end, start, dcb);
            double dib = baseYearDCB(start, dcb);
            tf = -(n / dib);
        } else {
            double n = daysBetweenDCB(start, end, dcb);
            double dib = baseYearDCB(end, dcb);
            if ("actual/actual".equalsIgnoreCase(dcb)) {
                double dib2 = dib;
                double dib1 = baseYearDCB(start, dcb);
                tf = (double) end.getDayOfYear() / dib2 + (double) (dib1 - start.getDayOfYear()) / dib1 + end.getYear()
                        - start.getYear() - 1;
            } else {
                tf = n / dib;
            }
        }

        return tf;
    }

    /**
     * 折现因子函数
     *
     * @param start: 开始日期
     * @param end:   结束日期
     * @param r:     利率
     * @param frq:   付息频率
     * @param dcb:   日算规则
     * @return double
     * @author lsd
     * @date 2024/7/10 15:14
     */
    public static double discountFactor(LocalDate start, LocalDate end, double r, String frq, String dcb) {
        double disc = 0;
        if (start.isAfter(end)) {
            return 0;
        }

        double tf = timeFactor(start, end, dcb);
        double m = 0;
        switch (frq.toLowerCase()) {
            case "cont":
                disc = Math.exp(-r * tf);
                break;
            case "smp":
                disc = 1 / (1 + r * tf);
                break;
            case "annu":
                m = 1;
                disc = 1 / Math.pow(1 + r / m, m * tf);
                break;
            case "semi":
                m = 2;
                disc = 1 / Math.pow(1 + r / m, m * tf);
                break;
            case "quart":
                m = 4;
                disc = 1 / Math.pow(1 + r / m, m * tf);
                break;
            case "mon":
                m = 12;
                disc = 1 / Math.pow(1 + r / m, m * tf);
                break;
            default:
                throw new IllegalArgumentException("不支持的付息频率类型: " + frq);

        }

        return disc;
    }

    /**
     * 通过折现率获取某一时间段的远期利率
     *
     * @param start: 开始日期
     * @param end:   结束日期
     * @param dsf:   折现因子
     * @param frq:   频率
     * @param dcb:   日期规则
     * @return double
     * @author lsd
     * @date 2024/7/16 10:26
     */
    public static double rateFromDiscountFactor(LocalDate start, LocalDate end, double dsf, String frq, String dcb) {
        double rate = 0;
        if (start.isAfter(end)) {
            return 0;
        }

        double tf = timeFactor(start, end, dcb);
        double m = 0;
        switch (frq.toLowerCase()) {
            case "cont":
                rate = -Math.log(dsf) / tf;
                break;
            case "smp":
                rate = (1 / dsf - 1) / tf;
                break;
            case "annu":
                m = 1;
                rate = (Math.pow(1 / dsf, 1 / (m * tf)) - 1) * m;
                break;
            case "semi":
                m = 2;
                rate = (Math.pow(1 / dsf, 1 / (m * tf)) - 1) * m;
                break;
            case "quart":
                m = 4;
                rate = (Math.pow(1 / dsf, 1 / (m * tf)) - 1) * m;
                break;
            case "mon":
                m = 12;
                rate = (Math.pow(1 / dsf, 1 / (m * tf)) - 1) * m;
                break;
            default:
                rate = 0;
                break;
        }
        return rate;
    }

    /**
     * 不同利率之间相互转换
     *
     * @param r:        输入利率
     * @param fromDate: 开始日期
     * @param toDate:   结束日期
     * @param fromFrq:  源利率付息频率
     * @param fromDcb:  源日算规则
     * @param toFrq:    目标利率付息频率
     * @param toDcb:    目标日算规则
     * @return double
     * @author lsd
     * @date 2024/7/10 15:15
     */
    public static double convertIrRate(double r, LocalDate fromDate, LocalDate toDate, String fromFrq, String fromDcb,
            String toFrq, String toDcb) {
        if (fromDate.isAfter(toDate)) {
            return 0;
        }

        double rate = 0;
        double disc = discountFactor(fromDate, toDate, r, fromFrq, fromDcb);
        double tf = timeFactor(fromDate, toDate, toDcb);
        double m = 0;
        switch (toFrq.toLowerCase()) {
            case "cont":
                rate = -Math.log(disc) / tf;
                break;
            case "smp":
                rate = (1 / disc - 1) / tf;
                break;
            case "annu":
                m = 1;
                rate = (Math.pow(1 / disc, 1 / (m * tf)) - 1) * m;
                break;
            case "semi":
                m = 2;
                rate = (Math.pow(1 / disc, 1 / (m * tf)) - 1) * m;
                break;
            case "quart":
                m = 4;
                rate = (Math.pow(1 / disc, 1 / (m * tf)) - 1) * m;
                break;
            case "mon":
                m = 12;
                rate = (Math.pow(1 / disc, 1 / (m * tf)) - 1) * m;
                break;
            default:
                rate = 0;
                break;
        }
        return rate;
    }
}
