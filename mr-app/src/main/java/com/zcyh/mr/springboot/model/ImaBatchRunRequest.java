package com.zcyh.mr.springboot.model;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * IMA 批量计量请求。
 */
public class ImaBatchRunRequest {
    @JsonProperty("batch_id")
    private String batchId;
    @JsonProperty("data_date")
    private String dataDate;
    private String user;
    @JsonProperty("normal_full_scenario_id_list")
    private String normalFullScenarioIdList;
    @JsonProperty("normal_reduced_scenario_id_list")
    private String normalReducedScenarioIdList;
    @JsonProperty("stress_reduced_scenario_id_list")
    private String stressReducedScenarioIdList;
    @JsonProperty("nmrf_scenario_id_list")
    private String nmrfScenarioIdList;
    @JsonProperty("ima_rule_id_list")
    private String imaRuleIdList;
    @JsonProperty("persist_scenario")
    private Boolean persistScenario;
    @JsonProperty("persist_result")
    private Boolean persistResult;
    @JsonProperty("cache_scenario_result")
    private Boolean cacheScenarioResult;
    @JsonProperty("frtb_disable")
    private Boolean frtbDisable;
    @JsonProperty("trade_filter")
    private BatchTradeFilter tradeFilter;
    @JsonProperty("sa_by_desk")
    private JSONObject saByDesk;
    @JsonProperty("amber_desks")
    private JSONArray amberDesks;
    @JsonProperty("green_desks")
    private JSONArray greenDesks;

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

    public String getImaRuleIdList() {
        return imaRuleIdList;
    }

    public void setImaRuleIdList(String imaRuleIdList) {
        this.imaRuleIdList = imaRuleIdList;
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

    public BatchTradeFilter getTradeFilter() {
        return tradeFilter;
    }

    public void setTradeFilter(BatchTradeFilter tradeFilter) {
        this.tradeFilter = tradeFilter;
    }

    public JSONObject getSaByDesk() {
        return saByDesk;
    }

    public void setSaByDesk(JSONObject saByDesk) {
        this.saByDesk = saByDesk;
    }

    public JSONArray getAmberDesks() {
        return amberDesks;
    }

    public void setAmberDesks(JSONArray amberDesks) {
        this.amberDesks = amberDesks;
    }

    public JSONArray getGreenDesks() {
        return greenDesks;
    }

    public void setGreenDesks(JSONArray greenDesks) {
        this.greenDesks = greenDesks;
    }
}
