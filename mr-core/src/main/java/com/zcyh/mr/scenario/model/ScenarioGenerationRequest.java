package com.zcyh.mr.scenario.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 场景生成请求。
 */
public class ScenarioGenerationRequest {
    private LocalDate valuationDate;
    private String scenarioIdList;
    private String user;
    private String source;
    private List<ScenarioTaskRequest> tasks = new ArrayList<ScenarioTaskRequest>();

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public void setValuationDate(LocalDate valuationDate) {
        this.valuationDate = valuationDate;
    }

    public String getScenarioIdList() {
        return scenarioIdList;
    }

    public void setScenarioIdList(String scenarioIdList) {
        this.scenarioIdList = scenarioIdList;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public List<ScenarioTaskRequest> getTasks() {
        return tasks;
    }

    public void setTasks(List<ScenarioTaskRequest> tasks) {
        this.tasks = tasks == null ? new ArrayList<ScenarioTaskRequest>() : tasks;
    }
}
