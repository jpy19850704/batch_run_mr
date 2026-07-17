package com.zcyh.mr.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class Convert {
    private static final Logger log = LoggerFactory.getLogger(Convert.class);
    public static Date toDate(Object date) {
        if (date instanceof Date)
            return (Date) date;
        else if (date instanceof String) {
            Date date1 = null;
            try {
                date1 = new SimpleDateFormat("yyyy-MM-dd").parse((String) date);
            } catch (ParseException e) {
                log.warn("日期解析失败: value={}", date, e);
            }
            return date1;
        }
        return null;
    }

    public static String toStr(Object value) {
        if (null == value)
            return null;
        if (value instanceof String)
            return (String) value;
        return value.toString();
    }

    public static String toStr(Object value, String defaultValue) {
        if (null == value)
            return defaultValue;
        if (value instanceof String)
            return (String) value;
        return value.toString();
    }

    public static Double toDouble(Object value) {
        if (value instanceof Double)
            return (Double) value;
        if (value instanceof String)
            return Double.parseDouble(toStr(value));
        else if (value instanceof BigDecimal)
            return ((BigDecimal) value).doubleValue();
        else if (value instanceof Number)
            return ((Number) value).doubleValue();
        return Double.NaN;
    }

    public static Integer toInt(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        final String valueStr = toStr(value);
        return Integer.parseInt(valueStr.trim());

    }

    /**
     * 深拷贝（委托给CommUtils.deepCopy）
     */
    public static <T> List<T> deepCopy(List<T> src) {
        return CommUtils.deepCopy(src);
    }

    public static boolean isTrue(Object value) {
        if (null == value)
            return false;
        if (value instanceof Boolean)
            return (boolean) value;
        if (value instanceof String)
            return "TRUE".equalsIgnoreCase(String.valueOf(value)) || "T".equalsIgnoreCase(String.valueOf(value));
        if (value instanceof Integer)
            return 1 == toInt(value);
        return false;
    }
}
