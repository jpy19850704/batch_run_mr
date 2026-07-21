package com.zcyh.mr.springboot.output.file;

import com.zcyh.mr.springboot.support.CsvRowWriter;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;

import java.io.BufferedWriter;
import java.io.IOException;

/**
 * 将结果行直接写入 CSV 文件。
 */
final class CsvResultFileWriter implements CsvRowWriter, AutoCloseable {
    private final BufferedWriter writer;
    private final String columnsHeader;
    private final char columnSeparator;
    private final char enclose;
    private final char escape;

    CsvResultFileWriter(BufferedWriter writer,
                        String columnsHeader,
                        char columnSeparator,
                        char enclose,
                        char escape) {
        this.writer = writer;
        this.columnsHeader = columnsHeader;
        this.columnSeparator = columnSeparator;
        this.enclose = enclose;
        this.escape = escape;
    }

    @Override
    public void appendRow(Object... values) {
        try {
            writer.write(DorisCsvStreamLoadBuffer.formatRow(
                    columnsHeader, columnSeparator, enclose, escape, values));
        } catch (IOException ex) {
            throw new IllegalStateException("写入结果CSV失败", ex);
        }
    }

    @Override
    public void flush() {
        try {
            writer.flush();
        } catch (IOException ex) {
            throw new IllegalStateException("刷新结果CSV失败", ex);
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
