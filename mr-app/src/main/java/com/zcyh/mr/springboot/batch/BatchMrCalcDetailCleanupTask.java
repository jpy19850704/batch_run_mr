package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.output.db.MrCalcDetailCleanupService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 完整批次 MR_CALC 明细清理任务。
 */
@Component
public class BatchMrCalcDetailCleanupTask implements BatchRunTask {
    private final MrCalcDetailCleanupService cleanupService;

    public BatchMrCalcDetailCleanupTask(MrCalcDetailCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isPersistResult()) {
            return;
        }
        if (context.isLocalRerun()) {
            return;
        }
        cleanupService.cleanupBatch(
                context.getBatchId(),
                LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE));
    }
}
