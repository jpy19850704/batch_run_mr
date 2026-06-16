package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * 可建模情景 PnL 记录（对应 TB_OUT_IMA_MODELLABLE_SCENARIO_PNL 一行）。
 * 由 SubsetScenarioRunner 生成，ImaModellablePnlPersistService 落库。
 */
public class SubsetPnlRecord {

    private String requestId;
    private String jobId;
    private String batchId;
    private long seqNo;
    private String dataDate;
    private String opCode;

    /** 情景集ID */
    private String scenarioId;
    /** 子情景ID（单条历史情景序号，原始值，不含 _LH 后缀） */
    private String subscenarioId;
    private String scenarioName;
    /** 情景类型：STRESS_REDUCED / NORMAL_FULL / NORMAL_REDUCED */
    private String scenarioType;

    private String instrumentId;
    private String productCode;

    /** 流动性期限天数：10/20/40/60/120 */
    private int lhDays;

    private BigDecimal baseValuationCny;

    /** 利率风险因子子集重定价估值 */
    private BigDecimal irValuation;
    private BigDecimal irPnl;

    /** 信用利差风险因子子集重定价估值 */
    private BigDecimal csValuation;
    private BigDecimal csPnl;

    /** 外汇风险因子子集重定价估值 */
    private BigDecimal fxValuation;
    private BigDecimal fxPnl;

    /** 权益风险因子子集重定价估值 */
    private BigDecimal eqValuation;
    private BigDecimal eqPnl;

    /** 大宗商品风险因子子集重定价估值 */
    private BigDecimal commValuation;
    private BigDecimal commPnl;

    /** 全风险类别子集重定价估值 */
    private BigDecimal allValuation;
    private BigDecimal allPnl;

    private long createdAt;
    private long updatedAt;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public long getSeqNo() { return seqNo; }
    public void setSeqNo(long seqNo) { this.seqNo = seqNo; }

    public String getDataDate() { return dataDate; }
    public void setDataDate(String dataDate) { this.dataDate = dataDate; }

    public String getOpCode() { return opCode; }
    public void setOpCode(String opCode) { this.opCode = opCode; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getSubscenarioId() { return subscenarioId; }
    public void setSubscenarioId(String subscenarioId) { this.subscenarioId = subscenarioId; }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String scenarioType) { this.scenarioType = scenarioType; }

    public String getInstrumentId() { return instrumentId; }
    public void setInstrumentId(String instrumentId) { this.instrumentId = instrumentId; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public int getLhDays() { return lhDays; }
    public void setLhDays(int lhDays) { this.lhDays = lhDays; }

    public BigDecimal getBaseValuationCny() { return baseValuationCny; }
    public void setBaseValuationCny(BigDecimal baseValuationCny) { this.baseValuationCny = baseValuationCny; }

    public BigDecimal getIrValuation() { return irValuation; }
    public void setIrValuation(BigDecimal irValuation) { this.irValuation = irValuation; }

    public BigDecimal getIrPnl() { return irPnl; }
    public void setIrPnl(BigDecimal irPnl) { this.irPnl = irPnl; }

    public BigDecimal getCsValuation() { return csValuation; }
    public void setCsValuation(BigDecimal csValuation) { this.csValuation = csValuation; }

    public BigDecimal getCsPnl() { return csPnl; }
    public void setCsPnl(BigDecimal csPnl) { this.csPnl = csPnl; }

    public BigDecimal getFxValuation() { return fxValuation; }
    public void setFxValuation(BigDecimal fxValuation) { this.fxValuation = fxValuation; }

    public BigDecimal getFxPnl() { return fxPnl; }
    public void setFxPnl(BigDecimal fxPnl) { this.fxPnl = fxPnl; }

    public BigDecimal getEqValuation() { return eqValuation; }
    public void setEqValuation(BigDecimal eqValuation) { this.eqValuation = eqValuation; }

    public BigDecimal getEqPnl() { return eqPnl; }
    public void setEqPnl(BigDecimal eqPnl) { this.eqPnl = eqPnl; }

    public BigDecimal getCommValuation() { return commValuation; }
    public void setCommValuation(BigDecimal commValuation) { this.commValuation = commValuation; }

    public BigDecimal getCommPnl() { return commPnl; }
    public void setCommPnl(BigDecimal commPnl) { this.commPnl = commPnl; }

    public BigDecimal getAllValuation() { return allValuation; }
    public void setAllValuation(BigDecimal allValuation) { this.allValuation = allValuation; }

    public BigDecimal getAllPnl() { return allPnl; }
    public void setAllPnl(BigDecimal allPnl) { this.allPnl = allPnl; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 创建一条 scenarioType 不同但其他字段完全相同的副本。
     * 用于 ALL-in-ReducedSet 优化：NORMAL_FULL 结果直接复制为 NORMAL_REDUCED。
     *
     * @param newScenarioType 新的情景类型
     * @return 副本记录
     */
    public SubsetPnlRecord copyWithScenarioType(String newScenarioType) {
        SubsetPnlRecord copy = new SubsetPnlRecord();
        copy.requestId = this.requestId;
        copy.jobId = this.jobId;
        copy.batchId = this.batchId;
        copy.seqNo = this.seqNo;
        copy.dataDate = this.dataDate;
        copy.opCode = this.opCode;
        copy.scenarioId = this.scenarioId;
        copy.subscenarioId = this.subscenarioId;
        copy.scenarioName = this.scenarioName;
        copy.scenarioType = newScenarioType;
        copy.instrumentId = this.instrumentId;
        copy.productCode = this.productCode;
        copy.lhDays = this.lhDays;
        copy.baseValuationCny = this.baseValuationCny;
        copy.irValuation = this.irValuation;
        copy.irPnl = this.irPnl;
        copy.csValuation = this.csValuation;
        copy.csPnl = this.csPnl;
        copy.fxValuation = this.fxValuation;
        copy.fxPnl = this.fxPnl;
        copy.eqValuation = this.eqValuation;
        copy.eqPnl = this.eqPnl;
        copy.commValuation = this.commValuation;
        copy.commPnl = this.commPnl;
        copy.allValuation = this.allValuation;
        copy.allPnl = this.allPnl;
        copy.createdAt = System.currentTimeMillis();
        copy.updatedAt = copy.createdAt;
        return copy;
    }
}
