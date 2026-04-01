package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.springboot.calendar.mapper.CalendarMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 批次启动前日历文件刷新服务。
 */
@Service
public class CalendarFileBootstrapService {
    private static final Logger log = LoggerFactory.getLogger(CalendarFileBootstrapService.class);

    private final CalendarMapper calendarMapper;
    private final Calendar holidayCalendar;
    private final String calendarStorePath;

    public CalendarFileBootstrapService(
            CalendarMapper calendarMapper,
            @Qualifier("mrHolidayCalendar") Calendar holidayCalendar,
            @Value("${mr.calendar.store.path:}") String calendarStorePath) {
        this.calendarMapper = calendarMapper;
        this.holidayCalendar = holidayCalendar;
        this.calendarStorePath = trimToNull(calendarStorePath);
    }

    public void refreshForBatch(String batchId) {
        String storePath = resolveStorePath();
        if (storePath == null) {
            throw new IllegalStateException("未配置日历文件目录 mr.calendar.store.path");
        }

        List<Map<String, Object>> rows;
        try {
            rows = calendarMapper.selectCalendar();
        } catch (Exception ex) {
            // 日历查询失败时降级继续：不生成文件、不刷新缓存，避免阻断批次流程。
            log.warn("批次启动日历刷新降级，查询 V_CALENDAR 失败，跳过本次刷新: batchId={}, error={}", batchId, ex.getMessage());
            return;
        }
        if (rows == null || rows.isEmpty()) {
            log.warn("批次启动日历刷新跳过，V_CALENDAR 无数据: batchId={}", batchId);
            return;
        }

        Map<String, SortedSet<LocalDate>> grouped = new TreeMap<String, SortedSet<LocalDate>>();
        int invalidRowCount = 0;
        for (Map<String, Object> row : rows) {
            String calendarCode = trimToNull(readValueAsString(row, "CALENDAR_CODE"));
            LocalDate holidayDate = readValueAsDate(row, "HOLIDAY_DATE");
            if (calendarCode == null || holidayDate == null) {
                invalidRowCount++;
                continue;
            }
            if (!grouped.containsKey(calendarCode)) {
                grouped.put(calendarCode, new TreeSet<LocalDate>());
            }
            grouped.get(calendarCode).add(holidayDate);
        }

        if (grouped.isEmpty()) {
            // 日历记录全部无效时降级继续：不生成文件、不刷新缓存，避免阻断批次流程。
            log.warn("批次启动日历刷新降级，V_CALENDAR 无有效记录，跳过本次刷新: batchId={}, rowCount={}, invalidRowCount={}",
                    batchId, rows.size(), invalidRowCount);
            return;
        }

        Path rootPath = Paths.get(storePath);
        try {
            Files.createDirectories(rootPath);
            for (Map.Entry<String, SortedSet<LocalDate>> entry : grouped.entrySet()) {
                writeCalendarFile(rootPath, entry.getKey(), entry.getValue());
            }
            holidayCalendar.loadFromPath(storePath);
        } catch (IOException ex) {
            throw new IllegalStateException("写入日历文件失败: " + ex.getMessage(), ex);
        }

        log.info("批次启动日历刷新完成: batchId={}, calendarCount={}, rowCount={}, invalidRowCount={}, storePath={}",
                batchId, grouped.size(), rows.size(), invalidRowCount, storePath);
    }

    private void writeCalendarFile(Path rootPath, String calendarCode, SortedSet<LocalDate> holidays) throws IOException {
        JSONObject root = new JSONObject();
        root.put("calendarCode", calendarCode);
        root.put("description", "由V_CALENDAR生成");
        root.put("version", LocalDate.now().toString());

        JSONArray holidayArray = new JSONArray();
        for (LocalDate holiday : holidays) {
            holidayArray.add(holiday.toString());
        }
        root.put("holidays", holidayArray);

        Path filePath = rootPath.resolve(calendarCode + ".json");
        String content = JSON.toJSONString(root, JSONWriter.Feature.PrettyFormat);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
    }

    private String resolveStorePath() {
        String configured = trimToNull(calendarStorePath);
        if (configured != null) {
            return configured;
        }
        return trimToNull(holidayCalendar.getStorePath());
    }

    private static LocalDate readValueAsDate(Map<String, Object> row, String key) {
        Object value = readValue(row, key);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return (LocalDate) value;
        }
        if (value instanceof java.sql.Date) {
            return ((java.sql.Date) value).toLocalDate();
        }
        if (value instanceof Date) {
            Date date = (Date) value;
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String text = trimToNull(String.valueOf(value));
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static String readValueAsString(Map<String, Object> row, String key) {
        Object value = readValue(row, key);
        return value == null ? null : String.valueOf(value);
    }

    private static Object readValue(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return null;
        }
        Object value = row.get(key);
        if (value != null) {
            return value;
        }
        value = row.get(key.toLowerCase());
        if (value != null) {
            return value;
        }
        return row.get(key.toUpperCase());
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
