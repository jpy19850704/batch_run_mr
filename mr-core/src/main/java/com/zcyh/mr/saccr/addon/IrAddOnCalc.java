package com.zcyh.mr.saccr.addon;

import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 利率（IR）资产类别 AddOn 计算器（对应文档 4.1 节）。
 *
 * <p>分组规则：按货币分对冲集合，货币内按到期时间 E_i 分三个期限桶。
 * <ul>
 *   <li>桶 1：E_i ≤ 1 年</li>
 *   <li>桶 2：1 < E_i ≤ 5 年</li>
 *   <li>桶 3：E_i > 5 年</li>
 * </ul>
 *
 * <p>桶间相关系数：ρ₁₂=ρ₂₃=0.70，ρ₁₃=0.30。
 * <p>跨货币：不允许净额，各货币 AddOn 直接相加。
 */
public final class IrAddOnCalc {

    private IrAddOnCalc() {
    }

    /**
     * 计算利率 AddOn。
     *
     * @param diPerCurrencyBucket key = "货币_桶号"（如 "CNY_1"），value = 该桶的 ΣD_i
     * @return AddOn(IR)
     */
    public static double calc(Map<String, Double> diPerCurrencyBucket) {
        // 按货币分组
        Map<String, double[]> currencyBucketMap = new HashMap<>();
        for (Map.Entry<String, Double> entry : diPerCurrencyBucket.entrySet()) {
            String[] parts = entry.getKey().split("_", 2);
            String ccy = parts[0];
            int bucket = Integer.parseInt(parts[1]);
            double[] buckets = currencyBucketMap.computeIfAbsent(ccy, k -> new double[4]);
            buckets[bucket] += entry.getValue();
        }

        double totalAddOn = 0.0;
        for (Map.Entry<String, double[]> entry : currencyBucketMap.entrySet()) {
            totalAddOn += calcPerCurrency(entry.getValue());
        }
        return totalAddOn;
    }

    /**
     * 计算单货币的 AddOn。
     *
     * @param buckets 索引1=桶1，索引2=桶2，索引3=桶3（索引0不用）
     */
    static double calcPerCurrency(double[] buckets) {
        double en1 = buckets[1];
        double en2 = buckets[2];
        double en3 = buckets[3];
        double rho12 = SaccrSupervisoryParams.IR_BUCKET_CORR_12;
        double rho23 = SaccrSupervisoryParams.IR_BUCKET_CORR_23;
        double rho13 = SaccrSupervisoryParams.IR_BUCKET_CORR_13;

        // AddOn(ccy) = SF × sqrt(EN₁² + EN₂² + EN₃² + 2ρ₁₂·EN₁·EN₂ + 2ρ₂₃·EN₂·EN₃ + 2ρ₁₃·EN₁·EN₃)
        double variance = en1 * en1 + en2 * en2 + en3 * en3
                + 2 * rho12 * en1 * en2
                + 2 * rho23 * en2 * en3
                + 2 * rho13 * en1 * en3;

        // variance 可能因负 D_i 而为负（极端情形），取 max(0) 后开方
        return SaccrSupervisoryParams.SF_IR * Math.sqrt(Math.max(0.0, variance));
    }

    /**
     * 按 E_i（年）判断期限桶编号（1/2/3）。
     */
    public static int getBucket(double endYears) {
        if (endYears <= 1.0) return 1;
        if (endYears <= 5.0) return 2;
        return 3;
    }

    /**
     * 构建 diPerCurrencyBucket map 的 key。
     */
    public static String buildKey(String currency, int bucket) {
        return currency + "_" + bucket;
    }

    /**
     * 便捷方法：直接从交易粒度数据计算 AddOn。
     *
     * @param diList 元素为 [currency, endYears, D_i] 的列表
     */
    public static double calcFromTrades(List<double[]> diList, List<String> currencies) {
        Map<String, Double> bucketMap = new HashMap<>();
        for (int i = 0; i < diList.size(); i++) {
            double[] item = diList.get(i);
            String ccy = currencies.get(i);
            double endYears = item[0];
            double di = item[1];
            int bucket = getBucket(endYears);
            String key = buildKey(ccy, bucket);
            bucketMap.merge(key, di, Double::sum);
        }
        return calc(bucketMap);
    }
}
