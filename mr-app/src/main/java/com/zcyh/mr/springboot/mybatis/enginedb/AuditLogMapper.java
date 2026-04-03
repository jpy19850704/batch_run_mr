package com.zcyh.mr.springboot.mybatis.enginedb;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 审计日志 Mapper。
 */
@Mapper
public interface AuditLogMapper {

    @Select("SELECT id,trace_id,request_id,client_id,user_id,user_name,source_system,action,resource_type,resource_id,engine_code,success_flag,error_code,message,remote_ip,request_uri,http_method,elapsed_ms,created_at FROM MR_AUDIT_LOG WHERE 1=0")
    List<Map<String, Object>> verifyAuditSchema();

    @Insert("INSERT INTO MR_AUDIT_LOG (trace_id, request_id, client_id, user_id, user_name, source_system, action, resource_type, resource_id, engine_code, success_flag, error_code, message, remote_ip, request_uri, http_method, elapsed_ms, created_at) "
            + "VALUES (#{traceId}, #{requestId}, #{clientId}, #{userId}, #{userName}, #{sourceSystem}, #{action}, #{resourceType}, #{resourceId}, #{engineCode}, #{successFlag}, #{errorCode}, #{message}, #{remoteIp}, #{requestUri}, #{httpMethod}, #{elapsedMs}, #{createdAt})")
    int insertAuditLog(
            @Param("traceId") String traceId,
            @Param("requestId") String requestId,
            @Param("clientId") String clientId,
            @Param("userId") String userId,
            @Param("userName") String userName,
            @Param("sourceSystem") String sourceSystem,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("engineCode") String engineCode,
            @Param("successFlag") Integer successFlag,
            @Param("errorCode") String errorCode,
            @Param("message") String message,
            @Param("remoteIp") String remoteIp,
            @Param("requestUri") String requestUri,
            @Param("httpMethod") String httpMethod,
            @Param("elapsedMs") Long elapsedMs,
            @Param("createdAt") Long createdAt
    );
}
