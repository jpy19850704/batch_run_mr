package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.BatchSubmitResult;
import com.zcyh.mr.springboot.model.JobSubmitRequest;
import com.zcyh.mr.springboot.model.JobSubmitResult;
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

    private final BatchJobService batchJobService;
    private final AsyncJobService asyncJobService;
    private final AlertService alertService;

    public BatchCalcSubmitTask(
            BatchJobService batchJobService,
            AsyncJobService asyncJobService,
            AlertService alertService) {
        this.batchJobService = batchJobService;
        this.asyncJobService = asyncJobService;
        this.alertService = alertService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        String batchId = context.getBatchId();
        String requestId = batchId;
        String engineCode = MrCalcEngineAdapter.CODE;
        String opCode = context.isScenarioMode() ? "SCENARIO" : "PRICING";
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        long now = System.currentTimeMillis();
        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode(engineCode);

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
        log.info("批量任务开始提交，batchId={}, totalTrades={}, totalJobs={}",
                batchId, context.getLoadedTrades().size(), context.getJobPayloads().size());

        int submittedJobs = 0;
        List<String> submittedJobIds = new ArrayList<String>();
        try {
            batchJobService.syncPortfolioHierarchySnapshot(batchId, dataDate);
            for (BatchJobPayload jobPayload : context.getJobPayloads()) {
                JobSubmitRequest jobRequest = new JobSubmitRequest();
                String jobId = BatchJobService.buildJobId(batchId, jobPayload.getSeqNo());
                jobRequest.setJobId(jobId);
                jobRequest.setRequestId(BatchJobService.buildJobRequestId(requestId, jobPayload.getSeqNo()));
                jobRequest.setEngineCode(engineCode);
                jobRequest.setIdempotencyKey(jobId);
                jobRequest.setPayload(jobPayload.getPayload());

                JobSubmitResult submitResult = asyncJobService.submit(jobRequest);
                submittedJobIds.add(submitResult.getJobId());
                batchJobService.insertBatchItem(batchId, jobPayload.getSeqNo(), submitResult.getJobId(), jobPayload.getChunkTrades());
                submittedJobs++;
            }
            batchJobService.updateBatchStatus(
                    batchId,
                    "SUBMITTED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    now,
                    "批量任务已提交");
        } catch (Exception ex) {
            for (String submittedId : submittedJobIds) {
                try {
                    asyncJobService.cancel(submittedId);
                } catch (Exception cancelEx) {
                    log.warn("批量任务补偿取消失败，batchId={}, jobId={}", batchId, submittedId);
                }
            }
            int pending = Math.max(0, context.getJobPayloads().size() - submittedJobs);
            batchJobService.updateBatchStatus(
                    batchId,
                    "FAILED",
                    pending,
                    0,
                    0,
                    submittedJobIds.size(),
                    0,
                    System.currentTimeMillis(),
                    "批量提交失败(已取消" + submittedJobIds.size() + "个子任务): " + ex.getMessage());
            alertService.error("BATCH_FAILED", "批量任务提交失败，batchId=" + batchId, ex);
            throw ex;
        }
        log.info("批量任务提交完成，batchId={}, totalJobs={}", batchId, context.getJobPayloads().size());

        BatchSubmitResult submitResult = new BatchSubmitResult();
        submitResult.setBatchId(batchId);
        submitResult.setRequestId(requestId);
        submitResult.setEngineCode(engineCode);
        submitResult.setOpCode(opCode);
        submitResult.setDataDate(dataDate.toString());
        submitResult.setStatus("SUBMITTED");
        submitResult.setTotalTrades(context.getLoadedTrades().size());
        submitResult.setTotalJobs(context.getJobPayloads().size());
        submitResult.setSubmittedAt(now);
        submitResult.setMessage("批量任务已提交");
        context.setSubmitResult(submitResult);
    }
}
