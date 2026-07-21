package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.batch.model.JobStatus;
import com.zcyh.mr.springboot.output.db.BatchDorisResultWriterService;
import com.zcyh.mr.springboot.output.file.BatchResultFileService;
import com.zcyh.mr.springboot.output.file.BatchResultStageService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 批次明细结果统一写入任务。
 */
@Component
public class BatchDorisResultWriteTask implements BatchRunTask {
    private final BatchDorisResultWriterService writerService;
    private final BatchResultFileService batchResultFileService;

    public BatchDorisResultWriteTask(BatchDorisResultWriterService writerService,
                                     BatchResultFileService batchResultFileService) {
        this.writerService = writerService;
        this.batchResultFileService = batchResultFileService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isPersistResult()) {
            return;
        }
        BatchStatusCalculator.BatchStatusSnapshot status = context.getBatchStatusSnapshot();
        if (status == null || status.status != JobStatus.SUCCESS) {
            return;
        }
        writerService.persistExecution(
                context.getBatchId(),
                LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE),
                context.getExecutionType(),
                context.getExecutionId(),
                context.getJobPayloads().size(),
                context.isLocalRerun() ? context.getInstrumentIds() : null);
        if (BatchResultStageService.EXECUTION_TYPE_BATCH.equals(context.getExecutionType())) {
            batchResultFileService.tryWriteSnapshotForBatch(context.getBatchId());
        }
    }
}
