package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * SES 聚合结果（MAR33.17）。
 *
 * 公式：SES = sqrt( Σ_idio_credit(loss²) + Σ_idio_equity(loss²)
 *                 + (ρ × Σ_other(loss))² + (1-ρ²) × Σ_other(loss²) )
 * ρ = 0.6（MAR33.17）
 */
public class SesResult {

    /** Σ_idio_credit(loss²) */
    private BigDecimal idioCreditSumSq;

    /** Σ_idio_equity(loss²) */
    private BigDecimal idioEquitySumSq;

    /** (ρ × Σ_other(loss))² */
    private BigDecimal otherCorrTerm;

    /** (1-ρ²) × Σ_other(loss²) */
    private BigDecimal otherIdioTerm;

    /** 最终 SES = sqrt(idioCreditSumSq + idioEquitySumSq + otherCorrTerm + otherIdioTerm) */
    private BigDecimal ses;

    public SesResult() {
    }

    public BigDecimal getIdioCreditSumSq() { return idioCreditSumSq; }
    public void setIdioCreditSumSq(BigDecimal idioCreditSumSq) { this.idioCreditSumSq = idioCreditSumSq; }

    public BigDecimal getIdioEquitySumSq() { return idioEquitySumSq; }
    public void setIdioEquitySumSq(BigDecimal idioEquitySumSq) { this.idioEquitySumSq = idioEquitySumSq; }

    public BigDecimal getOtherCorrTerm() { return otherCorrTerm; }
    public void setOtherCorrTerm(BigDecimal otherCorrTerm) { this.otherCorrTerm = otherCorrTerm; }

    public BigDecimal getOtherIdioTerm() { return otherIdioTerm; }
    public void setOtherIdioTerm(BigDecimal otherIdioTerm) { this.otherIdioTerm = otherIdioTerm; }

    public BigDecimal getSes() { return ses; }
    public void setSes(BigDecimal ses) { this.ses = ses; }
}
