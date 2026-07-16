package com.zcyh.mr.springboot.api;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.model.ApiResponse;
import com.zcyh.mr.springboot.model.EngineRunRequest;
import com.zcyh.mr.springboot.model.EngineRunResult;
import com.zcyh.mr.springboot.service.AlertService;
import com.zcyh.mr.springboot.service.AsyncJobService;
import com.zcyh.mr.springboot.service.AuditLogService;
import com.zcyh.mr.springboot.service.EngineOrchestratorService;
import com.zcyh.mr.springboot.out.cache.ScenarioDetailCacheService;
import com.zcyh.mr.springboot.out.cache.VarDetailCacheService;
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
    private final ScenarioDetailCacheService scenarioDetailCacheService;

    @Value("${spring.application.name:mr-springboot-app}")
    private String appName;

    public MrEngineController(
            EngineOrchestratorService orchestratorService,
            AsyncJobService asyncJobService,
            AuditLogService auditLogService,
            AlertService alertService,
            ObjectProvider<VarDetailCacheService> varDetailCacheServiceProvider,
            ObjectProvider<ScenarioDetailCacheService> scenarioDetailCacheServiceProvider
    ) {
        this.orchestratorService = orchestratorService;
        this.asyncJobService = asyncJobService;
        this.auditLogService = auditLogService;
        this.alertService = alertService;
        this.varDetailCacheService = varDetailCacheServiceProvider.getIfAvailable();
        this.scenarioDetailCacheService = scenarioDetailCacheServiceProvider.getIfAvailable();
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

    @GetMapping("/api/engines")
    public ApiResponse<Object> engines() {
        return ApiResponse.ok(orchestratorService.listEngines());
    }

    @PostMapping("/api/engine/run")
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
    @PostMapping("/api/engine/var/detail")
    public ApiResponse<Object> varDetail(@RequestBody JSONObject request) {
        if (varDetailCacheService == null) {
            return ApiResponse.fail("CACHE_DISABLED", "VaR 维度缓存服务未启用");
        }
        String requestId = readRequiredField(request, "request_id");
        String quantile = readRequiredField(request, "quantile");
        String ruleId = readRequiredField(request, "rule_id");
        String scenarioId = readRequiredField(request, "scenario_id");
        String groupType = readRequiredField(request, "group_type");
        String groupValue = readRequiredField(request, "group_value");

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

    /**
     * 读取情景变化明细缓存的维度列表。
     */
    @PostMapping("/api/engine/scenario/list")
    public ApiResponse<Object> scenarioList(@RequestBody JSONObject request) {
        if (scenarioDetailCacheService == null) {
            return ApiResponse.fail("CACHE_DISABLED", "情景变化明细缓存服务未启用");
        }
        String runId = readRequiredField(request, "run_id");
        String scenarioId = readOptionalField(request, "scenario_id");
        String subScenarioId = readOptionalField(request, "sub_scenario_id");
        String curveType = readOptionalField(request, "curve_type");

        try {
            return ApiResponse.ok(scenarioDetailCacheService.listDimensions(runId, scenarioId, subScenarioId, curveType));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail("INVALID_ARGUMENT", ex.getMessage());
        } catch (Exception ex) {
            log.warn("读取情景变化明细缓存维度列表失败: {}", ex.getMessage());
            return ApiResponse.fail("CACHE_UNAVAILABLE", "情景变化明细缓存暂不可用，请稍后重试");
        }
    }

    /**
     * 按维度读取情景变化明细缓存。
     */
    @PostMapping("/api/engine/scenario/detail")
    public ApiResponse<Object> scenarioDetail(@RequestBody JSONObject request) {
        if (scenarioDetailCacheService == null) {
            return ApiResponse.fail("CACHE_DISABLED", "情景变化明细缓存服务未启用");
        }
        String runId = readRequiredField(request, "run_id");
        String scenarioId = readRequiredField(request, "scenario_id");
        String subScenarioId = readRequiredField(request, "sub_scenario_id");
        String curveType = readRequiredField(request, "curve_type");
        String curveId = readRequiredField(request, "curve_id");

        try {
            JSONObject detail = scenarioDetailCacheService.getDetail(runId, scenarioId, subScenarioId, curveType, curveId);
            if (detail == null) {
                return ApiResponse.fail("NOT_FOUND", "未找到对应情景变化明细缓存，请重新执行情景计算");
            }
            return ApiResponse.ok(detail);
        } catch (IllegalArgumentException ex) {
            return ApiResponse.fail("INVALID_ARGUMENT", ex.getMessage());
        } catch (Exception ex) {
            log.warn("读取情景变化明细缓存失败: {}", ex.getMessage());
            return ApiResponse.fail("CACHE_UNAVAILABLE", "情景变化明细缓存暂不可用，请稍后重试");
        }
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

    private static String readOptionalField(JSONObject obj, String... keys) {
        if (obj == null || keys == null || keys.length == 0) {
            return null;
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
        return null;
    }

}
