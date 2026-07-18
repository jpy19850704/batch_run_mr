package com.zcyh.mr.springboot.support;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 结果库日期与接口日期的唯一转换入口。
 */
public final class ResultDbDateSupport {
    private static final DateTimeFormatter PROTOCOL_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private ResultDbDateSupport() {
    }

    public static Date sqlDate(String value) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("数据日期不能为空");
        }
        try {
            return Date.valueOf(LocalDate.parse(text, PROTOCOL_DATE_FORMATTER));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("数据日期格式必须为yyyyMMdd: " + text, ex);
        }
    }

    public static String protocolDate(LocalDate value) {
        return value == null ? null : value.format(PROTOCOL_DATE_FORMATTER);
    }
}
