package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.out.db.MrCalcDetailCleanupService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 局部重跑 MR_CALC 明细清理任务。
 */
@Component
public class BatchLocalMrCalcDetailCleanupTask implements BatchRunTask {
    private final MrCalcDetailCleanupService cleanupService;

    public BatchLocalMrCalcDetailCleanupTask(MrCalcDetailCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isLocalRerun() || !context.isPersistResult()) {
            return;
        }
        cleanupService.cleanupInstruments(
                context.getBatchId(),
                LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE),
                context.getInstrumentIds());
    }
}
