package com.zcyh.mr.saccr.addon;

import com.zcyh.mr.saccr.params.SaccrSupervisoryParams;

import java.util.*;

/**
 * 大宗商品（Commodity）资产类别 AddOn 计算器（对应文档 4.5 节）。
 *
 * <p>分组规则：按商品类型（Bucket）分桶，桶内按具体品种分对冲集合。
 *
 * <p>桶内聚合（单因子模型）：
 * <pre>
 *   WEN_k = SF_k × ΣD_i(k)
 *   AddOn(bucket) = sqrt( (Σ ρ × WEN_k)² + Σ (1-ρ²) × WEN_k² )
 * </pre>
 *
 * <p>跨桶聚合：取绝对值相加，无跨桶净额。
 * <pre>
 *   AddOn(Commodity) = Σ AddOn(bucket)
 * </pre>
 */
public final class CommodityAddOnCalc {

    private CommodityAddOnCalc() {
    }

    /**
     * 计算大宗商品 AddOn。
     *
     * @param diPerBucketType key = "商品桶_品种"（如 "Power_WTI"），value = 该品种内 ΣD_i
     * @return AddOn(Commodity)
     */
    public static double calc(Map<String, Double> diPerBucketType) {
        // 按桶分组：桶 → (品种 → ΣD_i)
        Map<String, Map<String, Double>> bucketMap = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : diPerBucketType.entrySet()) {
            String[] parts = entry.getKey().split("_", 2);
            String bucket = parts[0];
            String type = parts.length > 1 ? parts[1] : "default";
            bucketMap.computeIfAbsent(bucket, k -> new LinkedHashMap<>())
                    .merge(type, entry.getValue(), Double::sum);
        }

        double total = 0.0;
        for (Map.Entry<String, Map<String, Double>> entry : bucketMap.entrySet()) {
            String bucket = entry.getKey();
            Map<String, Double> typeMap = entry.getValue();
            total += calcBucket(bucket, typeMap);
        }
        return total;
    }

    /**
     * 计算单个桶内的 AddOn（单因子模型）。
     */
    static double calcBucket(String bucket, Map<String, Double> netDiPerType) {
        double sf = SaccrSupervisoryParams.getSf("Commodity", false, false, bucket);
        double rho = SaccrSupervisoryParams.RHO_COMMODITY_INTRA;

        double systemicSum = 0.0;
        double idioSum = 0.0;

        for (double netDi : netDiPerType.values()) {
            double wen = sf * netDi;
            systemicSum += rho * wen;
            idioSum += (1.0 - rho * rho) * wen * wen;
        }

        return Math.sqrt(systemicSum * systemicSum + idioSum);
    }

    /**
     * 构建 diPerBucketType map 的 key。
     */
    public static String buildKey(String bucket, String commodityType) {
        return bucket + "_" + commodityType;
    }
}
