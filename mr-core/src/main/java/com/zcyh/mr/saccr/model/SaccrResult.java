package com.zcyh.mr.saccr.model;

/**
 * SA-CCR 单个净额结算集合的计算输出。
 *
 * <p>包含五层计量的全部中间值和最终结果，便于审计和落库。
 */
public class SaccrResult {

    /** 净额结算集合 ID */
    public String nettingSetId;

    /** 交易对手 ID */
    public String counterpartyId;

    // ==================== 第三层中间值 ====================

    /** 净额结算集合内所有交易 MTM 之和 ΣV_i */
    public double sumMtm;

    /** 净收取抵押品 C */
    public double collateralC;

    /** 替代成本 RC */
    public double rc;

    /** 乘数 multiplier（[0.05, 1.0]） */
    public double multiplier;

    // ==================== 第二层：各资产类别 AddOn ====================

    /** 利率 AddOn */
    public double addonIr;

    /** 外汇 AddOn */
    public double addonFx;

    /** 信用 AddOn */
    public double addonCredit;

    /** 权益 AddOn */
    public double addonEquity;

    /** 大宗商品 AddOn */
    public double addonCommodity;

    /** 五类 AddOn 合计 */
    public double addonAggregate;

    // ==================== 第三层：PFE ====================

    /** 潜在未来风险暴露 PFE = multiplier × AddOn_aggregate */
    public double pfe;

    // ==================== 第四层：EAD ====================

    /** 风险敞口 EAD = 1.4 × (RC + PFE) */
    public double ead;

    // ==================== 第五层：资本（可选） ====================

    /** 适用风险权重 RW（如未计算资本则为 0） */
    public double riskWeight;

    /** CCR 风险加权资产 RWA = EAD × RW */
    public double rwaCcr;

    /** CCR 资本要求 Capital = RWA × 8% */
    public double capitalCcr;

    /** 是否已输出资本层结果 */
    public boolean capitalCalculated;
}
