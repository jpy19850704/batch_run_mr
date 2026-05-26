package com.zcyh.mr.saccr.addon;

import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;

import java.util.Map;

/**
 * 外汇（FX）资产类别 AddOn 计算器（对应文档 4.2 节）。
 *
 * <p>分组规则：按货币对（Currency Pair）分对冲集合，每对独立净额后取绝对值。
 * <p>跨货币对：不允许净额，各货币对 AddOn 直接相加。
 *
 * <p>公式：AddOn(FX) = Σ SF_FX × |Σ D_i(ccy pair)|
 */
public final class FxAddOnCalc {

    private FxAddOnCalc() {
    }

    /**
     * 计算外汇 AddOn。
     *
     * @param diPerCcyPair key = 货币对标识（如 "USD/CNY"），value = 该货币对内 ΣD_i
     * @return AddOn(FX)
     */
    public static double calc(Map<String, Double> diPerCcyPair) {
        double total = 0.0;
        for (double netDi : diPerCcyPair.values()) {
            total += SaccrSupervisoryParams.SF_FX * Math.abs(netDi);
        }
        return total;
    }
}
