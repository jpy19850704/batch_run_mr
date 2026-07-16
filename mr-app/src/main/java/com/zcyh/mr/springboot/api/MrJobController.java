package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.ApiResponse;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.BatchExecutionResult;
import com.zcyh.mr.springboot.model.BatchPatchRequest;
import com.zcyh.mr.springboot.model.BatchRunRequest;
import com.zcyh.mr.springboot.model.BatchRunResult;
import com.zcyh.mr.springboot.model.JobDetailResult;
import com.zcyh.mr.springboot.service.AlertService;
import com.zcyh.mr.springboot.service.AsyncJobService;
import com.zcyh.mr.springboot.service.AuditLogService;
import com.zcyh.mr.springboot.service.BatchRunService;
import com.zcyh.mr.springboot.service.BatchJobService;
import com.zcyh.mr.springboot.out.cache.TradeInfoCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务控制器。
 */
@RestController
@RequestMapping("/api/jobs")
public class MrJobController {
    private static final Logger log = LoggerFactory.getLogger(MrJobController.class);

    private final AsyncJobService asyncJobService;
    private final BatchJobService batchJobService;
    private final BatchRunService batchRunService;
    private final AuditLogService auditLogService;
    private final AlertService alertService;
    private final TradeInfoCacheService tradeInfoCacheService;

    public MrJobController(
            AsyncJobService asyncJobService,
            BatchJobService batchJobService,
            BatchRunService batchRunService,
            AuditLogService auditLogService,
            AlertService alertService,
            TradeInfoCacheService tradeInfoCacheService
    ) {
        this.asyncJobService = asyncJobService;
        this.batchJobService = batchJobService;
        this.batchRunService = batchRunService;
        this.auditLogService = auditLogService;
        this.alertService = alertService;
        this.tradeInfoCacheService = tradeInfoCacheService;
    }

    @GetMapping("/{jobId}")
    public ApiResponse<JobDetailResult> detail(@PathVariable("jobId") String jobId) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setJobId(jobId);
        try {
            JobDetailResult result = asyncJobService.getDetail(jobId);
            RequestContextHolder.setEngineCode(result.getEngineCode());
            auditLogService.recordSuccess("JOB_DETAIL_QUERY", "JOB", jobId, result.getEngineCode(), "任务详情查询成功", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure("JOB_DETAIL_QUERY", "JOB", jobId, null, "JOB_DETAIL_QUERY_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @PostMapping("/{jobId}/cancel")
    public ApiResponse<JobDetailResult> cancel(@PathVariable("jobId") String jobId) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setJobId(jobId);
        try {
            JobDetailResult result = asyncJobService.cancel(jobId);
            RequestContextHolder.setEngineCode(result.getEngineCode());
            log.info("任务取消请求完成，jobId={}, status={}", jobId, result.getStatus());
            auditLogService.recordSuccess("JOB_CANCEL", "JOB", jobId, result.getEngineCode(), "任务取消完成", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("JOB_CANCEL_FAILED", "任务取消失败，jobId=" + jobId, ex);
            auditLogService.recordFailure("JOB_CANCEL", "JOB", jobId, null, "JOB_CANCEL_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @GetMapping("/{jobId}/scenario-result")
    public ApiResponse<JSONObject> scenarioResult(@PathVariable("jobId") String jobId) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setJobId(jobId);
        try {
            JSONObject result = asyncJobService.getScenarioPnl(jobId);
            auditLogService.recordSuccess("JOB_SCENARIO_RESULT_QUERY", "JOB", jobId, "MR_CALC", "任务情景结果查询成功", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure("JOB_SCENARIO_RESULT_QUERY", "JOB", jobId, "MR_CALC", "JOB_SCENARIO_RESULT_QUERY_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @PostMapping("/batch/run")
    public ApiResponse<BatchRunResult> runBatch(@RequestBody BatchRunRequest request) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setBatchId(request == null ? null : request.getBatchId());
        RequestContextHolder.setEngineCode("MR_CALC");
        try {
            BatchRunResult result = batchRunService.run(request);
            RequestContextHolder.setBatchId(result.getBatchId());
            auditLogService.recordSuccess("BATCH_RUN", "BATCH", result.getBatchId(), "MR_CALC", "批次总编排已异步启动", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("BATCH_RUN_FAILED", "批次总编排执行失败", ex);
            auditLogService.recordFailure("BATCH_RUN", "BATCH", request == null ? null : request.getBatchId(), "MR_CALC", "BATCH_RUN_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @PostMapping("/batch/patch")
    public ApiResponse<BatchExecutionResult> patchBatch(@RequestBody BatchPatchRequest request) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setBatchId(request == null ? null : request.getBatchId());
        RequestContextHolder.setEngineCode("MR_CALC");
        try {
            BatchExecutionResult result = batchRunService.patch(request);
            RequestContextHolder.setBatchId(result.getBatchId());
            auditLogService.recordSuccess("BATCH_PATCH", "BATCH", result.getBatchId(), result.getEngineCode(), "批量补丁提交成功", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("BATCH_PATCH_FAILED", "批量补丁提交失败", ex);
            auditLogService.recordFailure("BATCH_PATCH", "BATCH", request == null ? null : request.getBatchId(), "MR_CALC", "BATCH_PATCH_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @GetMapping("/batch/{batchId}")
    public ApiResponse<BatchDetailResult> batchDetail(@PathVariable("batchId") String batchId) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setBatchId(batchId);
        try {
            BatchDetailResult result = batchJobService.getDetail(batchId);
            auditLogService.recordSuccess("BATCH_DETAIL_QUERY", "BATCH", batchId, result.getEngineCode(), "批量详情查询成功", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure("BATCH_DETAIL_QUERY", "BATCH", batchId, null, "BATCH_DETAIL_QUERY_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @GetMapping("/batch/{batchId}/trade-info")
    public ApiResponse<JSONObject> batchTradeInfo(@PathVariable("batchId") String batchId) {
        long start = System.currentTimeMillis();
        RequestContextHolder.setBatchId(batchId);
        try {
            JSONObject result = tradeInfoCacheService.getBatchTradeInfo(batchId);
            auditLogService.recordSuccess("BATCH_TRADE_INFO_QUERY", "BATCH", batchId, "MR_CALC", "批次交易维度快照查询成功", System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure("BATCH_TRADE_INFO_QUERY", "BATCH", batchId, "MR_CALC", "BATCH_TRADE_INFO_QUERY_FAILED", ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

}
