package com.zcyh.mr.springboot.calendar.mapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 日历数据查询仓储。
 */
@Repository
public class CalendarMapper {

    private final JdbcTemplate engineDbJdbcTemplate;

    public CalendarMapper(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public List<Map<String, Object>> selectCalendar() {
        String sql = "SELECT CALENDAR_CODE, HOLIDAY_DATE FROM V_CALENDAR ORDER BY CALENDAR_CODE, HOLIDAY_DATE";
        return engineDbJdbcTemplate.queryForList(sql);
    }
}
