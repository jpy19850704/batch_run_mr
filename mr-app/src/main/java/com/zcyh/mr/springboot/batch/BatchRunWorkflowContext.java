package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.InputFilterExpression;

import com.zcyh.mr.springboot.input.db.MarketCurveInputRow;

import com.zcyh.mr.springboot.input.db.TradeInputRow;

import com.zcyh.mr.springboot.batch.model.BatchDetailResult;
import com.zcyh.mr.springboot.batch.model.BatchExecutionResult;
import com.zcyh.mr.springboot.batch.model.BatchRunRequest;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量运行工作流上下文。
 */
public class BatchRunWorkflowContext {
    private BatchRunRequest request;
    private String batchId;
    private String dataDate;
    private String user;
    private String regularScenarioIdList;
    private String varScenarioIdList;
    private String normalFullScenarioIdList;
    private String normalReducedScenarioIdList;
    private String stressReducedScenarioIdList;
    private String nmrfScenarioIdList;
    private boolean scenarioMode;
    private String runMode;
    private boolean whatifMode;
    private boolean externalBatchIdProvided;
    private boolean persistResult;
    private boolean cacheScenarioResult;
    private boolean frtbDisabled;
    private boolean localRerun;
    private int firstJobSeqNo = 1;
    private List<String> instrumentIds = new ArrayList<String>();
    private InputFilterExpression tradeFilter;
    private List<ScenarioGeneratedRecord> scenarioRecords = new ArrayList<ScenarioGeneratedRecord>();
    private List<TradeInputRow> loadedTrades = new ArrayList<TradeInputRow>();
    private List<MarketCurveInputRow> loadedMarketData = new ArrayList<MarketCurveInputRow>();
    private List<List<TradeInputRow>> tradeChunks = new ArrayList<List<TradeInputRow>>();
    private Set<String> scenarioMarketKeys = new LinkedHashSet<String>();
    private List<BatchJobPayload> jobPayloads = new ArrayList<BatchJobPayload>();
    private BatchExecutionResult submitResult;
    private BatchDetailResult batchDetail;
    private BatchStatusCalculator.BatchStatusSnapshot batchStatusSnapshot;

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

    public boolean isCacheScenarioResult() {
        return cacheScenarioResult;
    }

    public void setCacheScenarioResult(boolean cacheScenarioResult) {
        this.cacheScenarioResult = cacheScenarioResult;
    }

    public boolean isFrtbDisabled() {
        return frtbDisabled;
    }

    public void setFrtbDisabled(boolean frtbDisabled) {
        this.frtbDisabled = frtbDisabled;
    }

    public boolean isLocalRerun() {
        return localRerun;
    }

    public void setLocalRerun(boolean localRerun) {
        this.localRerun = localRerun;
    }

    public int getFirstJobSeqNo() {
        return firstJobSeqNo;
    }

    public void setFirstJobSeqNo(int firstJobSeqNo) {
        this.firstJobSeqNo = firstJobSeqNo;
    }

    public List<String> getInstrumentIds() {
        return instrumentIds;
    }

    public void setInstrumentIds(List<String> instrumentIds) {
        this.instrumentIds = instrumentIds == null
                ? new ArrayList<String>()
                : instrumentIds;
    }

    public InputFilterExpression getTradeFilter() {
        return tradeFilter;
    }

    public void setTradeFilter(InputFilterExpression tradeFilter) {
        this.tradeFilter = tradeFilter;
    }

    public List<ScenarioGeneratedRecord> getScenarioRecords() {
        return scenarioRecords;
    }

    public void setScenarioRecords(List<ScenarioGeneratedRecord> scenarioRecords) {
        this.scenarioRecords = scenarioRecords == null
                ? new ArrayList<ScenarioGeneratedRecord>()
                : scenarioRecords;
    }

    public List<TradeInputRow> getLoadedTrades() {
        return loadedTrades;
    }

    public void setLoadedTrades(List<TradeInputRow> loadedTrades) {
        this.loadedTrades = loadedTrades == null
                ? new ArrayList<TradeInputRow>()
                : loadedTrades;
    }

    public List<MarketCurveInputRow> getLoadedMarketData() {
        return loadedMarketData;
    }

    public void setLoadedMarketData(List<MarketCurveInputRow> loadedMarketData) {
        this.loadedMarketData = loadedMarketData == null
                ? new ArrayList<MarketCurveInputRow>()
                : loadedMarketData;
    }

    public List<List<TradeInputRow>> getTradeChunks() {
        return tradeChunks;
    }

    public void setTradeChunks(List<List<TradeInputRow>> tradeChunks) {
        this.tradeChunks = tradeChunks == null
                ? new ArrayList<List<TradeInputRow>>()
                : tradeChunks;
    }

    public Set<String> getScenarioMarketKeys() {
        return scenarioMarketKeys;
    }

    public void setScenarioMarketKeys(Set<String> scenarioMarketKeys) {
        this.scenarioMarketKeys = scenarioMarketKeys == null
                ? new LinkedHashSet<String>()
                : new LinkedHashSet<String>(scenarioMarketKeys);
    }

    public List<BatchJobPayload> getJobPayloads() {
        return jobPayloads;
    }

    public void setJobPayloads(List<BatchJobPayload> jobPayloads) {
        this.jobPayloads = jobPayloads == null
                ? new ArrayList<BatchJobPayload>()
                : jobPayloads;
    }

    public BatchExecutionResult getSubmitResult() {
        return submitResult;
    }

    public void setSubmitResult(BatchExecutionResult submitResult) {
        this.submitResult = submitResult;
    }

    public BatchDetailResult getBatchDetail() {
        return batchDetail;
    }

    public void setBatchDetail(BatchDetailResult batchDetail) {
        this.batchDetail = batchDetail;
    }

    BatchStatusCalculator.BatchStatusSnapshot getBatchStatusSnapshot() {
        return batchStatusSnapshot;
    }

    void setBatchStatusSnapshot(BatchStatusCalculator.BatchStatusSnapshot batchStatusSnapshot) {
        this.batchStatusSnapshot = batchStatusSnapshot;
    }
}
