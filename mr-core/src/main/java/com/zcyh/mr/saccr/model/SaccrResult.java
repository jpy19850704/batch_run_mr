package com.zcyh.mr.saccr.model;

/**
 * SA-CCR 单个净额结算集合的计算输出。
 *
 * <p>包含五层计量的全部中间值和最终结果，便于审计和落库。
 */
public class SaccrResult {

    /** 净额结算集合 ID */
    public String nettingSetId;

    /** 净额模式：NETTING_SET / TRADE */
    public String nettingMode;

    /** 交易对手 ID */
    public String counterpartyId;

    /** 交易笔数 */
    public int tradeCount;

    /** 保证金协议类型 */
    public String marginType;

    // ==================== 第三层中间值 ====================

    /** 净额结算集合内所有交易 MTM 之和 ΣV_i */
    public double sumMtm;

    /** 净收取抵押品 C */
    public double collateralC;

    /** TH 折 CNY 后金额 */
    public double thresholdCny;

    /** MTA 折 CNY 后金额 */
    public double mtaCny;

    /** NICA 折 CNY 后金额 */
    public double nicaCny;

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

}
