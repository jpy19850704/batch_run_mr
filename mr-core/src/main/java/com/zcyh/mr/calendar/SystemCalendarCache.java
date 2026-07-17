package com.zcyh.mr.calendar;

/**
 * 系统级日历缓存。
 */
public final class SystemCalendarCache {
    private static volatile Calendar holidayCalendar = new Calendar();

    private SystemCalendarCache() {
    }

    public static Calendar getCalendar() {
        return holidayCalendar;
    }

    public static void setCalendar(Calendar calendar) {
        holidayCalendar = calendar == null ? new Calendar() : calendar;
    }

    public static Calendar resolve(Calendar calendar) {
        return calendar == null ? holidayCalendar : calendar;
    }
}
