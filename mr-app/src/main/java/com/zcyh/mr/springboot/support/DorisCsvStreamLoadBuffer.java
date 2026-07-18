package com.zcyh.mr.springboot.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Doris CSV Stream Load 批次缓冲器。
 * 用于在内存中按固定行数聚合 CSV 内容，再统一推送到 Doris。
 */
public class DorisCsvStreamLoadBuffer {
    private static final DateTimeFormatter PROTOCOL_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<String> DATE_COLUMNS = Set.of(
            "DATA_DATE", "START_DATE", "END_DATE", "EXCEPTION_DATE", "OPTION_EXPIRY");

    private final DorisStreamLoadService dorisStreamLoadService;
    private final String tableName;
    private final String columnsHeader;
    private final String labelPrefix;
    private final int batchSize;
    private final StringBuilder csvBuilder;
    private final char columnSeparatorChar;
    private final char encloseChar;
    private final char escapeChar;
    private final String[] columns;
    private int currentBatchCount;
    private int chunkNo;

    public DorisCsvStreamLoadBuffer(DorisStreamLoadService dorisStreamLoadService,
                                    String tableName,
                                    String columnsHeader,
                                    String labelPrefix,
                                    int batchSize) {
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.tableName = tableName;
        this.columnsHeader = columnsHeader;
        this.labelPrefix = labelPrefix;
        this.batchSize = batchSize <= 0 ? 20000 : batchSize;
        this.csvBuilder = new StringBuilder(Math.max(1024, this.batchSize * 128));
        this.columnSeparatorChar = dorisStreamLoadService.getColumnSeparatorChar();
        this.encloseChar = dorisStreamLoadService.getEncloseChar();
        this.escapeChar = dorisStreamLoadService.getEscapeChar();
        this.columns = parseColumns(columnsHeader);
    }

    /**
     * 追加一行 CSV 数据。
     */
    public void appendRow(Object... values) {
        if (values != null && values.length != columns.length) {
            throw new IllegalArgumentException("Doris列数与数据值数量不一致: table=" + tableName
                    + ", columns=" + columns.length + ", values=" + values.length);
        }
        appendCsvRow(csvBuilder, columnSeparatorChar, encloseChar, escapeChar, columns, values);
        currentBatchCount++;
        if (currentBatchCount >= batchSize) {
            flush();
        }
    }

    /**
     * 刷出当前批次。
     */
    public void flush() {
        if (csvBuilder.length() == 0) {
            return;
        }
        chunkNo++;
        dorisStreamLoadService.loadCsv(
                tableName,
                columnsHeader,
                csvBuilder.toString().getBytes(StandardCharsets.UTF_8),
                labelPrefix + "_chunk" + chunkNo);
        csvBuilder.setLength(0);
        currentBatchCount = 0;
    }

    public static String decimalText(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static void appendCsvRow(StringBuilder csvBuilder,
                                     char columnSeparatorChar,
                                     char encloseChar,
                                     char escapeChar,
                                     String[] columns,
                                     Object... values) {
        if (values == null) {
            csvBuilder.append('\n');
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csvBuilder.append(columnSeparatorChar);
            }
            appendCsvCell(csvBuilder, columnSeparatorChar, encloseChar, escapeChar,
                    normalizeColumnValue(columns[i], values[i]));
        }
        csvBuilder.append('\n');
    }

    private static String[] parseColumns(String columnsHeader) {
        String[] rawColumns = columnsHeader.split(",");
        String[] result = new String[rawColumns.length];
        for (int i = 0; i < rawColumns.length; i++) {
            result[i] = rawColumns[i].trim().toUpperCase(Locale.ROOT);
        }
        return result;
    }

    private static Object normalizeColumnValue(String column, Object value) {
        if (value == null || !DATE_COLUMNS.contains(column)) {
            return value;
        }
        if (value instanceof LocalDate) {
            return value.toString();
        }
        if (value instanceof Date) {
            return ((Date) value).toLocalDate().toString();
        }
        String text = String.valueOf(value).trim();
        try {
            return LocalDate.parse(text, PROTOCOL_DATE_FORMATTER).toString();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(column + "格式必须为yyyyMMdd: " + text, ex);
        }
    }

    private static void appendCsvCell(StringBuilder csvBuilder,
                                      char columnSeparatorChar,
                                      char encloseChar,
                                      char escapeChar,
                                      Object value) {
        if (value == null) {
            csvBuilder.append("\\N");
            return;
        }
        String text = String.valueOf(value);
        boolean needQuote = text.indexOf(columnSeparatorChar) >= 0
                || text.indexOf(encloseChar) >= 0
                || text.indexOf(escapeChar) >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        if (!needQuote) {
            csvBuilder.append(text);
            return;
        }
        csvBuilder.append(encloseChar);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                csvBuilder.append(escapeChar).append('n');
                continue;
            }
            if (ch == '\r') {
                csvBuilder.append(escapeChar).append('r');
                continue;
            }
            if (ch == encloseChar || ch == escapeChar) {
                csvBuilder.append(escapeChar);
            }
            csvBuilder.append(ch);
        }
        csvBuilder.append(encloseChar);
    }
}
