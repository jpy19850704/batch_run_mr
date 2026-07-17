package com.zcyh.mr.springboot.batch.model;

/**
 * 异步任务状态枚举。
 */
public enum JobStatus {
    PENDING(false),
    RUNNING(false),
    SUCCESS(true),
    FAILED(true),
    PARTIAL_FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    JobStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public static JobStatus parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("异步任务状态不能为空");
        }
        try {
            return JobStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("未知异步任务状态: " + value, ex);
        }
    }
}
