package com.zcyh.mr.springboot.input.common;

public class ExcelTemplateFile {
    private final String fileName;
    private final byte[] content;

    public ExcelTemplateFile(String fileName, byte[] content) {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public byte[] getContent() {
        return content;
    }
}
