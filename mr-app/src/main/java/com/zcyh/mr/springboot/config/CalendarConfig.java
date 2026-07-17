package com.zcyh.mr.springboot.config;

import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.calendar.SystemCalendarCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 日历组件配置。
 */
@Configuration
public class CalendarConfig {

    @Bean(name = "mrHolidayCalendar")
    public Calendar mrHolidayCalendar(
            @Value("${mr.calendar.store.path:}") String calendarStorePath) {
        Calendar calendar = new Calendar();
        calendar.loadFromPath(calendarStorePath);
        SystemCalendarCache.setCalendar(calendar);
        return calendar;
    }
}
