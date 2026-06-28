package com.zcyh.mr.scenario.model;

import com.zcyh.mr.frtbima.rfet.model.RfetResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个场景任务请求。
 */
public class ScenarioTaskRequest {
    private String scenarioId;
    private String scenarioType;
    private LocalDate valuationDate;
    private List<ScenarioDefinition> definitions = new ArrayList<ScenarioDefinition>();
    private List<String> warnings = new ArrayList<String>();
    private Map<String, List<ScenarioMarketSeries>> currentMarketData = new LinkedHashMap<String, List<ScenarioMarketSeries>>();
    private Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> historicalMarketData = new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();
    private List<RfetResult> imaRfetResults = new ArrayList<RfetResult>();

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public String getScenarioType() {
        return scenarioType;
    }

    public void setScenarioType(String scenarioType) {
        this.scenarioType = scenarioType;
    }

    public LocalDate getValuationDate() {
        return valuationDate;
    }

    public void setValuationDate(LocalDate valuationDate) {
        this.valuationDate = valuationDate;
    }

    public List<ScenarioDefinition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(List<ScenarioDefinition> definitions) {
        this.definitions = definitions == null ? new ArrayList<ScenarioDefinition>() : definitions;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<String>() : warnings;
    }

    public Map<String, List<ScenarioMarketSeries>> getCurrentMarketData() {
        return currentMarketData;
    }

    public void setCurrentMarketData(Map<String, List<ScenarioMarketSeries>> currentMarketData) {
        this.currentMarketData = currentMarketData == null
                ? new LinkedHashMap<String, List<ScenarioMarketSeries>>()
                : currentMarketData;
    }

    public Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> getHistoricalMarketData() {
        return historicalMarketData;
    }

    public void setHistoricalMarketData(Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> historicalMarketData) {
        this.historicalMarketData = historicalMarketData == null
                ? new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>()
                : historicalMarketData;
    }

    public List<RfetResult> getImaRfetResults() {
        return imaRfetResults;
    }

    public void setImaRfetResults(List<RfetResult> imaRfetResults) {
        this.imaRfetResults = imaRfetResults == null ? new ArrayList<RfetResult>() : imaRfetResults;
    }
}
