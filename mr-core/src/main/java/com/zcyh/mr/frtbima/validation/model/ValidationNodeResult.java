package com.zcyh.mr.frtbima.validation.model;

import com.zcyh.mr.frtbima.validation.common.TrafficLightZone;

import java.math.BigDecimal;

/**
 * 任意维度节点的综合验证结果。
 * 支持交易台、全行或用户自定义的任意聚合节点。
 * 包含回测结果、PLA测试结果和最终区间判定。
 * 规则依据：MAR32.4-32.42，MAR33.45
 *
 * 回测PnL类型说明（MAR32.5-32.6）：
 * - 全行级（BANK_WIDE）：backtestResult 中仅含 ACTUAL 类型的突破明细
 * - 交易台级（DESK）：backtestResult 中同时含 ACTUAL 和 HYPOTHETICAL 类型的突破明细，
 *   同一天两者都突破只计 1 次例外，但明细分别记录
 * - 自定义节点：由调用方决定使用哪种回测类
 */
public class ValidationNodeResult {

    /** 节点ID（可以是交易台ID、全行标识或任意自定义标识） */
    private String nodeId;

    /**
     * 节点类型标注。
     * 典型值：DESK（交易台）、BANK_WIDE（全行）、或用户自定义类型。
     */
    private String nodeType;

    /**
     * 回测结果。
     * 全行级：仅含 ACTUAL 突破明细。
     * 交易台级：含 ACTUAL + HYPOTHETICAL 突破明细（同一天去重计数）。
     * 通过 ExceptionDetail.pnlType 区分突破来源。
     */
    private BacktestResult backtestResult;

    /** PLA 测试结果 */
    private PlaTestResult plaTestResult;

    /**
     * 综合判定区间（取回测和PLA中的较差结果）。
     * 校验等级顺序：RED > AMBER > GREEN
     */
    private TrafficLightZone zone;

    /**
     * 对应的标准法（SA）资本要求，用于计算 Amber 附加系数 k（MAR33.45）。
     * 单位：人民币
     */
    private BigDecimal saCapital;

    public ValidationNodeResult() {
    }

    public ValidationNodeResult(String nodeId, String nodeType) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
    }

    /**
     * 根据回测和PLA测试结果计算综合区间。
     * 规则：取所有非null检验结果区间中最严重的一个。
     * 校验等级顺序：RED > AMBER > GREEN
     */
    public void computeZone() {
        TrafficLightZone result = TrafficLightZone.GREEN;

        // 回测区间
        if (backtestResult != null) {
            result = worst(result, backtestResult.getZone());
        }

        // PLA 区间
        result = worst(result, derivePlaZone());

        this.zone = result;
    }

    /**
     * 从 PLA 测试结果推导交通灯区间。
     */
    private TrafficLightZone derivePlaZone() {
        if (plaTestResult == null) {
            return TrafficLightZone.GREEN;
        }
        String spZone = plaTestResult.getSpearmanZone();
        String ksZone = plaTestResult.getKsZone();

        if ("RED".equals(spZone) || "RED".equals(ksZone)) {
            return TrafficLightZone.RED;
        }
        if ("AMBER".equals(spZone) || "AMBER".equals(ksZone)) {
            return TrafficLightZone.AMBER;
        }
        return TrafficLightZone.GREEN;
    }

    private TrafficLightZone worst(TrafficLightZone a, TrafficLightZone b) {
        if (a == TrafficLightZone.RED || b == TrafficLightZone.RED) {
            return TrafficLightZone.RED;
        }
        if (a == TrafficLightZone.AMBER || b == TrafficLightZone.AMBER) {
            return TrafficLightZone.AMBER;
        }
        return TrafficLightZone.GREEN;
    }

    // ================== 访问器方法 ==================

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
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
