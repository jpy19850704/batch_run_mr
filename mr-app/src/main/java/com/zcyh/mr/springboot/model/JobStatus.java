package com.zcyh.mr.springboot.model;

/**
 * 异步任务状态枚举。
 */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

