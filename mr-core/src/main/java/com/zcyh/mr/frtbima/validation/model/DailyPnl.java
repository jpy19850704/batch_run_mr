package com.zcyh.mr.frtbima.validation.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 每日损益记录，包含回测和 PLA 所需的三类 PnL 及前一日 VaR。
 * 规则依据：MAR32.5（实际PnL）、MAR32.20（假设PnL与风险理论PnL）
 */
public class DailyPnl {

    /** 日期 */
    private LocalDate date;

    /**
     * 实际 PnL（Actual P&L）。
     * 用于回测：与前一日 VaR 阈值比较，超出为例外。
     */
    private BigDecimal actualPnl;

    /**
     * 假设 PnL（Hypothetical P&L）。
     * 基于期末头寸、不含费率和佣金的盯市损益，用于 PLA 测试。
     */
    private BigDecimal hypotheticalPnl;

    /**
     * 风险理论 PnL（Risk-Theoretical P&L）。
     * 由内模风险因子推算的损益（即内模的一日P&L），用于 PLA 测试。
     */
    private BigDecimal riskTheoreticalPnl;

    /**
     * 前一日 99% VaR 值（正数表示损失阈值）。
     * 回测中，T 日的实际 PnL 与 T-1 日计算的 VaR 对比来判定例外。
     * 规则依据：MAR32.4
     */
    private BigDecimal varValue;

    public DailyPnl() {
    }

    public DailyPnl(LocalDate date, BigDecimal actualPnl,
                    BigDecimal hypotheticalPnl, BigDecimal riskTheoreticalPnl) {
        this.date = date;
        this.actualPnl = actualPnl;
        this.hypotheticalPnl = hypotheticalPnl;
        this.riskTheoreticalPnl = riskTheoreticalPnl;
    }

    public DailyPnl(LocalDate date, BigDecimal actualPnl,
                    BigDecimal hypotheticalPnl, BigDecimal riskTheoreticalPnl,
                    BigDecimal varValue) {
        this.date = date;
        this.actualPnl = actualPnl;
        this.hypotheticalPnl = hypotheticalPnl;
        this.riskTheoreticalPnl = riskTheoreticalPnl;
        this.varValue = varValue;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public BigDecimal getActualPnl() {
        return actualPnl;
    }

    public void setActualPnl(BigDecimal actualPnl) {
        this.actualPnl = actualPnl;
    }

    public BigDecimal getHypotheticalPnl() {
        return hypotheticalPnl;
    }

    public void setHypotheticalPnl(BigDecimal hypotheticalPnl) {
        this.hypotheticalPnl = hypotheticalPnl;
    }

    public BigDecimal getRiskTheoreticalPnl() {
        return riskTheoreticalPnl;
    }

    public void setRiskTheoreticalPnl(BigDecimal riskTheoreticalPnl) {
        this.riskTheoreticalPnl = riskTheoreticalPnl;
    }

    public BigDecimal getVarValue() {
        return varValue;
    }

    public void setVarValue(BigDecimal varValue) {
        this.varValue = varValue;
    }
}
