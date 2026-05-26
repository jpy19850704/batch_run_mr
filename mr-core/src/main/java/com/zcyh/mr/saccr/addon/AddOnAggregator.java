package com.zcyh.mr.saccr.addon;

/**
 * 五类资产 AddOn 合计聚合器（对应文档 4.6 节）。
 *
 * <p>公式：AddOn_aggregate = AddOn(IR) + AddOn(FX) + AddOn(Credit) + AddOn(Equity) + AddOn(Commodity)
 */
public final class AddOnAggregator {

    private AddOnAggregator() {
    }

    /**
     * 汇总五类 AddOn。
     *
     * @param addonIr        利率 AddOn
     * @param addonFx        外汇 AddOn
     * @param addonCredit    信用 AddOn
     * @param addonEquity    权益 AddOn
     * @param addonCommodity 大宗商品 AddOn
     * @return AddOn_aggregate（非负）
     */
    public static double aggregate(double addonIr, double addonFx, double addonCredit,
                                   double addonEquity, double addonCommodity) {
        return addonIr + addonFx + addonCredit + addonEquity + addonCommodity;
    }
}
