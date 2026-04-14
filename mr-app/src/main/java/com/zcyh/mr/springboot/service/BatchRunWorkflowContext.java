package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.zcyh.mr.springboot.model.BatchDetailResult;
import com.zcyh.mr.springboot.model.BatchRunRequest;
import com.zcyh.mr.springboot.model.BatchSubmitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量运行工作流上下文。
 */
public class BatchRunWorkflowContext {
    private BatchRunRequest request;
    private String batchId;
    private String dataDate;
    private String user;
    private String regularScenarioIdList;
    private String riskClassDecompScenarioIdList;
    private boolean scenarioMode;
    private String runMode;
    private boolean whatifMode;
    private boolean externalBatchIdProvided;
    private boolean persistResult;
    private String scenarioJson;
    private JSONArray scenarioData = new JSONArray();
    private List<BatchTradeDataLoader.TradeRow> loadedTrades = new ArrayList<BatchTradeDataLoader.TradeRow>();
    private List<BatchTradeDataLoader.CurveRow> loadedMarketData = new ArrayList<BatchTradeDataLoader.CurveRow>();
    private List<List<BatchTradeDataLoader.TradeRow>> tradeChunks = new ArrayList<List<BatchTradeDataLoader.TradeRow>>();
    private List<BatchJobPayload> jobPayloads = new ArrayList<BatchJobPayload>();
    private BatchSubmitResult submitResult;
    private BatchDetailResult batchDetail;

    public BatchRunRequest getRequest() {
        return request;
    }

    public void setRequest(BatchRunRequest request) {
        this.request = request;
    }

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

    public boolean isScenarioMode() {
        return scenarioMode;
    }

    public void setScenarioMode(boolean scenarioMode) {
        this.scenarioMode = scenarioMode;
    }

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public boolean isWhatifMode() {
        return whatifMode;
    }

    public void setWhatifMode(boolean whatifMode) {
        this.whatifMode = whatifMode;
    }

    public boolean isExternalBatchIdProvided() {
        return externalBatchIdProvided;
    }

    public void setExternalBatchIdProvided(boolean externalBatchIdProvided) {
        this.externalBatchIdProvided = externalBatchIdProvided;
    }

    public boolean isPersistResult() {
        return persistResult;
    }

    public void setPersistResult(boolean persistResult) {
        this.persistResult = persistResult;
    }

    public String getScenarioJson() {
        return scenarioJson;
    }

    public void setScenarioJson(String scenarioJson) {
        this.scenarioJson = scenarioJson;
    }

    public JSONArray getScenarioData() {
        return scenarioData;
    }

    public void setScenarioData(JSONArray scenarioData) {
        this.scenarioData = scenarioData == null ? new JSONArray() : scenarioData;
    }

    public List<BatchTradeDataLoader.TradeRow> getLoadedTrades() {
        return loadedTrades;
    }

    public void setLoadedTrades(List<BatchTradeDataLoader.TradeRow> loadedTrades) {
        this.loadedTrades = loadedTrades == null
                ? new ArrayList<BatchTradeDataLoader.TradeRow>()
                : loadedTrades;
    }

    public List<BatchTradeDataLoader.CurveRow> getLoadedMarketData() {
        return loadedMarketData;
    }

    public void setLoadedMarketData(List<BatchTradeDataLoader.CurveRow> loadedMarketData) {
        this.loadedMarketData = loadedMarketData == null
                ? new ArrayList<BatchTradeDataLoader.CurveRow>()
                : loadedMarketData;
    }

    public List<List<BatchTradeDataLoader.TradeRow>> getTradeChunks() {
        return tradeChunks;
    }

    public void setTradeChunks(List<List<BatchTradeDataLoader.TradeRow>> tradeChunks) {
        this.tradeChunks = tradeChunks == null
                ? new ArrayList<List<BatchTradeDataLoader.TradeRow>>()
                : tradeChunks;
    }

    public List<BatchJobPayload> getJobPayloads() {
        return jobPayloads;
    }

    public void setJobPayloads(List<BatchJobPayload> jobPayloads) {
        this.jobPayloads = jobPayloads == null
                ? new ArrayList<BatchJobPayload>()
                : jobPayloads;
    }

    public BatchSubmitResult getSubmitResult() {
        return submitResult;
    }

    public void setSubmitResult(BatchSubmitResult submitResult) {
        this.submitResult = submitResult;
    }

    public BatchDetailResult getBatchDetail() {
        return batchDetail;
    }

    public void setBatchDetail(BatchDetailResult batchDetail) {
        this.batchDetail = batchDetail;
    }
}
