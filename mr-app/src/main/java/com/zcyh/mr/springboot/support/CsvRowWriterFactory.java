package com.zcyh.mr.springboot.support;

/**
 * 按结果表创建 CSV 行输出器。
 */
public interface CsvRowWriterFactory {
    CsvRowWriter create(String tableName, String columnsHeader, String labelPrefix, int batchSize);
}
