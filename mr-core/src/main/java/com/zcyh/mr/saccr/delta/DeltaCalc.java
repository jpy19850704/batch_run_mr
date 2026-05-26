package com.zcyh.mr.saccr.delta;

import com.zcyh.mr.product.basic.option.OptUtil;
import com.zcyh.mr.saccr.model.SaccrTrade;
import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;

/**
 * SA-CCR 监管 Delta（δ_i）计算器（对应文档步骤 4）。
 *
 * <p>线性产品：δ_i = direction（+1 或 -1）。
 *
 * <p>期权产品：引入 Call/Put 两个维度：
 * <pre>
 *   ω = λ × callSign    其中 callSign = +1(Call) / -1(Put)
 *   δ = ω × Φ(ω × d₁)  其中 d₁ = [ln(P/K) + 0.5σ²T] / [σ√T]
 * </pre>
 * 展开四种情形（δ 均在 [-1, +1]）：
 * <ul>
 *   <li>多头 Call（λ=+1, ω=+1）：δ = Φ(d₁) ∈ (0,1)</li>
 *   <li>空头 Call（λ=-1, ω=-1）：δ = -Φ(-d₁) = Φ(d₁)-1 ∈ (-1,0) → 深度实值时 → -1</li>
 *   <li>多头 Put（λ=+1, ω=-1）：δ = -Φ(-d₁) = Φ(d₁)-1 ∈ (-1,0) → 深度实值时 → -1</li>
 *   <li>空头 Put（λ=-1, ω=+1）：δ = Φ(d₁) ∈ (0,1) → 深度实值时 → +1</li>
 * </ul>
 * σ 取 {@link SaccrSupervisoryParams#getSigma(String)} 规定的监管波动率。
 */
public final class DeltaCalc {

    private DeltaCalc() {
    }

    /**
     * 计算单笔交易的监管 Delta。
     *
     * @param trade   交易输入
     * @param optionT 期权到期时间 T_i（年），仅期权类使用，非期权传入任意值
     * @return δ_i，取值范围 [-1, +1]
     */
    public static double calc(SaccrTrade trade, double optionT) {
        if (!trade.isOption) {
            return trade.direction;
        }
        return calcOptionDelta(trade, optionT);
    }

    private static double calcOptionDelta(SaccrTrade trade, double optionT) {
        double lambda = trade.optionLong ? 1.0 : -1.0;
        // callSign = +1 for CALL（默认），-1 for PUT
        boolean isPut = "PUT".equalsIgnoreCase(trade.optionType);
        double callSign = isPut ? -1.0 : 1.0;
        // ω 决定整体敏感性符号
        double omega = lambda * callSign;

        double p = trade.underlyingPrice;
        double k = trade.strikePrice;

        // T=0 时退化为内在价值：深度实值 → ω，深度虚值 → 0，平值 → ω×0.5
        if (optionT <= 0) {
            boolean itm = isPut ? (p < k) : (p > k);
            boolean atm = (p == k);
            if (itm) return omega;
            if (atm) return omega * 0.5;
            return 0.0;
        }

        if (p <= 0 || k <= 0) {
            throw new IllegalArgumentException(
                    "交易 " + trade.tradeId + " 的标的价格 P 和行权价 K 必须为正数");
        }

        double sigma = SaccrSupervisoryParams.getSigma(trade.assetClass);
        double d1 = (Math.log(p / k) + 0.5 * sigma * sigma * optionT)
                / (sigma * Math.sqrt(optionT));

        return omega * OptUtil.cdf(omega * d1);
    }
}
