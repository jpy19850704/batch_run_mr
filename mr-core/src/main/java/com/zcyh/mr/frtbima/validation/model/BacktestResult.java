package com.zcyh.mr.frtbima.validation.model;

import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 回测结果。
 * 包含交通灯区间、例外次数、乘数附加值，以及每次突破的明细记录。
 * 规则依据：MAR32.4-32.9
 */
public class BacktestResult {

    /** 交通灯区间 */
    private TrafficLightZone zone;

    /** 250天窗口内的例外次数（实际PnL超出前一日99% VaR的天数） */
    private int exceptionCount;

    /**
     * 乘数附加值（加法项 k，MAR32.4 Table）。
     * 绿区：0；黄区：0.4-0.85；红区：模型不合格（此处记录1.0作为占位）
     */
    private BigDecimal multiplierAddOn;

    /**
     * 突破明细列表。
     * 每条记录包含突破日期、当日PnL、前一日VaR阈值等信息。
     */
    private List<ExceptionDetail> exceptions;

    public BacktestResult() {
        this.exceptions = new ArrayList<>();
    }

    public BacktestResult(TrafficLightZone zone, int exceptionCount, BigDecimal multiplierAddOn) {
        this.zone = zone;
        this.exceptionCount = exceptionCount;
        this.multiplierAddOn = multiplierAddOn;
        this.exceptions = new ArrayList<>();
    }

    public BacktestResult(TrafficLightZone zone, int exceptionCount,
                          BigDecimal multiplierAddOn, List<ExceptionDetail> exceptions) {
        this.zone = zone;
        this.exceptionCount = exceptionCount;
        this.multiplierAddOn = multiplierAddOn;
        this.exceptions = exceptions != null ? exceptions : new ArrayList<>();
    }

    public TrafficLightZone getZone() {
        return zone;
    }

    public void setZone(TrafficLightZone zone) {
        this.zone = zone;
    }

    public int getExceptionCount() {
        return exceptionCount;
    }

    public void setExceptionCount(int exceptionCount) {
        this.exceptionCount = exceptionCount;
    }

    public BigDecimal getMultiplierAddOn() {
        return multiplierAddOn;
    }

    public void setMultiplierAddOn(BigDecimal multiplierAddOn) {
        this.multiplierAddOn = multiplierAddOn;
    }

    public List<ExceptionDetail> getExceptions() {
        return Collections.unmodifiableList(exceptions);
    }

    public void setExceptions(List<ExceptionDetail> exceptions) {
        this.exceptions = exceptions != null ? exceptions : new ArrayList<>();
    }
}
