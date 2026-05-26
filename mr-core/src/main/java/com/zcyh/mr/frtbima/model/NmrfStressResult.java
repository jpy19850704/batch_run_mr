package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * 单个 NMRF 桶的压力损失结果（Phase2 聚合中间值）。
 * 取该桶 UP/DOWN 两行中 |PNL| 更大的值作为压力损失。
 */
public class NmrfStressResult {

    /** bucketId（如 CNY_SWAP_1826_999999） */
    private String bucketId;

    /** curveId / riskFactorId */
    private String riskFactorId;

    /** NMRF 分类：IDIO_CREDIT / IDIO_EQUITY / OTHER */
    private String nmrfType;

    /** 压力损失（取绝对值最大方向的 |PNL|，恒为非负） */
    private BigDecimal stressLoss;

    public NmrfStressResult() {
    }

    public NmrfStressResult(String bucketId, String riskFactorId, String nmrfType, BigDecimal stressLoss) {
        this.bucketId = bucketId;
        this.riskFactorId = riskFactorId;
        this.nmrfType = nmrfType;
        this.stressLoss = stressLoss;
    }

    public String getBucketId() { return bucketId; }
    public void setBucketId(String bucketId) { this.bucketId = bucketId; }

    public String getRiskFactorId() { return riskFactorId; }
    public void setRiskFactorId(String riskFactorId) { this.riskFactorId = riskFactorId; }

    public String getNmrfType() { return nmrfType; }
    public void setNmrfType(String nmrfType) { this.nmrfType = nmrfType; }

    public BigDecimal getStressLoss() { return stressLoss; }
    public void setStressLoss(BigDecimal stressLoss) { this.stressLoss = stressLoss; }
}
