package com.zcyh.mr.springboot.support;

/**
 * CSV 行输出器。
 */
public interface CsvRowWriter {
    void appendRow(Object... values);

    void flush();
}
