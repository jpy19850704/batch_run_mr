package com.zcyh.mr.frtbima.validation.model;

import java.math.BigDecimal;

/**
 * PLA（P&L Attribution）测试结果。
 * 规则依据：MAR32.20-32.42
 */
public class PlaTestResult {

    /** Spearman 秩相关系数（假设PnL vs 风险理论PnL） */
    private BigDecimal spearmanCorrelation;

    /** KS 检验统计量 */
    private BigDecimal ksStatistic;

    /** PLA 测试是否通过（Spearman 和 KS 均达到绿区标准） */
    private boolean passed;

    /** Spearman 测试区间（GREEN/AMBER/RED） */
    private String spearmanZone;

    /** KS 测试区间（GREEN/AMBER/RED） */
    private String ksZone;

    public PlaTestResult() {
    }

    public PlaTestResult(BigDecimal spearmanCorrelation, BigDecimal ksStatistic,
                         boolean passed, String spearmanZone, String ksZone) {
        this.spearmanCorrelation = spearmanCorrelation;
        this.ksStatistic = ksStatistic;
        this.passed = passed;
        this.spearmanZone = spearmanZone;
        this.ksZone = ksZone;
    }

    public BigDecimal getSpearmanCorrelation() {
        return spearmanCorrelation;
    }

    public void setSpearmanCorrelation(BigDecimal spearmanCorrelation) {
        this.spearmanCorrelation = spearmanCorrelation;
    }

    public BigDecimal getKsStatistic() {
        return ksStatistic;
    }

    public void setKsStatistic(BigDecimal ksStatistic) {
        this.ksStatistic = ksStatistic;
    }

    public boolean isPassed() {
        return passed;
    }

    public void setPassed(boolean passed) {
        this.passed = passed;
    }

    public String getSpearmanZone() {
        return spearmanZone;
    }

    public void setSpearmanZone(String spearmanZone) {
        this.spearmanZone = spearmanZone;
    }

    public String getKsZone() {
        return ksZone;
    }

    public void setKsZone(String ksZone) {
        this.ksZone = ksZone;
    }
}
