package com.zcyh.mr.springboot.service;

/**
 * 批量运行工作流任务。
 */
public interface BatchRunTask {
    void execute(BatchRunWorkflowContext context);
}
