package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * IMCC 聚合结果（MAR33.15）。
 *
 * 公式：IMCC = 0.5 × IMCC(C) + 0.5 × Σ IMCC(Ci)
 * 其中 ρ=0.5，IMCC(Ci) 为各风险类别的约束 ES。
 */
public class ImccResult {

    /** 全类别约束 ES（IMCC(C)） */
    private BigDecimal imccConstrained;

    /** 各风险类别约束 ES 之和（Σ IMCC(Ci)） */
    private BigDecimal imccUnconstrainedSum;

    /** 各风险类别 ES（riskClass → esValue） */
    private Map<String, BigDecimal> riskClassEs;

    /** 最终 IMCC = 0.5 × imccConstrained + 0.5 × imccUnconstrainedSum */
    private BigDecimal imcc;

    public ImccResult() {
    }

    public BigDecimal getImccConstrained() { return imccConstrained; }
    public void setImccConstrained(BigDecimal imccConstrained) { this.imccConstrained = imccConstrained; }

    public BigDecimal getImccUnconstrainedSum() { return imccUnconstrainedSum; }
    public void setImccUnconstrainedSum(BigDecimal imccUnconstrainedSum) { this.imccUnconstrainedSum = imccUnconstrainedSum; }

    public Map<String, BigDecimal> getRiskClassEs() { return riskClassEs; }
    public void setRiskClassEs(Map<String, BigDecimal> riskClassEs) { this.riskClassEs = riskClassEs; }

    public BigDecimal getImcc() { return imcc; }
    public void setImcc(BigDecimal imcc) { this.imcc = imcc; }
}
