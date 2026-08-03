package com.zcyh.mr.scenario.util;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 时间工具类
 *
 * @author ruoyi
 */
public class DateUtils{
    /**
     * 日期型字符串转化为日期 格式
     */
    public static Date parseDate(String str) {
        if (str == null) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(str);
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }

    // 计算两个日期间工作日天数（给定假期列表）
    public static Long getWorkDays(Date startdate, Date endDate, List<Date> holDate){
        if (startdate != null && endDate != null){
            boolean flag = true;
            if (startdate.compareTo(endDate) > 0){
                Date temp = endDate;
                endDate = startdate;
                startdate = temp;
                flag = false;
            }
            Date finalStartdate = startdate;
            Date finalEndDate = endDate;
            long holDateSize = holDate.stream().filter(map -> {
                if (finalStartdate.compareTo(map) <= 0 && finalEndDate.compareTo(map) >= 0) {
                    return true;
                }
                return false;
            }).count();

            Calendar calendar = Calendar.getInstance();
            calendar.setTime(startdate);

            long allDateSize = 0L;
            while (startdate.compareTo(endDate) <= 0) {
                allDateSize ++;
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                startdate = calendar.getTime();
            }

            long result = allDateSize - holDateSize -1;
            if (flag){
                return result;
            } else {
                return -result;
            }
        }
        return null;
    }

}


