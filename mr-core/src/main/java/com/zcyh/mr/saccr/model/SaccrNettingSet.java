package com.zcyh.mr.saccr.model;

import java.util.List;

/**
 * SA-CCR 净额结算集合输入模型（对应文档 2.2 节）。
 *
 * <p>一个交易对手可能对应多个净额结算集合，每个集合独立计算 EAD，结果不跨集合净额。
 */
public class SaccrNettingSet {

    /** 净额结算集合唯一标识 */
    public String nettingSetId;

    /** 交易对手标识 */
    public String counterpartyId;

    /** 交易对手类型（用于风险权重查询）：Sovereign / Bank / Corporate / QCCP / NonQCCP */
    public String counterpartyType;

    /** 交易对手外部评级（如 AAA / A+ / BBB+ 等），用于资本计算 */
    public String counterpartyRating;

    // ==================== 保证金协议字段 ====================

    /** 是否有保证金协议（Margined）*/
    public boolean isMargined;

    /**
     * 是否集中清算（通过 CCP 清算）。
     * 影响 MPOR 取值（5天 vs ≥10天）。
     */
    public boolean isCleared;

    /**
     * 是否合格中央对手方（QCCP）。
     * 仅 isCleared=true 时有意义，影响风险权重（2% vs 150%）。
     */
    public boolean isQccp;

    /**
     * 银行是否作为客户（Client）通过清算会员接入 CCP。
     * true = 间接清算；false = 直接清算会员。
     */
    public boolean isClientClearing;

    /**
     * 客户清算是否满足可转移性保护条件（Portability）。
     * 满足 → MPOR=5天，RW=2%；否则按双边处理。
     * 仅 isClientClearing=true 时有效。
     */
    public boolean meetsPortabilityConditions;

    /**
     * 保证金协议类型：Bilateral（双向）/ OneWayBank（单向，银行缴纳）/ None。
     * OneWayBank 按路径 C 处理（等同 Unmargined，但 C 可能为负）。
     */
    public String marginType;

    /** 触发追加保证金的门槛值 TH */
    public double threshold;

    /** 最低转让金额 MTA */
    public double mta;

    /**
     * 净独立抵押品金额 NICA（= 银行收取的 IA − 银行缴纳的 IA）。
     * 银行净收取为正。
     */
    public double nica;

    /**
     * 已收取净抵押品 C（含变动保证金和折价后初始保证金）。
     * 银行方收取为正，缴纳为负。
     * 单向保证金（OneWayBank）时 C 可为负（银行已缴纳的 VM）。
     */
    public double collateralC;

    /**
     * 自定义 MPOR 天数（工作日）。
     * 设为 0 时由引擎按监管规则自动推算：
     * 集中清算=5天，双边日频=10天，低频=10+超出天数。
     */
    public int mporDays;

    /**
     * 是否存在争议历史（Dispute History）。
     * true → MPOR 翻倍（集中清算不适用）。
     */
    public boolean hasDisputeHistory;

    // ==================== 交易列表 ====================

    /** 归属于本净额结算集合的所有交易 */
    public List<SaccrTrade> trades;
}
