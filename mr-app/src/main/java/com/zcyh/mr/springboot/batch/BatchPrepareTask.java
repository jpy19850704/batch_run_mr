package com.zcyh.mr.springboot.batch;

import org.springframework.stereotype.Component;

/**
 * 批量运行通用准备任务。
 */
@Component
public class BatchPrepareTask implements BatchRunTask {
    private final CalendarFileBootstrapService calendarFileBootstrapService;

    public BatchPrepareTask(CalendarFileBootstrapService calendarFileBootstrapService) {
        this.calendarFileBootstrapService = calendarFileBootstrapService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        calendarFileBootstrapService.refreshForBatch(context.getBatchId());
    }
}
