package com.zcyh.mr.springboot.execution;

/**
 * 计量执行请求体。
 */
public class MeasurementExecutionRequest {
    private String requestId;
    private String engineCode;
    private Object payload;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getEngineCode() {
        return engineCode;
    }

    public void setEngineCode(String engineCode) {
        this.engineCode = engineCode;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }
}

