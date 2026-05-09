package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Doris Stream Load 服务。
 * 直接将内存中的批次文本推送到 Doris，不落地临时文件。
 */
@Service
public class DorisStreamLoadService {
    private static final Logger log = LoggerFactory.getLogger(DorisStreamLoadService.class);
    private static final DateTimeFormatter LABEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String COLUMN_SEPARATOR_HEADER = "\\x01";
    private static final char COLUMN_SEPARATOR_CHAR = '\u0001';
    private static final String ENCLOSE_HEADER = "\"";
    private static final char ENCLOSE_CHAR = '"';
    private static final String ESCAPE_HEADER = "\\";
    private static final char ESCAPE_CHAR = '\\';
    private static final String NULL_FORMAT_HEADER = "\\N";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String databaseName;
    private final String authHeader;
    private final Duration requestTimeout;

    public DorisStreamLoadService(
            @Value("${mr.doris.stream-load.base-url:http://127.0.0.1:8040}") String baseUrl,
            @Value("${mr.doris.stream-load.database:engine_result_db}") String databaseName,
            @Value("${mr.doris.stream-load.username:${ENGINE_RESULT_DB_USERNAME:root}}") String username,
            @Value("${mr.doris.stream-load.password:${ENGINE_RESULT_DB_PASSWORD:pwd123}}") String password,
            @Value("${mr.doris.stream-load.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${mr.doris.stream-load.read-timeout-ms:300000}") long readTimeoutMs) {
        this.baseUrl = trimTrailingSlash(requireText(baseUrl, "Doris Stream Load 地址不能为空"));
        this.databaseName = requireText(databaseName, "Doris Stream Load 数据库不能为空");
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((requireText(username, "Doris Stream Load 用户名不能为空")
                        + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1000L)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.requestTimeout = Duration.ofMillis(Math.max(readTimeoutMs, 1000L));
    }

    /**
     * 以 CSV 文本方式执行一次 Stream Load。
     */
    public JSONObject loadCsv(String tableName, String columnsHeader, byte[] payload, String labelPrefix) {
        String safeTableName = requireText(tableName, "Doris Stream Load 表名不能为空");
        String safeColumnsHeader = requireText(columnsHeader, "Doris Stream Load 列定义不能为空");
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Doris Stream Load 内容不能为空");
        }

        String label = buildLabel(labelPrefix, safeTableName);
        String endpoint = String.format(Locale.ROOT, "%s/api/%s/%s/_stream_load", baseUrl, databaseName, safeTableName);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(requestTimeout)
                .expectContinue(true)
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .header("format", "csv")
                .header("column_separator", COLUMN_SEPARATOR_HEADER)
                .header("enclose", ENCLOSE_HEADER)
                .header("escape", ESCAPE_HEADER)
                .header("null_format", NULL_FORMAT_HEADER)
                .header("columns", safeColumnsHeader)
                .header("label", label)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Doris Stream Load HTTP 状态异常: " + response.statusCode() + ", body=" + response.body());
            }
            JSONObject result = JSON.parseObject(response.body());
            String status = result == null ? null : result.getString("Status");
            if (!isSuccessStatus(status)) {
                throw new IllegalStateException("Doris Stream Load 失败, label=" + label + ", body=" + response.body());
            }
            log.info("Doris Stream Load 成功, table={}, label={}, rows={}, loadedRows={}, loadTimeMs={}",
                    safeTableName,
                    label,
                    result.getString("NumberTotalRows"),
                    result.getString("NumberLoadedRows"),
                    result.getString("LoadTimeMs"));
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("Doris Stream Load 调用失败, table=" + safeTableName + ", label=" + label, ex);
        }
    }

    private static boolean isSuccessStatus(String status) {
        if (status == null) {
            return false;
        }
        return "Success".equalsIgnoreCase(status) || "Publish Timeout".equalsIgnoreCase(status);
    }

    /**
     * 返回 CSV 列分隔符。
     */
    public char getColumnSeparatorChar() {
        return COLUMN_SEPARATOR_CHAR;
    }

    /**
     * 返回 CSV 包围符。
     */
    public char getEncloseChar() {
        return ENCLOSE_CHAR;
    }

    /**
     * 返回 CSV 转义符。
     */
    public char getEscapeChar() {
        return ESCAPE_CHAR;
    }

    private static String buildLabel(String labelPrefix, String tableName) {
        String prefix = trimToNull(labelPrefix);
        if (prefix == null) {
            prefix = tableName;
        }
        return prefix + "_" + LABEL_TIME_FORMATTER.format(LocalDateTime.now()) + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String requireText(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
