package com.zcyh.mr.frtbima.validation.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 回测单次突破（例外）的明细记录。
 * 记录突破发生的日期、PnL类型（实际/假设）、当日PnL值、前一日VaR值。
 * 规则依据：MAR32.4-32.6
 */
public class ExceptionDetail {

    /** PnL类型常量：实际PnL */
    public static final String PNL_TYPE_ACTUAL = "ACTUAL";

    /** PnL类型常量：假设PnL */
    public static final String PNL_TYPE_HYPOTHETICAL = "HYPOTHETICAL";

    /** 突破发生日期 */
    private LocalDate date;

    /**
     * PnL 类型标识。
     * ACTUAL = 实际PnL突破，HYPOTHETICAL = 假设PnL突破。
     * 同一天可能同时出现两条明细（actual和hypothetical各一条）。
     */
    private String pnlType;

    /** 当日 PnL 值（触发突破的实际或假设PnL） */
    private BigDecimal pnl;

    /** 前一日 99% VaR 值（正数，即损失阈值） */
    private BigDecimal varValue;

    /** 实际使用的阈值（= -|varValue|），PnL低于此值即为突破 */
    private BigDecimal threshold;

    public ExceptionDetail() {
    }

    public ExceptionDetail(LocalDate date, String pnlType, BigDecimal pnl,
                           BigDecimal varValue, BigDecimal threshold) {
        this.date = date;
        this.pnlType = pnlType;
        this.pnl = pnl;
        this.varValue = varValue;
        this.threshold = threshold;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getPnlType() {
        return pnlType;
    }

    public void setPnlType(String pnlType) {
        this.pnlType = pnlType;
    }

    public BigDecimal getPnl() {
        return pnl;
    }

    public void setPnl(BigDecimal pnl) {
        this.pnl = pnl;
    }

    public BigDecimal getVarValue() {
        return varValue;
    }

    public void setVarValue(BigDecimal varValue) {
        this.varValue = varValue;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    @Override
    public String toString() {
        return String.format("ExceptionDetail{date=%s, pnlType=%s, pnl=%s, varValue=%s, threshold=%s}",
                date, pnlType, pnl, varValue, threshold);
    }
}
