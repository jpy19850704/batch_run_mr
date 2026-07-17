package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.runtime.AlertService;

import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import com.zcyh.mr.springboot.measurement.valuation.ValuationExecutionAdapter;
import com.zcyh.mr.springboot.batch.model.BatchExecutionResult;
import com.zcyh.mr.springboot.output.db.PortfolioHierarchySnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量 Calc 提交任务。
 */
@Component
public class BatchCalcSubmitTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchCalcSubmitTask.class);
    private static final String OP_CODE_PRICING = "PRICING";
    private static final String OP_CODE_SCENARIO = "SCENARIO";
    private static final String STATUS_ACCEPTED = "ACCEPTED";
    private static final String ALERT_BATCH_FAILED = "BATCH_FAILED";

    private final BatchJobService batchJobService;
    private final AsyncJobService asyncJobService;
    private final AlertService alertService;
    private final PortfolioHierarchySnapshotService portfolioHierarchySnapshotService;

    public BatchCalcSubmitTask(
            BatchJobService batchJobService,
            AsyncJobService asyncJobService,
            AlertService alertService,
            PortfolioHierarchySnapshotService portfolioHierarchySnapshotService) {
        this.batchJobService = batchJobService;
        this.asyncJobService = asyncJobService;
        this.alertService = alertService;
        this.portfolioHierarchySnapshotService = portfolioHierarchySnapshotService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        String batchId = context.getBatchId();
        String requestId = batchId;
        String engineCode = ValuationExecutionAdapter.CODE;
        String opCode = context.isScenarioMode() ? OP_CODE_SCENARIO : OP_CODE_PRICING;
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        long now = System.currentTimeMillis();
        ExecutionContextHolder.setBatchId(batchId);
        ExecutionContextHolder.setEngineCode(engineCode);

        if (!context.isLocalRerun()) {
            batchJobService.prepareBatchSubmission(
                    batchId,
                    requestId,
                    engineCode,
                    opCode,
                    dataDate,
                    null,
                    null,
                    context.getLoadedTrades().size(),
                    context.getJobPayloads().size(),
                    now);
        }
        log.info("批量任务开始提交，batchId={}, totalTrades={}, totalJobs={}",
                batchId, context.getLoadedTrades().size(), context.getJobPayloads().size());

        List<String> submittedJobIds = new ArrayList<String>();
        try {
            if (context.isPersistResult() && !context.isLocalRerun()) {
                portfolioHierarchySnapshotService.writeSnapshot(batchId, dataDate);
            }
            for (BatchJobPayload jobPayload : context.getJobPayloads()) {
                String jobId = batchJobService.submitBatchChildJob(batchId, requestId, engineCode, jobPayload);
                if (!jobPayload.isFailed()) {
                    submittedJobIds.add(jobId);
                }
            }
            batchJobService.refreshBatchProgress(
                    batchId,
                    context.isLocalRerun() ? "批次局部重跑任务已提交" : "批量任务已提交");
        } catch (Exception ex) {
            for (String submittedId : submittedJobIds) {
                try {
                    asyncJobService.cancel(submittedId);
                } catch (Exception cancelEx) {
                    log.warn("批量任务补偿取消失败，batchId={}, jobId={}", batchId, submittedId);
                }
            }
            batchJobService.refreshBatchProgress(
                    batchId,
                    "批量提交失败(已取消" + submittedJobIds.size() + "个子任务): " + ex.getMessage());
            alertService.error(ALERT_BATCH_FAILED, "批量任务提交失败，batchId=" + batchId, ex);
            throw ex;
        }
        log.info("批量任务提交完成，batchId={}, totalJobs={}", batchId, context.getJobPayloads().size());

        BatchExecutionResult submitResult = new BatchExecutionResult();
        submitResult.setBatchId(batchId);
        submitResult.setRequestId(requestId);
        submitResult.setEngineCode(engineCode);
        submitResult.setOpCode(opCode);
        submitResult.setDataDate(dataDate.toString());
        submitResult.setStatus(STATUS_ACCEPTED);
        submitResult.setTotalTrades(context.getLoadedTrades().size());
        submitResult.setTotalJobs(context.getJobPayloads().size());
        submitResult.setSubmittedAt(now);
        submitResult.setMessage("批量任务已提交");
        context.setSubmitResult(submitResult);
    }
}
