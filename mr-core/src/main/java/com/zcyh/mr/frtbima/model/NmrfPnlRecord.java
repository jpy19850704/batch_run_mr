package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * NMRF 情景 PnL 记录（对应 TB_OUT_IMA_NMRF_SCENARIO_PNL 一行）。
 * 由 MR_CALC 情景结果映射生成，并随结果处理链路统一落库。
 *
 * 每个桶生成两行：subscenarioId = bucketId_UP 和 bucketId_DOWN
 */
public class NmrfPnlRecord {

    private String batchId;
    private String dataDate;

    /** 情景集ID（即 bucketId，如 CNY_SWAP_1826_999999） */
    private String scenarioId;

    /** 子情景ID（{bucketId}_UP 或 {bucketId}_DOWN） */
    private String subscenarioId;
    private String scenarioName;

    private String instrumentId;
    private String productCode;

    /** 曲线ID（如 CNY_SWAP），供 SES 按因子维度汇总 */
    private String riskFactorId;

    /** NMRF 分类：IDIO_CREDIT / IDIO_EQUITY / OTHER */
    private String nmrfType;

    private BigDecimal baseValuationCny;

    /** PnL = stressValuation - baseValuation（亏损为负） */
    private BigDecimal pnl;

    private long createdAt;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getDataDate() { return dataDate; }
    public void setDataDate(String dataDate) { this.dataDate = dataDate; }

    public String getScenarioId() { return scenarioId; }
    public void setScenarioId(String scenarioId) { this.scenarioId = scenarioId; }

    public String getSubscenarioId() { return subscenarioId; }
    public void setSubscenarioId(String subscenarioId) { this.subscenarioId = subscenarioId; }

    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }

    public String getInstrumentId() { return instrumentId; }
    public void setInstrumentId(String instrumentId) { this.instrumentId = instrumentId; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getRiskFactorId() { return riskFactorId; }
    public void setRiskFactorId(String riskFactorId) { this.riskFactorId = riskFactorId; }

    public String getNmrfType() { return nmrfType; }
    public void setNmrfType(String nmrfType) { this.nmrfType = nmrfType; }

    public BigDecimal getBaseValuationCny() { return baseValuationCny; }
    public void setBaseValuationCny(BigDecimal baseValuationCny) { this.baseValuationCny = baseValuationCny; }

    public BigDecimal getPnl() { return pnl; }
    public void setPnl(BigDecimal pnl) { this.pnl = pnl; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
