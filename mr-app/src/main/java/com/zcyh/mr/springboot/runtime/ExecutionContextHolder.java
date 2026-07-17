package com.zcyh.mr.springboot.runtime;

import org.slf4j.MDC;

/**
 * 线程级执行上下文持有器。
 */
public final class ExecutionContextHolder {
    private static final ThreadLocal<ExecutionContext> HOLDER = new ThreadLocal<ExecutionContext>();

    private ExecutionContextHolder() {
    }

    public static ExecutionContext get() {
        return HOLDER.get();
    }

    public static ExecutionContext getOrCreate() {
        ExecutionContext context = HOLDER.get();
        if (context == null) {
            context = new ExecutionContext();
            HOLDER.set(context);
        }
        return context;
    }

    public static ExecutionContext snapshot() {
        ExecutionContext context = HOLDER.get();
        return context == null ? null : context.copy();
    }

    public static void bind(ExecutionContext context) {
        if (context == null) {
            clear();
            return;
        }
        HOLDER.set(context);
        syncMdc(context);
    }

    public static void clear() {
        HOLDER.remove();
        MDC.clear();
    }

    public static void setJobId(String jobId) {
        ExecutionContext context = getOrCreate();
        context.setJobId(jobId);
        syncMdc(context);
    }

    public static void setBatchId(String batchId) {
        ExecutionContext context = getOrCreate();
        context.setBatchId(batchId);
        syncMdc(context);
    }

    public static void setEngineCode(String engineCode) {
        ExecutionContext context = getOrCreate();
        context.setEngineCode(engineCode);
        syncMdc(context);
    }

    private static void syncMdc(ExecutionContext context) {
        putOrRemove("traceId", context.getTraceId());
        putOrRemove("requestId", context.getRequestId());
        putOrRemove("clientId", context.getClientId());
        putOrRemove("userId", context.getUserId());
        putOrRemove("userName", context.getUserName());
        putOrRemove("sourceSystem", context.getSourceSystem());
        putOrRemove("remoteIp", context.getRemoteIp());
        putOrRemove("requestUri", context.getRequestUri());
        putOrRemove("method", context.getMethod());
        putOrRemove("jobId", context.getJobId());
        putOrRemove("batchId", context.getBatchId());
        putOrRemove("engineCode", context.getEngineCode());
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, value);
    }
}
