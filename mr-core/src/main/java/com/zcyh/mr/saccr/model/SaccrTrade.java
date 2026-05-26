package com.zcyh.mr.saccr.model;

import java.time.LocalDate;

/**
 * SA-CCR 单笔交易输入模型（对应文档 2.1 节）。
 *
 * <p>所有货币名义本金须在传入前折算为报告货币。
 */
public class SaccrTrade {

    /** 交易唯一标识 */
    public String tradeId;

    /** 资产类别：IR / FX / Credit / Equity / Commodity */
    public String assetClass;

    /**
     * 交易方向：+1 多头（支付固定/买入外币/保护买方）；-1 空头（收取固定/卖出外币/保护卖方）。
     * 利率互换约定：支付浮动（收取固定）= +1。
     */
    public int direction;

    /** 名义本金（已折算为报告货币） */
    public double notional;

    /** 计价货币（ISO 4217，如 CNY / USD） */
    public String currency;

    /** 衍生品标的资产起始日期（S_i） */
    public LocalDate startDate;

    /** 衍生品标的资产到期日期（E_i） */
    public LocalDate endDate;

    /**
     * 期权到期日期（T_i），仅期权类产品填写。
     * 非期权类设为 null。
     */
    public LocalDate optionExpiry;

    /**
     * 产品类型：Swap / Forward / Option / CDS / TRS 等。
     * 用于驱动 S_i/E_i/T_i 的取值逻辑。
     */
    public String productType;

    /** 当前市场价值 MTM（V_i），以报告货币计，可为负 */
    public double mtmValue;

    /** 是否期权类产品 */
    public boolean isOption;

    /**
     * 期权方向：true = 多头（λ=+1）；false = 空头（λ=-1）。
     * 仅 isOption=true 时有效。
     */
    public boolean optionLong;

    /**
     * 期权类型：{@code "CALL"} 或 {@code "PUT"}，大小写不敏感。
     * 默认 "CALL"（null / 空串均视为 CALL）。
     * <p>Call 和 Put 对 Delta 符号的影响：
     * <ul>
     *   <li>多头 Call / 空头 Put → ω = +λ，Delta 为正（对标的价格正敏感）</li>
     *   <li>空头 Call / 多头 Put → ω = -λ，Delta 为负（对标的价格负敏感）</li>
     * </ul>
     */
    public String optionType;

    /** 行权价 K，仅期权类适用 */
    public double strikePrice;

    /** 标的当前价格 P，仅期权/权益/商品类适用 */
    public double underlyingPrice;

    // ---- 信用/权益类专用 ----

    /**
     * 参考主体标识（Reference Entity）。
     * 信用类：CDS 参考债务人；权益类：标的公司或指数代码。
     */
    public String referenceEntity;

    /**
     * 信用质量输入（仅信用类使用）。
     * <p>单一主体（isIndex=false）：传外部评级（AAA/AA/A/BBB/BB/B/CCC，支持 +/- 变体）。
     * <p>信用指数（isIndex=true）：传 IG 或 SG。
     */
    public String creditRating;

    /**
     * 是否指数产品。
     * 信用类（CDS 指数）和权益类（指数衍生品）均使用此字段。
     */
    public boolean isIndex;

    // ---- 商品类专用 ----

    /**
     * 商品桶（Commodity Bucket）：Power / Energy / Metal / Agriculture 等。
     * 用于区分监管因子（电力 40% vs 其他 18%）和跨桶聚合。
     */
    public String commodityBucket;

    /**
     * 商品具体品种标识（对冲集合划分用，如 WTI / Gold）。
     * 当 commodityBucket 内有多个品种时，同品种可净额。
     */
    public String commodityType;

    /** 合约数量 Q_i，权益类（股数）和商品类（物理单位）使用 */
    public double quantity;

    /** 货币对标识（外汇类使用，如 USD/CNY） */
    public String currencyPair;
}
