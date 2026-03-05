package com.zcyh.mr.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;

class BondFuncTest {

    @Test
    public void test() {
        LocalDate start = LocalDate.of(2024, 1, 30);
        LocalDate end = LocalDate.of(2021, 12, 1);

        int n = CurveFunc.daysBetweenDCB(start, end, "30/360");
        System.out.println(n);
        double tf = CurveFunc.timeFactor(start, end, "30/360");
        System.out.println(tf);

        LocalDate a = start.plusMonths(1);
        System.out.println(a);

    }

    @Test
    public void testCal() {
        Calendar cal = new Calendar();
        String[] dateListStr = {"20211231", "20220101", "20220102"};
        Set<LocalDate> dates = new TreeSet<>();
        for (String dateStr : dateListStr) {
            dates.add(LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd")));
        }

        cal.addHolidays("PEK", dates);

        LocalDate newDate = cal.getBusinessDay("PEK", LocalDate.of(2021, 12, 30), "A", 3);
        System.out.println(newDate);

    }

    @Test
    public void testBinSearch() {
        int[] num = {1, 7, 14, 31, 59, 181, 273, 365, 546, 730, 912, 1096, 1277, 1461, 1642, 1826, 2191, 2557, 2922, 3287, 3652, 5479, 7305, 9131, 10957};

    }

    @Test
    public void testCashflows() {
        Period rst = CashflowUtils.convertFreq("6M");
        System.out.println(rst);

    }
}