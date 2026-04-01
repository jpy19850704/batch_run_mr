package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.ApiResponse;
import com.zcyh.mr.springboot.model.EngineRunRequest;
import com.zcyh.mr.springboot.model.EngineRunResult;
import com.zcyh.mr.springboot.service.AlertService;
import com.zcyh.mr.springboot.service.AsyncJobService;
import com.zcyh.mr.springboot.service.AuditLogService;
import com.zcyh.mr.springboot.service.EngineOrchestratorService;
import com.zcyh.mr.springboot.service.VarDetailCacheService;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot 对外接口控制器。
 */
@RestController
public class MrEngineController {
    private static final Logger log = LoggerFactory.getLogger(MrEngineController.class);

    private final EngineOrchestratorService orchestratorService;
    private final AsyncJobService asyncJobService;
    private final AuditLogService auditLogService;
    private final AlertService alertService;
    private final VarDetailCacheService varDetailCacheService;

    @Value("${spring.application.name:mr-springboot-app}")
    private String appName;

    public MrEngineController(
            EngineOrchestratorService orchestratorService,
            AsyncJobService asyncJobService,
            AuditLogService auditLogService,
            AlertService alertService,
            ObjectProvider<VarDetailCacheService> varDetailCacheServiceProvider
    ) {
        this.orchestratorService = orchestratorService;
        this.asyncJobService = asyncJobService;
        this.auditLogService = auditLogService;
        this.alertService = alertService;
        this.varDetailCacheService = varDetailCacheServiceProvider.getIfAvailable();
    }

    @GetMapping("/healthz")
    public ApiResponse<Map<String, Object>> healthz() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("status", "UP");
        data.put("app", appName);
        data.put("time", LocalDateTime.now().toString());
        return ApiResponse.ok(data);
    }

    @GetMapping("/readyz")
    public ApiResponse<Map<String, Object>> readyz() {
        Map<String, Object> data = asyncJobService.readinessSnapshot();
        data.put("app", appName);
        data.put("engines", orchestratorService.listEngines().size());
        return ApiResponse.ok(data);
    }

    @GetMapping("/api/v1/engines")
    public ApiResponse<Object> engines() {
        return ApiResponse.ok(orchestratorService.listEngines());
    }

    @PostMapping("/api/v1/engine/run")
    public ApiResponse<EngineRunResult> run(@RequestBody EngineRunRequest request) {
        long start = System.currentTimeMillis();
        String engineCode = request == null ? null : request.getEngineCode();
        RequestContextHolder.setEngineCode(engineCode);
        try {
            EngineRunResult result = orchestratorService.run(request);
            long elapsed = System.currentTimeMillis() - start;
            log.info("同步估值完成，engineCode={}, success={}, elapsedMs={}",
                    result.getEngineCode(), result.isSuccess(), elapsed);
            if (result.isSuccess()) {
                auditLogService.recordSuccess("ENGINE_RUN", "ENGINE", result.getEngineCode(), result.getEngineCode(), "同步估值完成", elapsed);
            } else {
                alertService.warn("ENGINE_RUN_FAILED", "同步估值返回失败结果，engineCode=" + result.getEngineCode());
                auditLogService.recordFailure("ENGINE_RUN", "ENGINE", result.getEngineCode(), result.getEngineCode(), result.getErrorCode(), result.getErrorMessage(), elapsed);
            }
            return ApiResponse.ok(result);
        } catch (RuntimeException ex) {
            long elapsed = System.currentTimeMillis() - start;
            alertService.error("ENGINE_RUN_FAILED", "同步估值失败，engineCode=" + engineCode, ex);
            auditLogService.recordFailure("ENGINE_RUN", "ENGINE", engineCode, engineCode, "ENGINE_RUN_FAILED", ex.getMessage(), elapsed);
            throw ex;
        }
    }

    /**
     * 按维度读取 VaR 明细缓存。
     */
    @PostMapping("/api/v1/engine/var/detail")
    public ApiResponse<Object> varDetail(@RequestBody JSONObject request) {
        if (varDetailCacheService == null) {
            return ApiResponse.fail("CACHE_DISABLED", "VaR 维度缓存服务未启用");
        }
        String requestId = readRequiredField(request, "request_id", "requestId");
        String quantile = readRequiredField(request, "quantile");
        String ruleId = readRequiredField(request, "rule_id", "ruleId");
        String scenarioId = readRequiredField(request, "scenario_id", "scenarioId");
        String groupType = readRequiredField(request, "group_type", "groupType");
        String groupValue = readRequiredField(request, "group_value", "groupValue");

        JSONObject detail;
        try {
            detail = varDetailCacheService.getDimensionDetail(
                    requestId,
                    quantile,
                    ruleId,
                    scenarioId,
                    groupType,
                    groupValue);
        } catch (Exception ex) {
            log.warn("读取 VaR 维度缓存失败: {}", ex.getMessage());
            return ApiResponse.fail("CACHE_UNAVAILABLE", "VaR 维度缓存暂不可用，请稍后重试");
        }
        if (detail == null) {
            return ApiResponse.fail("NOT_FOUND", "未找到对应维度明细缓存，可能已过期，请重新执行 VaR 计算");
        }
        return ApiResponse.ok(detail);
    }

    private static String readRequiredField(JSONObject obj, String... keys) {
        if (obj == null || keys == null || keys.length == 0) {
            throw new IllegalArgumentException("请求参数不能为空");
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = trimToNull(obj.getString(key));
            if (value != null) {
                return value;
            }
        }
        throw new IllegalArgumentException("参数缺失: " + keys[0]);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
