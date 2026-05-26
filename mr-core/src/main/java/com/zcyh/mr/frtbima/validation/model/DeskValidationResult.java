package com.zcyh.mr.frtbima.validation.model;

import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;

import java.math.BigDecimal;

/**
 * 单个交易台的综合验证结果。
 *
 * @deprecated 已被 {@link ValidationNodeResult} 替代。
 * ValidationNodeResult 支持任意维度节点（交易台、全行或自定义分组），
 * 通过 nodeId + nodeType 灵活标识，并内置综合 zone 合并逻辑。
 * 本类保留仅为向后兼容，新代码请使用 ValidationNodeResult。
 */
@Deprecated
public class DeskValidationResult {

    /** 交易台ID */
    private String deskId;

    /** 回测结果 */
    private BacktestResult backtestResult;

    /** PLA 测试结果 */
    private PlaTestResult plaTestResult;

    /**
     * 综合判定区间（取回测和PLA中的较差结果）。
     * 任一为RED则整体为RED；任一为AMBER则整体为AMBER；均GREEN才为GREEN
     */
    private TrafficLightZone zone;

    /**
     * 对应的标准法（SA）资本要求，用于计算 Amber 附加系数 k（MAR33.45）。
     * 单位：人民币
     */
    private BigDecimal saCapital;

    public DeskValidationResult() {
    }

    /**
     * 转换为新的 ValidationNodeResult。
     *
     * @return 对应的 ValidationNodeResult（nodeType=DESK）
     */
    public ValidationNodeResult toNodeResult() {
        ValidationNodeResult result = new ValidationNodeResult(deskId, "DESK");
        result.setBacktestResult(backtestResult);
        result.setPlaTestResult(plaTestResult);
        result.setZone(zone);
        result.setSaCapital(saCapital);
        return result;
    }

    public String getDeskId() {
        return deskId;
    }

    public void setDeskId(String deskId) {
        this.deskId = deskId;
    }

    public BacktestResult getBacktestResult() {
        return backtestResult;
    }

    public void setBacktestResult(BacktestResult backtestResult) {
        this.backtestResult = backtestResult;
    }

    public PlaTestResult getPlaTestResult() {
        return plaTestResult;
    }

    public void setPlaTestResult(PlaTestResult plaTestResult) {
        this.plaTestResult = plaTestResult;
    }

    public TrafficLightZone getZone() {
        return zone;
    }

    public void setZone(TrafficLightZone zone) {
        this.zone = zone;
    }

    public BigDecimal getSaCapital() {
        return saCapital;
    }

    public void setSaCapital(BigDecimal saCapital) {
        this.saCapital = saCapital;
    }
}
