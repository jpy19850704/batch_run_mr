package com.zcyh.mr.springboot.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 关键告警服务。
 */
@Service
public class AlertService {
    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final boolean enabled;
    private final long suppressWindowMs;
    private final ConcurrentMap<String, Long> lastAlertTimeMap = new ConcurrentHashMap<String, Long>();

    public AlertService(
            @Value("${mr.alert.enabled:true}") boolean enabled,
            @Value("${mr.alert.suppress-window-ms:60000}") long suppressWindowMs
    ) {
        this.enabled = enabled;
        this.suppressWindowMs = Math.max(0L, suppressWindowMs);
    }

    public void warn(String code, String message) {
        emit("WARN", code, message, null);
    }

    public void error(String code, String message, Throwable ex) {
        emit("ERROR", code, message, ex);
    }

    private void emit(String level, String code, String message, Throwable ex) {
        if (!enabled) {
            return;
        }
        String safeCode = trimToDefault(code, "UNKNOWN_ALERT");
        String key = level + "|" + safeCode + "|" + trimToDefault(message, "");
        long now = System.currentTimeMillis();
        Long previous = lastAlertTimeMap.put(key, now);
        if (previous != null && suppressWindowMs > 0L && now - previous.longValue() < suppressWindowMs) {
            return;
        }
        if ("ERROR".equals(level)) {
            log.error("告警触发 code={}, message={}", safeCode, message, ex);
            return;
        }
        log.warn("告警触发 code={}, message={}", safeCode, message);
    }

    private static String trimToDefault(String txt, String defaultValue) {
        if (txt == null) {
            return defaultValue;
        }
        String value = txt.trim();
        return value.isEmpty() ? defaultValue : value;
    }
}
