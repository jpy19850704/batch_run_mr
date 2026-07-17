package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.batch.model.JobStatus;

/**
 * 异步任务实体（对应 MR_ASYNC_JOB 表）。
 */
public class AsyncJobEntity {
    public String jobId;
    public String requestId;
    public String engineCode;
    public String payloadJson;
    public JobStatus status;
    public long createdAt;
    public Long startedAt;
    public Long finishedAt;
    public Long elapsedMs;
    public Integer successFlag;
    public String errorCode;
    public String errorMessage;
    public String idempotencyKey;
    public String traceId;
    public String clientId;
    public String userId;
    public String userName;
    public String sourceSystem;
    public boolean cancelRequested;
    public String ownerNode;
    public long updatedAt;
}
