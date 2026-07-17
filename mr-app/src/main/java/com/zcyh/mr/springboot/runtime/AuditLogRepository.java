package com.zcyh.mr.springboot.runtime;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 审计日志仓储。
 */
@Repository
public class AuditLogRepository {
    private static final String VERIFY_SQL = "SELECT id,trace_id,request_id,client_id,user_id,user_name,"
            + "source_system,action,resource_type,resource_id,engine_code,success_flag,error_code,message,"
            + "remote_ip,request_uri,http_method,elapsed_ms,created_at FROM MR_AUDIT_LOG WHERE 1=0";
    private static final String INSERT_SQL = "INSERT INTO MR_AUDIT_LOG "
            + "(trace_id, request_id, client_id, user_id, user_name, source_system, action, resource_type, "
            + "resource_id, engine_code, success_flag, error_code, message, remote_ip, request_uri, "
            + "http_method, elapsed_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void verifySchema() {
        jdbcTemplate.queryForList(VERIFY_SQL);
    }

    public int insert(
            String traceId,
            String requestId,
            String clientId,
            String userId,
            String userName,
            String sourceSystem,
            String action,
            String resourceType,
            String resourceId,
            String engineCode,
            Integer successFlag,
            String errorCode,
            String message,
            String remoteIp,
            String requestUri,
            String httpMethod,
            Long elapsedMs,
            Long createdAt) {
        return jdbcTemplate.update(
                INSERT_SQL,
                traceId,
                requestId,
                clientId,
                userId,
                userName,
                sourceSystem,
                action,
                resourceType,
                resourceId,
                engineCode,
                successFlag,
                errorCode,
                message,
                remoteIp,
                requestUri,
                httpMethod,
                elapsedMs,
                createdAt);
    }
}
