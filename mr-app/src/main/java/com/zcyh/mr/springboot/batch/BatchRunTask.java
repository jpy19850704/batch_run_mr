package com.zcyh.mr.springboot.batch;

/**
 * 批量运行工作流任务。
 */
public interface BatchRunTask {
    void execute(BatchRunWorkflowContext context);
}
