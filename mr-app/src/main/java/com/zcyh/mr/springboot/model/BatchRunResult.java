package com.zcyh.mr.springboot.model;

/**
 * 批次总编排运行结果。
 */
public class BatchRunResult {
    private String batchId;
    private String dataDate;
    private String user;
    private String mode;
    private boolean scenarioGenerated;
    private int scenarioCount;
    private BatchDetailResult batchDetail;
    private Object frtbSummary;
    private Object drcSummary;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getDataDate() {
        return dataDate;
    }

    public void setDataDate(String dataDate) {
        this.dataDate = dataDate;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isScenarioGenerated() {
        return scenarioGenerated;
    }

    public void setScenarioGenerated(boolean scenarioGenerated) {
        this.scenarioGenerated = scenarioGenerated;
    }

    public int getScenarioCount() {
        return scenarioCount;
    }

    public void setScenarioCount(int scenarioCount) {
        this.scenarioCount = scenarioCount;
    }

    public BatchDetailResult getBatchDetail() {
        return batchDetail;
    }

    public void setBatchDetail(BatchDetailResult batchDetail) {
        this.batchDetail = batchDetail;
    }

    public Object getFrtbSummary() {
        return frtbSummary;
    }

    public void setFrtbSummary(Object frtbSummary) {
        this.frtbSummary = frtbSummary;
    }

    public Object getDrcSummary() {
        return drcSummary;
    }

    public void setDrcSummary(Object drcSummary) {
        this.drcSummary = drcSummary;
    }
}
