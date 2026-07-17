package com.zcyh.mr.springboot.batch.model;

/**
 * 批量执行回执。
 */
public class BatchExecutionResult {
    private String batchId;
    private String requestId;
    private String engineCode;
    private String opCode;
    private String dataDate;
    private String status;
    private int totalTrades;
    private int totalJobs;
    private int weightBudget;
    private long submittedAt;
    private long pollAfterMs;
    private String detailUrl;
    private String message;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

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

    public String getOpCode() {
        return opCode;
    }

    public void setOpCode(String opCode) {
        this.opCode = opCode;
    }

    public String getDataDate() {
        return dataDate;
    }

    public void setDataDate(String dataDate) {
        this.dataDate = dataDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getTotalTrades() {
        return totalTrades;
    }

    public void setTotalTrades(int totalTrades) {
        this.totalTrades = totalTrades;
    }

    public int getTotalJobs() {
        return totalJobs;
    }

    public void setTotalJobs(int totalJobs) {
        this.totalJobs = totalJobs;
    }

    public int getWeightBudget() {
        return weightBudget;
    }

    public void setWeightBudget(int weightBudget) {
        this.weightBudget = weightBudget;
    }

    public long getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(long submittedAt) {
        this.submittedAt = submittedAt;
    }

    public long getPollAfterMs() {
        return pollAfterMs;
    }

    public void setPollAfterMs(long pollAfterMs) {
        this.pollAfterMs = pollAfterMs;
    }

    public String getDetailUrl() {
        return detailUrl;
    }

    public void setDetailUrl(String detailUrl) {
        this.detailUrl = detailUrl;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
