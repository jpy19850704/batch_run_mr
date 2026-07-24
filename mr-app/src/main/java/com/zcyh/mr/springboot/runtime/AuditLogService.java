package com.zcyh.mr.springboot.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务。
 */
@Service
public class AuditLogService {
    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;
    private final boolean enabled;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            @Value("${mr.audit.enabled:true}") boolean enabled
    ) {
        this.auditLogRepository = auditLogRepository;
        this.enabled = enabled;
        if (this.enabled) {
            verifyAuditSchema();
        }
    }

    public void recordSuccess(String action, String resourceType, String resourceId, String engineCode, String message, long elapsedMs) {
        record(action, resourceType, resourceId, engineCode, true, null, message, elapsedMs);
    }

    public void recordFailure(String action, String resourceType, String resourceId, String engineCode, String errorCode, String message, long elapsedMs) {
        record(action, resourceType, resourceId, engineCode, false, errorCode, message, elapsedMs);
    }

    private void record(String action, String resourceType, String resourceId, String engineCode, boolean success, String errorCode, String message, long elapsedMs) {
        if (!enabled) {
            return;
        }
        ExecutionContext context = ExecutionContextHolder.snapshot();
        try {
            auditLogRepository.insert(
                    safe(context == null ? null : context.getTraceId()),
                    safe(context == null ? null : context.getRequestId()),
                    safe(context == null ? null : context.getClientId()),
                    safe(context == null ? null : context.getUserId()),
                    safe(context == null ? null : context.getUserName()),
                    safe(context == null ? null : context.getSourceSystem()),
                    safe(action),
                    safe(resourceType),
                    safe(resourceId),
                    safe(engineCode),
                    success ? 1 : 0,
                    safe(errorCode),
                    safe(message),
                    safe(context == null ? null : context.getRemoteIp()),
                    safe(context == null ? null : context.getRequestUri()),
                    safe(context == null ? null : context.getMethod()),
                    Math.max(0L, elapsedMs)
            );
        } catch (Exception ex) {
            log.error("审计日志写入失败，action={}, resourceType={}, resourceId={}", action, resourceType, resourceId, ex);
        }
    }

    private void verifyAuditSchema() {
        auditLogRepository.verifySchema();
    }

    private static String safe(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
