package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.runtime.ExecutionContextHolder;
import com.zcyh.mr.springboot.api.ApiResponse;
import com.zcyh.mr.springboot.measurement.frtb.FrtbDrcSummaryRequest;
import com.zcyh.mr.springboot.measurement.frtb.FrtbSbaSummaryRequest;
import com.zcyh.mr.springboot.measurement.RuleSummaryRequest;
import com.zcyh.mr.springboot.measurement.var.VarSummaryRequest;
import com.zcyh.mr.springboot.runtime.AuditLogService;
import com.zcyh.mr.springboot.measurement.frtb.FrtbDrcSummaryService;
import com.zcyh.mr.springboot.measurement.frtb.FrtbRraoResultService;
import com.zcyh.mr.springboot.measurement.frtb.FrtbSbaSummaryService;
import com.zcyh.mr.springboot.measurement.ima.ImaCapitalSummaryService;
import com.zcyh.mr.springboot.measurement.var.VarSummaryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/**
 * 汇总接口控制器。
 * 提供独立的 VaR、FRTB SBA、FRTB DRC 汇总入口。
 */
@RestController
@RequestMapping("/api/summary")
public class MrSummaryController {
    private final FrtbSbaSummaryService frtbSbaSummaryService;
    private final FrtbDrcSummaryService frtbDrcSummaryService;
    private final FrtbRraoResultService frtbRraoResultService;
    private final VarSummaryService varSummaryService;
    private final ImaCapitalSummaryService imaCapitalSummaryService;
    private final AuditLogService auditLogService;

    public MrSummaryController(FrtbSbaSummaryService frtbSbaSummaryService,
                               FrtbDrcSummaryService frtbDrcSummaryService,
                               FrtbRraoResultService frtbRraoResultService,
                               VarSummaryService varSummaryService,
                               ImaCapitalSummaryService imaCapitalSummaryService,
                               AuditLogService auditLogService) {
        this.frtbSbaSummaryService = frtbSbaSummaryService;
        this.frtbDrcSummaryService = frtbDrcSummaryService;
        this.frtbRraoResultService = frtbRraoResultService;
        this.varSummaryService = varSummaryService;
        this.imaCapitalSummaryService = imaCapitalSummaryService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/frtb/sba")
    public ApiResponse<Object> sbaSummary(@RequestBody JSONObject request) {
        FrtbSbaSummaryRequest parsed = SummaryRequestParser.parseSba(request);
        return executeSummary(parsed.getBatchId(), "frtb_sba", "SUMMARY_FRTB_SBA", "FRTB SBA",
                () -> frtbSbaSummaryService.summarize(parsed));
    }

    @PostMapping("/frtb/drc")
    public ApiResponse<Object> drcSummary(@RequestBody JSONObject request) {
        FrtbDrcSummaryRequest parsed = SummaryRequestParser.parseDrc(request);
        return executeSummary(parsed.getBatchId(), "frtb_drc", "SUMMARY_FRTB_DRC", "FRTB DRC",
                () -> frtbDrcSummaryService.summarize(parsed));
    }

    @PostMapping("/frtb/rrao")
    public ApiResponse<Object> rraoSummary(@RequestBody JSONObject request) {
        RuleSummaryRequest parsed = SummaryRequestParser.parseRrao(request);
        return executeSummary(parsed.getBatchId(), "frtb_rrao", "SUMMARY_FRTB_RRAO", "FRTB RRAO",
                () -> frtbRraoResultService.summarize(parsed));
    }

    @PostMapping("/var")
    public ApiResponse<Object> varSummary(@RequestBody JSONObject request) {
        VarSummaryRequest parsed = SummaryRequestParser.parseVar(request);
        return executeSummary(parsed.getBatchId(), "var", "SUMMARY_VAR", "VaR",
                () -> varSummaryService.summarize(parsed));
    }

    @PostMapping("/ima/capital")
    public ApiResponse<Object> imaCapitalSummary(@RequestBody JSONObject request) {
        RuleSummaryRequest parsed = SummaryRequestParser.parseImaCapital(request);
        return executeSummary(parsed.getBatchId(), "ima_capital", "SUMMARY_IMA_CAPITAL", "IMA资本",
                () -> imaCapitalSummaryService.summarize(parsed));
    }

    private ApiResponse<Object> executeSummary(String batchId, String engineCode,
            String operationCode, String displayName, Supplier<JSONObject> summaryAction) {
        long start = System.currentTimeMillis();
        ExecutionContextHolder.setBatchId(batchId);
        ExecutionContextHolder.setEngineCode(engineCode);
        try {
            JSONObject result = summaryAction.get();
            auditLogService.recordSuccess(
                    operationCode,
                    "SUMMARY",
                    batchId,
                    engineCode,
                    displayName + " 汇总成功",
                    System.currentTimeMillis() - start);
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            String errorCode = operationCode + "_FAILED";
            auditLogService.recordFailure(
                    operationCode,
                    "SUMMARY",
                    batchId,
                    engineCode,
                    errorCode,
                    ex.getMessage(),
                    System.currentTimeMillis() - start);
            throw ex;
        }
    }

}
