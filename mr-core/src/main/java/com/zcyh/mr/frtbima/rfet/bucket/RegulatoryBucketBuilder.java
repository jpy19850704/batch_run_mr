package com.zcyh.mr.frtbima.rfet.bucket;

import com.zcyh.mr.frtbima.rfet.model.RealPriceObservation;
import com.zcyh.mr.frtbima.rfet.model.RfBucketDefinition;
import com.zcyh.mr.frtbima.rfet.model.RiskFactorBucket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监管桶构建器。
 *
 * <p>对每条实际价格观测 (curveId, tenorDays[, strikeLevel])，遍历 bucketDefinitions
 * 找到满足 {@code containsPoint(tenorDays, strikeLevel)} 的桶，累计计数并记录日期。
 *
 * <p>支持两种桶类型（MAR31.12-14）：
 * <ul>
 *   <li><b>一维桶</b>（利率/权益/外汇/大宗商品即期）：仅按期限 tenorDays 匹配
 *   <li><b>二维桶</b>（波动率曲面）：同时按期限 + 执行价 strikeLevel 匹配
 * </ul>
 *
 * <p>每条观测只归属一个桶（第一个匹配的桶），避免重复计数。
 * 规则依据：MAR31.12-31.14
 */
public class RegulatoryBucketBuilder {

    /**
     * 构建监管桶映射并统计各桶观测数与日期。
     *
     * @param bucketDefinitions RF_CONFIG 桶定义列表
     * @param observations      窗口期内有效实际价格观测列表
     * @return bucketId → RiskFactorBucket 的有序映射（无观测的桶也包含在内，observationCount=0）
     */
    public Map<String, RiskFactorBucket> build(List<RfBucketDefinition> bucketDefinitions,
                                                List<RealPriceObservation> observations) {
        // 初始化所有桶（保证无观测的桶也出现在结果中）
        Map<String, RiskFactorBucket> bucketMap = new LinkedHashMap<>();
        if (bucketDefinitions != null) {
            for (RfBucketDefinition def : bucketDefinitions) {
                String bucketId = def.getBucketId();
                bucketMap.put(bucketId, new RiskFactorBucket(bucketId, def.getCurveId()));
            }
        }

        if (observations == null || observations.isEmpty() || bucketDefinitions == null) {
            return bucketMap;
        }

        // 对每条观测，匹配所属桶（一条观测只归属一个桶）
        for (RealPriceObservation obs : observations) {
            if (obs.getCurveId() == null) {
                continue;
            }
            for (RfBucketDefinition def : bucketDefinitions) {
                if (!def.getCurveId().equals(obs.getCurveId())) {
                    continue;
                }
                // 二维匹配：期限 + delta（非波动率产品 delta 为 null）
                if (def.containsPoint(obs.getTenorDays(), obs.getDelta())) {
                    bucketMap.get(def.getBucketId())
                             .addObservation(obs.getTenorDays(), obs.getObservationDate());
                    break; // 一条观测只归属一个桶
                }
            }
        }
        return bucketMap;
    }
}
