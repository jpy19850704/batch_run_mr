package com.zcyh.mr.product.basic.scf;

import com.zcyh.mr.core.Constants;

import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedList;

/**
 * 生成现金流类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/15 9:05
 */

public class Cashflows {
    private static final long SHORT_STUB_DAYS = 7L;

    public static LinkedList<LocalDate> generateCashflowDays(LocalDate startDate, LocalDate endDate, Period period,
            String dateGenerationRule) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("现金流日期不能为null: startDate=" + startDate + ", endDate=" + endDate);
        }
        LinkedList<LocalDate> cfDataListOri = new LinkedList<>();
        if (period == null || period.isZero()) {
            cfDataListOri.add(startDate);
            cfDataListOri.add(endDate);
        } else {
            if (Constants.DateGeneration.FOWARD.equals(dateGenerationRule)) {
                cfDataListOri.add(startDate);
                addForwardDates(cfDataListOri, startDate, endDate, period);
                cfDataListOri.add(endDate);
                mergeShortStub(cfDataListOri, true);
            } else {
                cfDataListOri.add(endDate);
                addBackwardDates(cfDataListOri, startDate, endDate, period);
                cfDataListOri.add(startDate);
                LinkedList<LocalDate> reverseList = new LinkedList<>();
                for (int i = 0, size = cfDataListOri.size(); i < size; i++) {
                    reverseList.add(cfDataListOri.get(size - 1 - i));
                }
                cfDataListOri = reverseList;
                mergeShortStub(cfDataListOri, false);
            }
        }

        return cfDataListOri;
    }

    private static void addForwardDates(LinkedList<LocalDate> dates, LocalDate startDate, LocalDate endDate,
            Period period) {
        if (isEomMonthlySchedule(startDate, endDate, period)) {
            long months = period.toTotalMonths();
            for (long step = 1L; true; step++) {
                LocalDate temp = startDate.plusMonths(months * step).with(TemporalAdjusters.lastDayOfMonth());
                if (!temp.isBefore(endDate)) {
                    break;
                }
                dates.add(temp);
            }
            return;
        }

        LocalDate left = startDate;
        while (true) {
            LocalDate temp = left.plus(period);
            if (!temp.isBefore(endDate)) {
                break;
            }
            dates.add(temp);
            left = temp;
        }
    }

    private static void addBackwardDates(LinkedList<LocalDate> dates, LocalDate startDate, LocalDate endDate,
            Period period) {
        if (isEomMonthlySchedule(startDate, endDate, period)) {
            long months = period.toTotalMonths();
            for (long step = 1L; true; step++) {
                LocalDate temp = endDate.minusMonths(months * step).with(TemporalAdjusters.lastDayOfMonth());
                if (!temp.isAfter(startDate)) {
                    break;
                }
                dates.add(temp);
            }
            return;
        }

        LocalDate right = endDate;
        while (true) {
            LocalDate temp = right.minus(period);
            if (!temp.isAfter(startDate)) {
                break;
            }
            dates.add(temp);
            right = temp;
        }
    }

    private static boolean isEomMonthlySchedule(LocalDate startDate, LocalDate endDate, Period period) {
        return period.toTotalMonths() > 0
                && period.getDays() == 0
                && isLastDayOfMonth(startDate)
                && isLastDayOfMonth(endDate);
    }

    private static boolean isLastDayOfMonth(LocalDate date) {
        return date.equals(date.with(TemporalAdjusters.lastDayOfMonth()));
    }

    private static void mergeShortStub(LinkedList<LocalDate> dates, boolean forward) {
        if (dates.size() < 3) {
            return;
        }
        if (forward) {
            int last = dates.size() - 1;
            long stubDays = ChronoUnit.DAYS.between(dates.get(last - 1), dates.get(last));
            if (stubDays >= 0 && stubDays < SHORT_STUB_DAYS) {
                dates.remove(last - 1);
            }
        } else {
            long stubDays = ChronoUnit.DAYS.between(dates.get(0), dates.get(1));
            if (stubDays >= 0 && stubDays < SHORT_STUB_DAYS) {
                dates.remove(1);
            }
        }
    }

}
