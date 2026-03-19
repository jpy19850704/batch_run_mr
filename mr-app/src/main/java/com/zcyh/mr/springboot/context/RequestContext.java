package com.zcyh.mr.springboot.context;

/**
 * 请求链路上下文。
 */
public class RequestContext {
    private String traceId;
    private String requestId;
    private String clientId;
    private String userId;
    private String userName;
    private String sourceSystem;
    private String remoteIp;
    private String requestUri;
    private String method;
    private String jobId;
    private String batchId;
    private String engineCode;

    public RequestContext copy() {
        RequestContext copy = new RequestContext();
        copy.traceId = this.traceId;
        copy.requestId = this.requestId;
        copy.clientId = this.clientId;
        copy.userId = this.userId;
        copy.userName = this.userName;
        copy.sourceSystem = this.sourceSystem;
        copy.remoteIp = this.remoteIp;
        copy.requestUri = this.requestUri;
        copy.method = this.method;
        copy.jobId = this.jobId;
        copy.batchId = this.batchId;
        copy.engineCode = this.engineCode;
        return copy;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getRemoteIp() {
        return remoteIp;
    }

    public void setRemoteIp(String remoteIp) {
        this.remoteIp = remoteIp;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getEngineCode() {
        return engineCode;
    }

    public void setEngineCode(String engineCode) {
        this.engineCode = engineCode;
    }
}
