package com.zcyh.mr.saccr.rc;

/**
 * SA-CCR 替代成本（RC）计算器（对应文档 5.1 节）。
 *
 * <p>提供三条计算路径：
 * <ul>
 *   <li>路径 A：无保证金协议（Unmargined）</li>
 *   <li>路径 B：有保证金协议（Margined，双向）</li>
 *   <li>路径 C：单向保证金（银行只缴纳），等同路径 A 但 C 可能为负</li>
 * </ul>
 */
public final class RcCalc {

    private RcCalc() {
    }

    /**
     * 路径 A：无保证金协议。
     *
     * <p>RC = max(ΣV_i - C, 0)
     *
     * @param sumMtm     净额结算集合内所有交易 MTM 之和
     * @param collateralC 净收取抵押品（银行收取为正，单向时可为负）
     * @return RC（非负）
     */
    public static double calcUnmargined(double sumMtm, double collateralC) {
        return Math.max(sumMtm - collateralC, 0.0);
    }

    /**
     * 路径 B：有保证金协议（双向）。
     *
     * <p>RC = max(ΣV_i - C, TH + MTA - NICA, 0)
     *
     * <p>第二项（TH + MTA - NICA）为最差情景下保证金追加之前可能暴露的最大敞口，
     * 保证 RC 不低于此值。
     *
     * @param sumMtm     净额结算集合内所有交易 MTM 之和
     * @param collateralC 净收取抵押品 C
     * @param threshold   保证金触发阈值 TH
     * @param mta         最低转让金额 MTA
     * @param nica        净独立抵押品金额 NICA（银行净收取为正）
     * @return RC（非负）
     */
    public static double calcMargined(double sumMtm, double collateralC,
                                      double threshold, double mta, double nica) {
        double term1 = sumMtm - collateralC;
        double term2 = threshold + mta - nica;
        return Math.max(Math.max(term1, term2), 0.0);
    }

    /**
     * 路径 C：单向保证金（银行只缴纳，不接收）。
     *
     * <p>处理等同路径 A，但 collateralC 为负（银行已缴纳的变动保证金）。
     *
     * @param sumMtm      净额结算集合内所有交易 MTM 之和
     * @param collateralC 净收取抵押品（银行已缴纳时为负值）
     * @return RC（非负）
     */
    public static double calcOneWayBank(double sumMtm, double collateralC) {
        // 等同路径 A
        return calcUnmargined(sumMtm, collateralC);
    }
}
