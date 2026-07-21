package com.zcyh.mr.springboot.output.file;

import com.zcyh.mr.springboot.support.CsvRowWriter;
import com.zcyh.mr.springboot.support.CsvRowWriterFactory;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 为单个计量分片创建结果 CSV 文件。
 */
public final class FileCsvRowWriterFactory implements CsvRowWriterFactory, AutoCloseable {
    private final Path directory;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final Map<String, CsvResultFileWriter> writers = new LinkedHashMap<String, CsvResultFileWriter>();
    private final Map<String, String> columnsByTable = new LinkedHashMap<String, String>();

    public FileCsvRowWriterFactory(Path directory, DorisStreamLoadService dorisStreamLoadService) {
        this.directory = directory;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @Override
    public CsvRowWriter create(String tableName, String columnsHeader, String labelPrefix, int batchSize) {
        CsvResultFileWriter existing = writers.get(tableName);
        if (existing != null) {
            if (!columnsHeader.equals(columnsByTable.get(tableName))) {
                throw new IllegalStateException("同一结果表使用了不同列定义: " + tableName);
            }
            return existing;
        }
        try {
            Files.createDirectories(directory);
            BufferedWriter bufferedWriter = Files.newBufferedWriter(
                    directory.resolve(fileName(tableName)), StandardCharsets.UTF_8);
            CsvResultFileWriter created = new CsvResultFileWriter(
                    bufferedWriter,
                    columnsHeader,
                    dorisStreamLoadService.getColumnSeparatorChar(),
                    dorisStreamLoadService.getEncloseChar(),
                    dorisStreamLoadService.getEscapeChar());
            writers.put(tableName, created);
            columnsByTable.put(tableName, columnsHeader);
            return created;
        } catch (IOException ex) {
            throw new IllegalStateException("创建结果CSV失败: table=" + tableName + ", directory=" + directory, ex);
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (CsvResultFileWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (IOException ex) {
                failure = ex;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static String fileName(String tableName) {
        return tableName.toLowerCase(java.util.Locale.ROOT) + ".csv";
    }
}
