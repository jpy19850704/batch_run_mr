package com.zcyh.mr.springboot.output.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MR_CALC 明细结果统一清理服务。
 */
@Service
public class MrCalcDetailCleanupService {
    private static final Logger log = LoggerFactory.getLogger(MrCalcDetailCleanupService.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_INTERVAL_MILLIS = 1000L;
    private static final int INSTRUMENT_CHUNK_SIZE = 500;
    private static final String[] TRANSIENT_ERROR_KEYWORDS = {
            "no queryable replicas",
            "not alive",
            "no backend available",
            "backend is down",
            "connection refused",
            "communications link failure"
    };
    private static final List<String> FULL_BATCH_TABLES = Collections.unmodifiableList(Arrays.asList(
            "TB_OUT_TRADE_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL",
            "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
            "TB_OUT_TRADE_DRC_DETAIL",
            "TB_OUT_MARKET_DATA_DETAIL",
            "TB_OUT_PORTFOLIO_HIERARCHY",
            "TB_OUT_SCENARIO_FILE_DETAIL",
            "TB_OUT_IMA_MODELLABLE_SCENARIO_PNL",
            "TB_OUT_IMA_NMRF_SCENARIO_PNL"
    ));
    private static final List<String> INSTRUMENT_TABLES = Collections.unmodifiableList(Arrays.asList(
            "TB_OUT_TRADE_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_RESULT_DETAIL",
            "TB_OUT_TRADE_SCENARIO_VAR_RESULT_DETAIL",
            "TB_OUT_TRADE_FRTB_SENSITIVITY_DETAIL",
            "TB_OUT_TRADE_DRC_DETAIL",
            "TB_OUT_IMA_MODELLABLE_SCENARIO_PNL",
            "TB_OUT_IMA_NMRF_SCENARIO_PNL"
    ));

    private final JdbcTemplate resultDbJdbcTemplate;

    public MrCalcDetailCleanupService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate resultDbJdbcTemplate) {
        this.resultDbJdbcTemplate = resultDbJdbcTemplate;
    }

    public void cleanupBatch(String batchId, LocalDate dataDate) {
        cleanupTables(batchId, dataDate, FULL_BATCH_TABLES);
    }

    public void cleanupBatchDetails(String batchId, LocalDate dataDate) {
        cleanupTables(batchId, dataDate, INSTRUMENT_TABLES);
    }

    public void cleanupMarketDataByBatchId(String batchId) {
        String safeBatchId = requireText(batchId, "batchId 不能为空");
        executeDelete(
                "TB_OUT_MARKET_DATA_DETAIL",
                "DELETE FROM TB_OUT_MARKET_DATA_DETAIL WHERE BATCH_ID=?",
                new Object[]{safeBatchId});
    }

    private void cleanupTables(String batchId, LocalDate dataDate, List<String> tableNames) {
        String safeBatchId = requireText(batchId, "batchId 不能为空");
        java.sql.Date resultDataDate = java.sql.Date.valueOf(dataDate);
        for (String tableName : tableNames) {
            executeDelete(
                    tableName,
                    "DELETE FROM " + tableName + " WHERE BATCH_ID=? AND DATA_DATE=?",
                    new Object[]{safeBatchId, resultDataDate});
        }
    }

    public void cleanupInstruments(String batchId,
                                   LocalDate dataDate,
                                   List<String> instrumentIds) {
        String safeBatchId = requireText(batchId, "batchId 不能为空");
        java.sql.Date resultDataDate = java.sql.Date.valueOf(dataDate);
        List<String> normalizedInstrumentIds = normalizeInstrumentIds(instrumentIds);
        if (normalizedInstrumentIds.isEmpty()) {
            throw new IllegalArgumentException("instrumentIds 不能为空");
        }
        for (int offset = 0; offset < normalizedInstrumentIds.size(); offset += INSTRUMENT_CHUNK_SIZE) {
            int end = Math.min(offset + INSTRUMENT_CHUNK_SIZE, normalizedInstrumentIds.size());
            List<String> chunk = normalizedInstrumentIds.subList(offset, end);
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            for (String tableName : INSTRUMENT_TABLES) {
                List<Object> args = new ArrayList<>();
                args.add(safeBatchId);
                args.add(resultDataDate);
                args.addAll(chunk);
                executeDelete(
                        tableName,
                        "DELETE FROM " + tableName
                                + " WHERE BATCH_ID=? AND DATA_DATE=? AND INSTRUMENT_ID IN (" + placeholders + ")",
                        args.toArray());
            }
        }
    }

    private void executeDelete(String tableName, String sql, Object[] args) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                resultDbJdbcTemplate.update(sql, args);
                return;
            } catch (DataAccessException ex) {
                if (!isTransientResultDbUnavailable(ex) || attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                long delayMillis = RETRY_INTERVAL_MILLIS * attempt;
                log.warn("Doris明细清理失败，准备重试，table={}, attempt={}, delayMillis={}, error={}",
                        tableName, attempt, delayMillis, rootMessage(ex));
                sleepBeforeRetry(delayMillis);
            }
        }
    }

    private static List<String> normalizeInstrumentIds(List<String> instrumentIds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (instrumentIds != null) {
            for (String instrumentId : instrumentIds) {
                String safeInstrumentId = trimToNull(instrumentId);
                if (safeInstrumentId != null) {
                    normalized.add(safeInstrumentId);
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private static boolean isTransientResultDbUnavailable(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                for (String keyword : TRANSIENT_ERROR_KEYWORDS) {
                    if (normalized.contains(keyword)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        Throwable root = ex;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        String message = root == null ? null : root.getMessage();
        return trimToNull(message) == null ? ex.getClass().getSimpleName() : message;
    }

    private static void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Doris明细清理重试被中断", ex);
        }
    }

    private static String formatDataDate(LocalDate dataDate) {
        if (dataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        return dataDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static String requireText(String value, String message) {
        String safeValue = trimToNull(value);
        if (safeValue == null) {
            throw new IllegalArgumentException(message);
        }
        return safeValue;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
