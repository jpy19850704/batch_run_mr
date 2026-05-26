package com.zcyh.mr.frtbima.rfet.bucket;

import com.zcyh.mr.frtbima.rfet.common.RfetConstants;
import com.zcyh.mr.frtbima.rfet.model.RiskFactorBucket;

import java.util.Map;

/**
 * Own Bucket（自定义桶）验证器（MAR31.15）。
 *
 * <p>当监管桶未通过标准 RFET（观测数 < 24 或存在超过 90 天的观测间隔）时，
 * 银行可使用 Own Bucket 规则作为补救：
 * 若同一 curveId 下所有监管桶的观测总数 &gt;= 100，则该桶仍可判定为 MODELLABLE。
 *
 * <p>Own Bucket 的业务含义：
 * 对于流动性较低的风险因子，单个期限桶的成交可能不足 24 笔，
 * 但若同一曲线整体成交活跃（跨期限总计 &gt;= 100 笔），
 * 监管视其为"基本可建模"，允许适用内模法。
 *
 * <p>规则依据：MAR31.15–31.16
 */
public class OwnBucketValidator {

    /**
     * 判断指定桶是否可通过 Own Bucket 规则补救为 MODELLABLE。
     *
     * <p>判定逻辑：
     * 汇总 {@code allBuckets} 中所有与该 {@code curveId} 相同的桶的观测数，
     * 若总和 &gt;= {@link RfetConstants#OWN_BUCKET_MIN_OBSERVATIONS}（100），则通过。
     *
     * @param curveId    失败桶的曲线ID
     * @param allBuckets 本次 RFET 判定的全部桶（bucketId → RiskFactorBucket）
     * @return true 表示可通过 Own Bucket 补救
     */
    public boolean passes(String curveId, Map<String, RiskFactorBucket> allBuckets) {
        if (curveId == null || allBuckets == null || allBuckets.isEmpty()) {
            return false;
        }
        int totalCount = 0;
        for (RiskFactorBucket bucket : allBuckets.values()) {
            if (curveId.equals(bucket.getCurveId())) {
                totalCount += bucket.getObservationCount();
            }
        }
        return totalCount >= RfetConstants.OWN_BUCKET_MIN_OBSERVATIONS;
    }

    /**
     * 计算同 curveId 下所有桶的观测总数（供日志和结果字段使用）。
     *
     * @param curveId    曲线ID
     * @param allBuckets 全部桶映射
     * @return 该 curveId 的观测总数
     */
    public int totalObservations(String curveId, Map<String, RiskFactorBucket> allBuckets) {
        if (curveId == null || allBuckets == null) return 0;
        int total = 0;
        for (RiskFactorBucket bucket : allBuckets.values()) {
            if (curveId.equals(bucket.getCurveId())) {
                total += bucket.getObservationCount();
            }
        }
        return total;
    }
}
