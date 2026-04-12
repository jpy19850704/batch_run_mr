package com.zcyh.mr.springboot.model;

/**
 * 批次总编排运行请求。
 */
public class BatchRunRequest {
    private String batchId;
    private String dataDate;
    private String user;
    private String scenarioIdList;
    private String runMode;

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

    public String getScenarioIdList() {
        return scenarioIdList;
    }

    public void setScenarioIdList(String scenarioIdList) {
        this.scenarioIdList = scenarioIdList;
    }

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }
}
