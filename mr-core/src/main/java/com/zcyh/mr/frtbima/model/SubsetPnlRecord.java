package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 可建模情景 PnL 记录（对应 TB_OUT_IMA_MODELLABLE_SCENARIO_PNL 一行）。
 * 由 SubsetScenarioRunner 生成，并随 MR_CALC 结果统一落库。
 */
public class SubsetPnlRecord {

    private String batchId;
    private LocalDate dataDate;

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

    private BigDecimal irPnl;

    private BigDecimal csPnl;

    private BigDecimal fxPnl;

    private BigDecimal eqPnl;

    private BigDecimal commPnl;

    private BigDecimal allPnl;

    private long createdAt;
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public LocalDate getDataDate() { return dataDate; }
    public void setDataDate(LocalDate dataDate) { this.dataDate = dataDate; }

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

    public BigDecimal getIrPnl() { return irPnl; }
    public void setIrPnl(BigDecimal irPnl) { this.irPnl = irPnl; }

    public BigDecimal getCsPnl() { return csPnl; }
    public void setCsPnl(BigDecimal csPnl) { this.csPnl = csPnl; }

    public BigDecimal getFxPnl() { return fxPnl; }
    public void setFxPnl(BigDecimal fxPnl) { this.fxPnl = fxPnl; }

    public BigDecimal getEqPnl() { return eqPnl; }
    public void setEqPnl(BigDecimal eqPnl) { this.eqPnl = eqPnl; }

    public BigDecimal getCommPnl() { return commPnl; }
    public void setCommPnl(BigDecimal commPnl) { this.commPnl = commPnl; }

    public BigDecimal getAllPnl() { return allPnl; }
    public void setAllPnl(BigDecimal allPnl) { this.allPnl = allPnl; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    /**
     * 创建一条 scenarioType 不同但其他字段完全相同的副本。
     * 用于 ALL-in-ReducedSet 优化：NORMAL_FULL 结果直接复制为 NORMAL_REDUCED。
     *
     * @param newScenarioType 新的情景类型
     * @return 副本记录
     */
    public SubsetPnlRecord copyWithScenarioType(String newScenarioType) {
        SubsetPnlRecord copy = new SubsetPnlRecord();
        copy.batchId = this.batchId;
        copy.dataDate = this.dataDate;
        copy.scenarioId = this.scenarioId;
        copy.subscenarioId = this.subscenarioId;
        copy.scenarioName = this.scenarioName;
        copy.scenarioType = newScenarioType;
        copy.instrumentId = this.instrumentId;
        copy.productCode = this.productCode;
        copy.lhDays = this.lhDays;
        copy.baseValuationCny = this.baseValuationCny;
        copy.irPnl = this.irPnl;
        copy.csPnl = this.csPnl;
        copy.fxPnl = this.fxPnl;
        copy.eqPnl = this.eqPnl;
        copy.commPnl = this.commPnl;
        copy.allPnl = this.allPnl;
        copy.createdAt = System.currentTimeMillis();
        return copy;
    }
}
