package com.zcyh.mr.frtbima.rfet.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 实际价格观测记录，粒度为 (curveId, tenorDays[, strikeLevel]) 单个价格点。
 * 对应 MAR31.12 中用于 RFET 判定的"真实价格"数据点。
 *
 * <p>非波动率产品（利率、权益、外汇即期等）：
 *   strikeLevel = null，仅使用 (curveId, tenorDays) 定位。
 *
 * <p>波动率曲面产品（IR_VOL/EQ_VOL/FX_VOL/COMM_VOL）：
 *   delta 表示期权到期时 ITM 的概率（δ ∈ [0,1]，Row D），
 *   与 tenorDays（到期日天数，Row C）共同定位波动率曲面上的一个格点。
 */
public class RealPriceObservation {

    /** 曲线ID（如 CNY_SWAP、600000、USDCNY、CNY_SWAP_VOL） */
    private String curveId;

    /** 期限天数（如 30、90、365），对应 Series<Integer,Double> 的 key */
    private int tenorDays;

    /**
     * delta（δ，仅波动率曲面使用）。
     * δ = 期权到期时 ITM 的概率，范围 [0, 1]，对应监管 Row D 分桶维度。
     * 非波动率产品此字段为 null。
     */
    private Double delta;

    /** 观测日期 */
    private LocalDate observationDate;

    /** 价格或波动率值（非空且非零才有效） */
    private BigDecimal price;

    /** 价格来源，参见 RfetConstants.SOURCE_* */
    private String source;

    public RealPriceObservation() {
    }

    /** 非波动率产品构造（无 delta） */
    public RealPriceObservation(String curveId, int tenorDays, LocalDate observationDate,
                                BigDecimal price, String source) {
        this.curveId = curveId;
        this.tenorDays = tenorDays;
        this.observationDate = observationDate;
        this.price = price;
        this.source = source;
    }

    /** 波动率曲面构造（含 delta，δ ∈ [0,1]） */
    public RealPriceObservation(String curveId, int tenorDays, Double delta,
                                LocalDate observationDate, BigDecimal price, String source) {
        this.curveId = curveId;
        this.tenorDays = tenorDays;
        this.delta = delta;
        this.observationDate = observationDate;
        this.price = price;
        this.source = source;
    }

    public String getCurveId() { return curveId; }
    public void setCurveId(String curveId) { this.curveId = curveId; }

    public int getTenorDays() { return tenorDays; }
    public void setTenorDays(int tenorDays) { this.tenorDays = tenorDays; }

    public Double getDelta() { return delta; }
    public void setDelta(Double delta) { this.delta = delta; }

    public LocalDate getObservationDate() { return observationDate; }
    public void setObservationDate(LocalDate observationDate) { this.observationDate = observationDate; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
