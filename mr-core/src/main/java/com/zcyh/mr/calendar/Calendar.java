package com.zcyh.mr.calendar;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

/**
 * 日历类: 单例模式-双重校验
 *
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class Calendar {
    private static final Logger log = LoggerFactory.getLogger(Calendar.class);

    private final HashMap<String, Set<LocalDate>> addedHolidays = new HashMap<String, Set<LocalDate>>();
    private final HashMap<String, CalendarFileRecord> fileRecords = new HashMap<String, CalendarFileRecord>();
    private String storePath;

    public Calendar() {

    }

    /**
     * 设置日历文件目录。
     */
    public synchronized void setStorePath(String storePath) {
        this.storePath = StringUtils.trimToNull(storePath);
    }

    /**
     * 获取日历文件目录。
     */
    public synchronized String getStorePath() {
        return storePath;
    }

    /**
     * 从指定目录加载全部日历文件。
     */
    public synchronized void loadFromPath(String storePath) {
        setStorePath(storePath);
        reloadAll();
    }

    /**
     * 重新加载目录下全部日历文件。
     */
    public synchronized void reloadAll() {
        if (StringUtils.isBlank(storePath)) {
            return;
        }

        Path root = Paths.get(storePath);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            log.warn("日历目录不存在，按全工作日处理: {}", root);
            clearFileBackedCalendars();
            return;
        }

        HashMap<String, CalendarFileRecord> loadedRecords = new HashMap<String, CalendarFileRecord>();
        try {
            List<Path> jsonFiles = new ArrayList<Path>();
            Files.list(root)
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .forEach(jsonFiles::add);

            for (Path jsonFile : jsonFiles) {
                CalendarFileRecord record = parseCalendarFile(jsonFile);
                if (record == null || StringUtils.isBlank(record.calendarCode)) {
                    continue;
                }
                loadedRecords.put(record.calendarCode, record);
            }

            replaceFileBackedCalendars(loadedRecords);
        } catch (IOException ex) {
            log.warn("加载日历目录失败，保留现有缓存: {}", ex.getMessage());
        }
    }

    public synchronized void clear(String calName) {
        Set<LocalDate> holidays = addedHolidays.get(calName);
        if (holidays != null) {
            holidays.clear();
        }
        fileRecords.remove(calName);
    }

    public synchronized void clear() {
        addedHolidays.clear();
        fileRecords.clear();
    }

    public synchronized void addHoliday(String calName, LocalDate date) {
        if (addedHolidays.get(calName) == null) {
            Set<LocalDate> holidays = new TreeSet<>();
            holidays.add(date);
            addedHolidays.put(calName, holidays);
        } else {
            addedHolidays.get(calName).add(date);
        }
    }

    public synchronized void addHolidays(String calName, Collection<LocalDate> dates) {
        if (addedHolidays.get(calName) == null) {
            Set<LocalDate> holidays = new TreeSet<>();
            holidays.addAll(dates);
            addedHolidays.put(calName, holidays);
        } else {
            addedHolidays.get(calName).addAll(dates);
        }
    }

    public synchronized void removeHolidays(String calName, Collection<LocalDate> dates) {
        if (addedHolidays.get(calName) == null) {
            return;
        } else {
            addedHolidays.get(calName).removeAll(dates);
        }
    }

    public synchronized Set<LocalDate> getHolidays(String calName) {
        if (StringUtils.isBlank(calName) || !addedHolidays.containsKey(calName)) {
            return new TreeSet<LocalDate>();
        }
        Set<LocalDate> holidays = addedHolidays.get(calName);
        return holidays == null ? new TreeSet<LocalDate>() : new TreeSet<LocalDate>(holidays);
    }

    public boolean isBusinessDay(String calName, final LocalDate d) {
        // 日历为空或未配置时使用全工作日口径，这是引擎默认日历策略，不属于字段兼容回退。
        if (StringUtils.isBlank(calName) || !addedHolidays.containsKey(calName)) {
            return true;
        }
        Set<LocalDate> holidays = addedHolidays.get(calName);
        if (holidays != null && holidays.contains(d)) {
            return false;
        } /*
           * else if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() ==
           * DayOfWeek.SUNDAY) {
           * return false;
           * }
           */else {
            return true;
        }

    }

    /**
     * 通过工作日生成规则，获取给定日期对应的工作日
     *
     * @param refDate: 输入的参考日期
     * @param rule:    日期生成规则
     * @param dayoff:  dayoff天数
     * @return LocalDate
     * @author lsd
     * @date 2024/7/10 15:35
     */
    public LocalDate getBusinessDay(String calName, LocalDate refDate, String rule, Integer dayoff) {
        if (StringUtils.isBlank(rule))
            return refDate;

        LocalDate refDateOri = LocalDate.from(refDate);
        switch (rule) {
            case "P":
                if (dayoff == 0) {
                    while (!isBusinessDay(calName, refDate)) {
                        refDate = refDate.minusDays(1);
                    }
                } else {
                    while (dayoff > 0) {
                        refDate = refDate.minusDays(1);
                        while (!isBusinessDay(calName, refDate)) {
                            refDate = refDate.minusDays(1);
                        }
                        dayoff--;
                    }
                }
                break;
            case "MP":
                if (dayoff == 0) {
                    while (!isBusinessDay(calName, refDate)) {
                        refDate = refDate.minusDays(1);
                    }
                } else {
                    while (dayoff > 0) {
                        refDate = refDate.minusDays(1);
                        while (!isBusinessDay(calName, refDate)) {
                            refDate = refDate.minusDays(1);
                        }
                        dayoff--;
                    }
                }

                if (refDateOri.getMonthValue() != refDate.getMonthValue()) {
                    refDate = getFirstBusinessDayOfMonth(calName, refDateOri);
                }
                break;
            case "F":
                if (dayoff == 0) {
                    while (!isBusinessDay(calName, refDate)) {
                        refDate = refDate.plusDays(1);
                    }
                } else {
                    while (dayoff > 0) {
                        refDate = refDate.plusDays(1);
                        while (!isBusinessDay(calName, refDate)) {
                            refDate = refDate.plusDays(1);
                        }
                        dayoff--;
                    }
                }
                break;
            case "MF":
                if (dayoff == 0) {
                    while (!isBusinessDay(calName, refDate)) {
                        refDate = refDate.plusDays(1);
                    }
                } else {
                    while (dayoff > 0) {
                        refDate = refDate.plusDays(1);
                        while (!isBusinessDay(calName, refDate)) {
                            refDate = refDate.plusDays(1);
                        }
                        dayoff--;
                    }
                }
                if (refDateOri.getMonthValue() != refDate.getMonthValue()) {
                    refDate = getLastBusinessDayOfMonth(calName, refDateOri);
                }
                break;
        }
        return refDate;
    }

    public LocalDate getFirstBusinessDayOfMonth(String calName, LocalDate refDate) {
        LocalDate firstDay = refDate.with(TemporalAdjusters.firstDayOfMonth());
        while (!isBusinessDay(calName, firstDay)) {
            firstDay = firstDay.plusDays(1);
        }
        return firstDay;
    }

    public LocalDate getLastBusinessDayOfMonth(String calName, LocalDate refDate) {
        LocalDate lastDay = refDate.with(TemporalAdjusters.lastDayOfMonth());
        while (!isBusinessDay(calName, lastDay)) {
            lastDay = lastDay.minusDays(1);
        }
        return lastDay;
    }

    public static LocalDate dateAdd(LocalDate refDate, int addNum, String unit) {
        LocalDate newDate = null;
        if ("years".equals(unit)) {
            newDate = refDate.plusYears(addNum);
        } else if ("months".equals(unit)) {
            newDate = refDate.plusMonths(addNum);
        } else if ("weeks".equals(unit)) {
            newDate = refDate.plusWeeks(addNum);
        } else if ("days".equals(unit)) {
            newDate = refDate.plusDays(addNum);
        }
        return newDate;
    }

    /**
     * 从指定日期向前推进N个工作日
     *
     * @param calName: 日历名称
     * @param refDate: 起始日期
     * @param days:    工作日天数
     * @return LocalDate
     */
    public LocalDate addBusinessDays(String calName, LocalDate refDate, int days) {
        return getBusinessDay(calName, refDate, "F", days);
    }

    /**
     * 根据期限代码计算到期日期，并调整到工作日
     * 支持 D(天)/W(周)/M(月)/Y(年) 格式，如 "1M"、"3Y"、"7D"
     *
     * @param calName:   日历名称，为空则不做工作日调整
     * @param startDate: 起始日期
     * @param termCode:  期限代码，如 "1M"、"2Y"、"30D"
     * @return LocalDate 调整后的到期日期
     */
    public LocalDate resolveTermDate(String calName, LocalDate startDate, String termCode) {
        if (termCode == null || termCode.length() < 2) {
            return startDate;
        }

        String mark = termCode.substring(termCode.length() - 1).toUpperCase();
        int amount;
        try {
            amount = Integer.parseInt(termCode.substring(0, termCode.length() - 1));
        } catch (NumberFormatException e) {
            return startDate;
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
                return startDate;
        }

        LocalDate endDate = dateAdd(startDate, amount, unit);

        // 通过 getBusinessDay 做工作日调整，默认 Following 规则
        if (calName != null && !calName.isEmpty()) {
            endDate = getBusinessDay(calName, endDate, "F", 0);
        }
        return endDate;
    }

    private void replaceFileBackedCalendars(HashMap<String, CalendarFileRecord> loadedRecords) {
        clearFileBackedCalendars();
        fileRecords.clear();
        for (CalendarFileRecord record : loadedRecords.values()) {
            fileRecords.put(record.calendarCode, record);
            replaceCalendarCache(record.calendarCode, record.holidays);
        }
    }

    private void clearFileBackedCalendars() {
        Set<String> previousKeys = new HashSet<String>(fileRecords.keySet());
        for (String key : previousKeys) {
            addedHolidays.remove(key);
        }
    }

    private void replaceCalendarCache(String calName, Collection<LocalDate> dates) {
        Set<LocalDate> holidays = new TreeSet<LocalDate>();
        if (dates != null) {
            holidays.addAll(dates);
        }
        addedHolidays.put(calName, holidays);
    }

    private CalendarFileRecord parseCalendarFile(Path jsonFile) {
        try {
            String content = Files.readString(jsonFile, StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(content);
            if (root == null) {
                log.warn("日历文件解析为空，跳过: {}", jsonFile);
                return null;
            }

            String calendarCode = StringUtils.trimToNull(root.getString("calendarCode"));
            if (calendarCode == null) {
                String fileName = jsonFile.getFileName().toString();
                if (fileName.toLowerCase().endsWith(".json")) {
                    calendarCode = fileName.substring(0, fileName.length() - 5);
                }
            }
            if (calendarCode == null) {
                log.warn("日历文件缺少 calendarCode，跳过: {}", jsonFile);
                return null;
            }

            Set<LocalDate> holidays = new TreeSet<LocalDate>();
            JSONArray holidayArray = root.getJSONArray("holidays");
            if (holidayArray != null) {
                for (int i = 0; i < holidayArray.size(); i++) {
                    String text = StringUtils.trimToNull(holidayArray.getString(i));
                    if (text == null) {
                        continue;
                    }
                    try {
                        holidays.add(LocalDate.parse(text));
                    } catch (Exception ex) {
                        log.warn("忽略非法节假日日期: file={}, value={}", jsonFile, text);
                    }
                }
            }

            CalendarFileRecord record = new CalendarFileRecord();
            record.calendarCode = calendarCode;
            record.description = root.getString("description");
            record.version = root.getString("version");
            record.holidays = holidays;
            record.sourceFile = jsonFile;
            record.lastModified = Files.getLastModifiedTime(jsonFile).toMillis();
            return record;
        } catch (Exception ex) {
            log.warn("解析日历文件失败，跳过: {}, {}", jsonFile, ex.getMessage());
            return null;
        }
    }

    /**
     * 文件日历记录。
     */
    private static class CalendarFileRecord {
        private String calendarCode;
        private String description;
        private String version;
        private Set<LocalDate> holidays = new TreeSet<LocalDate>();
        private Path sourceFile;
        private long lastModified;
    }
}
