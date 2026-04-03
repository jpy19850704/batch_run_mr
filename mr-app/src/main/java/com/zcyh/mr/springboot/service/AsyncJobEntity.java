package com.zcyh.mr.springboot.service;

/**
 * 异步任务实体（对应 MR_ASYNC_JOB 表）。
 */
class AsyncJobEntity {
    String jobId;
    String requestId;
    String engineCode;
    String payloadJson;
    String status;
    long createdAt;
    Long startedAt;
    Long finishedAt;
    Long elapsedMs;
    Integer successFlag;
    String errorCode;
    String errorMessage;
    String idempotencyKey;
    String traceId;
    String clientId;
    String userId;
    String userName;
    String sourceSystem;
    boolean cancelRequested;
    String ownerNode;
    long updatedAt;
}

