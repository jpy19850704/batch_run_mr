package com.zcyh.mr.saccr.addon;

import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;

import java.util.Map;

/**
 * 权益（Equity）资产类别 AddOn 计算器（对应文档 4.4 节）。
 *
 * <p>结构与信用类完全对称，将参考主体替换为参考权益（Reference Equity）。
 *
 * <p>公式：
 * <pre>
 *   WEN_k = SF_k × ΣD_i(k)
 *   AddOn(Equity) = sqrt( (Σ ρ_k × WEN_k)² + Σ (1 - ρ_k²) × WEN_k² )
 * </pre>
 */
public final class EquityAddOnCalc {

    private EquityAddOnCalc() {
    }

    /**
     * 参考权益描述信息，用于确定 SF 和 ρ。
     */
    public static class EquityInfo {
        /** 是否指数产品（true = 指数，false = 单一股票） */
        public boolean isIndex;

        public EquityInfo(boolean isIndex) {
            this.isIndex = isIndex;
        }
    }

    /**
     * 计算权益 AddOn。
     *
     * @param netDiPerEquity  key = 参考权益标识，value = 该权益内 ΣD_i（已带符号）
     * @param equityInfoMap   key = 参考权益标识，value = EquityInfo（SF/ρ 查询依据）
     * @return AddOn(Equity)
     */
    public static double calc(Map<String, Double> netDiPerEquity,
                              Map<String, EquityInfo> equityInfoMap) {
        if (equityInfoMap == null) {
            throw new IllegalArgumentException("权益主体信息不能为空");
        }
        double systemicSum = 0.0;
        double idioSum = 0.0;

        for (Map.Entry<String, Double> entry : netDiPerEquity.entrySet()) {
            String equity = entry.getKey();
            double netDi = entry.getValue();
            EquityInfo info = equityInfoMap.get(equity);
            if (info == null) {
                throw new IllegalArgumentException("权益主体 " + equity + " 缺少 EquityInfo");
            }

            double sf = SaccrSupervisoryParams.getSf("Equity", info.isIndex, false, null);
            double rho = SaccrSupervisoryParams.getRho("Equity", info.isIndex);

            double wen = sf * netDi;
            systemicSum += rho * wen;
            idioSum += (1.0 - rho * rho) * wen * wen;
        }

        return Math.sqrt(systemicSum * systemicSum + idioSum);
    }
}
