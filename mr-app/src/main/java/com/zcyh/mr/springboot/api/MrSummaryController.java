package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.ApiResponse;
import com.zcyh.mr.springboot.service.AlertService;
import com.zcyh.mr.springboot.service.AuditLogService;
import com.zcyh.mr.springboot.service.FrtbDrcSummaryService;
import com.zcyh.mr.springboot.service.FrtbRraoSummaryService;
import com.zcyh.mr.springboot.service.FrtbSbaSummaryService;
import com.zcyh.mr.springboot.service.VarSummaryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 汇总接口控制器。
 * 提供独立的 VaR、FRTB SBA、FRTB DRC 汇总入口。
 */
@RestController
@RequestMapping("/api/summary")
public class MrSummaryController {
    private final FrtbSbaSummaryService frtbSbaSummaryService;
    private final FrtbDrcSummaryService frtbDrcSummaryService;
    private final FrtbRraoSummaryService frtbRraoSummaryService;
    private final VarSummaryService varSummaryService;
    private final AuditLogService auditLogService;
    private final AlertService alertService;

    public MrSummaryController(FrtbSbaSummaryService frtbSbaSummaryService,
                               FrtbDrcSummaryService frtbDrcSummaryService,
                               FrtbRraoSummaryService frtbRraoSummaryService,
                               VarSummaryService varSummaryService,
                               AuditLogService auditLogService,
                               AlertService alertService) {
        this.frtbSbaSummaryService = frtbSbaSummaryService;
        this.frtbDrcSummaryService = frtbDrcSummaryService;
        this.frtbRraoSummaryService = frtbRraoSummaryService;
        this.varSummaryService = varSummaryService;
        this.auditLogService = auditLogService;
        this.alertService = alertService;
    }

    @PostMapping("/frtb/sba")
    public ApiResponse<Object> sbaSummary(@RequestBody JSONObject request) {
        long start = System.currentTimeMillis();
        String batchId = readBatchId(request);
        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode("frtb_sba");
        try {
            JSONObject result = frtbSbaSummaryService.summarize(request);
            auditLogService.recordSuccess(
                    "SUMMARY_FRTB_SBA",
                    "SUMMARY",
                    batchId,
                    "frtb_sba",
                    "FRTB SBA 汇总成功",
                    System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("SUMMARY_FRTB_SBA_FAILED", "FRTB SBA 汇总失败，batchId=" + batchId, ex);
            auditLogService.recordFailure(
                    "SUMMARY_FRTB_SBA",
                    "SUMMARY",
                    batchId,
                    "frtb_sba",
                    "SUMMARY_FRTB_SBA_FAILED",
                    ex.getMessage(),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @PostMapping("/frtb/drc")
    public ApiResponse<Object> drcSummary(@RequestBody JSONObject request) {
        long start = System.currentTimeMillis();
        String batchId = readBatchId(request);
        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode("frtb_drc");
        try {
            JSONObject result = frtbDrcSummaryService.summarize(request);
            auditLogService.recordSuccess(
                    "SUMMARY_FRTB_DRC",
                    "SUMMARY",
                    batchId,
                    "frtb_drc",
                    "FRTB DRC 汇总成功",
                    System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("SUMMARY_FRTB_DRC_FAILED", "FRTB DRC 汇总失败，batchId=" + batchId, ex);
            auditLogService.recordFailure(
                    "SUMMARY_FRTB_DRC",
                    "SUMMARY",
                    batchId,
                    "frtb_drc",
                    "SUMMARY_FRTB_DRC_FAILED",
                    ex.getMessage(),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @PostMapping("/frtb/rrao")
    public ApiResponse<Object> rraoSummary(@RequestBody JSONObject request) {
        long start = System.currentTimeMillis();
        String batchId = readBatchId(request);
        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode("frtb_rrao");
        try {
            JSONObject result = frtbRraoSummaryService.summarize(request);
            auditLogService.recordSuccess(
                    "SUMMARY_FRTB_RRAO",
                    "SUMMARY",
                    batchId,
                    "frtb_rrao",
                    "FRTB RRAO 汇总成功",
                    System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("SUMMARY_FRTB_RRAO_FAILED", "FRTB RRAO 汇总失败，batchId=" + batchId, ex);
            auditLogService.recordFailure(
                    "SUMMARY_FRTB_RRAO",
                    "SUMMARY",
                    batchId,
                    "frtb_rrao",
                    "SUMMARY_FRTB_RRAO_FAILED",
                    ex.getMessage(),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

    @PostMapping("/var")
    public ApiResponse<Object> varSummary(@RequestBody JSONObject request) {
        long start = System.currentTimeMillis();
        String batchId = readBatchId(request);
        RequestContextHolder.setBatchId(batchId);
        RequestContextHolder.setEngineCode("var");
        try {
            JSONObject result = varSummaryService.summarize(request);
            auditLogService.recordSuccess(
                    "SUMMARY_VAR",
                    "SUMMARY",
                    batchId,
                    "var",
                    "VaR 汇总成功",
                    System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            alertService.error("SUMMARY_VAR_FAILED", "VaR 汇总失败，batchId=" + batchId, ex);
            auditLogService.recordFailure(
                    "SUMMARY_VAR",
                    "SUMMARY",
                    batchId,
                    "var",
                    "SUMMARY_VAR_FAILED",
                    ex.getMessage(),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

    private static String readBatchId(JSONObject request) {
        if (request == null) {
            return null;
        }
        String batchId = trimToNull(request.getString("batch_id"));
        if (batchId != null) {
            return batchId;
        }
        return null;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
