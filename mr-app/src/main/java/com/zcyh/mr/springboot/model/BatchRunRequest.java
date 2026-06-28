package com.zcyh.mr.springboot.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 批次总编排运行请求。
 */
public class BatchRunRequest {
    @JsonProperty("batch_id")
    private String batchId;
    @JsonProperty("data_date")
    private String dataDate;
    private String user;
    @JsonProperty("regular_scenario_id_list")
    private String regularScenarioIdList;
    @JsonProperty("var_scenario_id_list")
    private String varScenarioIdList;
    @JsonProperty("normal_full_scenario_id_list")
    private String normalFullScenarioIdList;
    @JsonProperty("normal_reduced_scenario_id_list")
    private String normalReducedScenarioIdList;
    @JsonProperty("stress_reduced_scenario_id_list")
    private String stressReducedScenarioIdList;
    @JsonProperty("nmrf_scenario_id_list")
    private String nmrfScenarioIdList;
    @JsonProperty("run_mode")
    private String runMode;
    @JsonProperty("persist_scenario")
    private Boolean persistScenario;
    @JsonProperty("persist_result")
    private Boolean persistResult;
    @JsonProperty("cache_scenario_result")
    private Boolean cacheScenarioResult;
    @JsonProperty("frtb_disable")
    private Boolean frtbDisable;
    @JsonProperty("frtb_sba_rule_id_list")
    private String frtbSbaRuleIdList;
    @JsonProperty("var_rule_id_list")
    private String varRuleIdList;
    @JsonProperty("drc_rule_id_list")
    private String drcRuleIdList;
    @JsonProperty("trade_filter")
    private BatchTradeFilter tradeFilter;

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

    public String getVarScenarioIdList() {
        return varScenarioIdList;
    }

    public void setVarScenarioIdList(String varScenarioIdList) {
        this.varScenarioIdList = varScenarioIdList;
    }

    public String getNormalFullScenarioIdList() {
        return normalFullScenarioIdList;
    }

    public void setNormalFullScenarioIdList(String normalFullScenarioIdList) {
        this.normalFullScenarioIdList = normalFullScenarioIdList;
    }

    public String getNormalReducedScenarioIdList() {
        return normalReducedScenarioIdList;
    }

    public void setNormalReducedScenarioIdList(String normalReducedScenarioIdList) {
        this.normalReducedScenarioIdList = normalReducedScenarioIdList;
    }

    public String getStressReducedScenarioIdList() {
        return stressReducedScenarioIdList;
    }

    public void setStressReducedScenarioIdList(String stressReducedScenarioIdList) {
        this.stressReducedScenarioIdList = stressReducedScenarioIdList;
    }

    public String getNmrfScenarioIdList() {
        return nmrfScenarioIdList;
    }

    public void setNmrfScenarioIdList(String nmrfScenarioIdList) {
        this.nmrfScenarioIdList = nmrfScenarioIdList;
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

    public Boolean getCacheScenarioResult() {
        return cacheScenarioResult;
    }

    public void setCacheScenarioResult(Boolean cacheScenarioResult) {
        this.cacheScenarioResult = cacheScenarioResult;
    }

    public Boolean getFrtbDisable() {
        return frtbDisable;
    }

    public void setFrtbDisable(Boolean frtbDisable) {
        this.frtbDisable = frtbDisable;
    }

    public String getFrtbSbaRuleIdList() {
        return frtbSbaRuleIdList;
    }

    public void setFrtbSbaRuleIdList(String frtbSbaRuleIdList) {
        this.frtbSbaRuleIdList = frtbSbaRuleIdList;
    }

    public String getVarRuleIdList() {
        return varRuleIdList;
    }

    public void setVarRuleIdList(String varRuleIdList) {
        this.varRuleIdList = varRuleIdList;
    }

    public String getDrcRuleIdList() {
        return drcRuleIdList;
    }

    public void setDrcRuleIdList(String drcRuleIdList) {
        this.drcRuleIdList = drcRuleIdList;
    }

    public BatchTradeFilter getTradeFilter() {
        return tradeFilter;
    }

    public void setTradeFilter(BatchTradeFilter tradeFilter) {
        this.tradeFilter = tradeFilter;
    }
}
