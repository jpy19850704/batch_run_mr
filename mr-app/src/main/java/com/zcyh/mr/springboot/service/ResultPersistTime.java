package com.zcyh.mr.springboot.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 结果库落库时间格式工具。
 */
final class ResultPersistTime {
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    private ResultPersistTime() {
    }

    static String nowText() {
        return LocalDateTime.now().format(DISPLAY_FORMATTER);
    }

    static String formatEpochMillis(long epochMillis) {
        if (epochMillis <= 0) {
            return nowText();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZONE_ID).format(DISPLAY_FORMATTER);
    }
}
