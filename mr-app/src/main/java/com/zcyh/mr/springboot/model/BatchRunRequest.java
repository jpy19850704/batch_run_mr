package com.zcyh.mr.springboot.model;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 批次总编排运行请求。
 */
public class BatchRunRequest {
    @JsonAlias("batch_id")
    private String batchId;
    @JsonAlias("data_date")
    private String dataDate;
    private String user;
    @JsonAlias("regular_scenario_id_list")
    private String regularScenarioIdList;
    @JsonAlias({"riskclassdecomp_scenario_id_list", "risk_class_decomp_scenario_id_list"})
    private String riskClassDecompScenarioIdList;
    @JsonAlias("run_mode")
    private String runMode;
    @JsonAlias("persist_scenario")
    private Boolean persistScenario;
    @JsonAlias("persist_result")
    private Boolean persistResult;

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

    public String getRegularScenarioIdList() {
        return regularScenarioIdList;
    }

    public void setRegularScenarioIdList(String regularScenarioIdList) {
        this.regularScenarioIdList = regularScenarioIdList;
    }

    public String getRiskClassDecompScenarioIdList() {
        return riskClassDecompScenarioIdList;
    }

    public void setRiskClassDecompScenarioIdList(String riskClassDecompScenarioIdList) {
        this.riskClassDecompScenarioIdList = riskClassDecompScenarioIdList;
    }

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public Boolean getPersistScenario() {
        return persistScenario;
    }

    public void setPersistScenario(Boolean persistScenario) {
        this.persistScenario = persistScenario;
    }

    public Boolean getPersistResult() {
        return persistResult;
    }

    public void setPersistResult(Boolean persistResult) {
        this.persistResult = persistResult;
    }
}
