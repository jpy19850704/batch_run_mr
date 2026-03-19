package com.zcyh.mr.springboot.context;

import org.slf4j.MDC;

/**
 * 线程级请求上下文持有器。
 */
public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<RequestContext>();

    private RequestContextHolder() {
    }

    public static RequestContext get() {
        return HOLDER.get();
    }

    public static RequestContext getOrCreate() {
        RequestContext context = HOLDER.get();
        if (context == null) {
            context = new RequestContext();
            HOLDER.set(context);
        }
        return context;
    }

    public static RequestContext snapshot() {
        RequestContext context = HOLDER.get();
        return context == null ? null : context.copy();
    }

    public static void bind(RequestContext context) {
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
        RequestContext context = getOrCreate();
        context.setJobId(jobId);
        syncMdc(context);
    }

    public static void setBatchId(String batchId) {
        RequestContext context = getOrCreate();
        context.setBatchId(batchId);
        syncMdc(context);
    }

    public static void setEngineCode(String engineCode) {
        RequestContext context = getOrCreate();
        context.setEngineCode(engineCode);
        syncMdc(context);
    }

    private static void syncMdc(RequestContext context) {
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
