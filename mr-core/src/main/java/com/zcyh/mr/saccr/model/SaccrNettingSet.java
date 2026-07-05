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

    /** 净额模式：NETTING_SET / TRADE */
    public String nettingMode;

    /** 交易对手标识 */
    public String counterpartyId;

    // ==================== 保证金协议字段 ====================

    /** 是否有保证金协议（Margined）*/
    public boolean isMargined;

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
     * 显式 MPOR 天数（工作日），由输入侧按监管下限和行内规则确定。
     */
    public int mporDays;

    // ==================== 交易列表 ====================

    /** 归属于本净额结算集合的所有交易 */
    public List<SaccrTrade> trades;
}
