package com.zcyh.mr.springboot.support;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 结果库日期与接口日期的唯一转换入口。
 */
public final class ResultDbDateSupport {
    private static final DateTimeFormatter PROTOCOL_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private ResultDbDateSupport() {
    }

    public static LocalDate localDate(String value) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("数据日期不能为空");
        }
        try {
            return LocalDate.parse(text, PROTOCOL_DATE_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("数据日期格式必须为yyyy-MM-dd: " + text, ex);
        }
    }

    public static Date sqlDate(String value) {
        return sqlDate(localDate(value));
    }

    public static Date sqlDate(LocalDate value) {
        if (value == null) {
            throw new IllegalArgumentException("数据日期不能为空");
        }
        return Date.valueOf(value);
    }

    public static String protocolDate(LocalDate value) {
        return value == null ? null : value.format(PROTOCOL_DATE_FORMATTER);
    }
}
