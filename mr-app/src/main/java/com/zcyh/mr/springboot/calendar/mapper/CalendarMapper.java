package com.zcyh.mr.springboot.calendar.mapper;

import java.util.List;
import java.util.Map;

/**
 * 日历数据访问接口。
 */
public interface CalendarMapper {

    List<Map<String, Object>> selectCalendar();
}
