package com.zcyh.mr.frtbima.rfet.checker;

import com.zcyh.mr.frtbima.rfet.bucket.OwnBucketValidator;
import com.zcyh.mr.frtbima.rfet.bucket.RegulatoryBucketBuilder;
import com.zcyh.mr.frtbima.rfet.common.RfetConstants;
import com.zcyh.mr.frtbima.rfet.model.RealPriceObservation;
import com.zcyh.mr.frtbima.rfet.model.RfBucketDefinition;
import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import com.zcyh.mr.frtbima.rfet.model.RiskFactorBucket;
import com.zcyh.mr.frtbima.rfet.validator.RealPriceValidator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFET 主判定器。
 *
 * <p>按 MAR31.12–31.16 依次执行三条规则，任一通过即为 MODELLABLE：
 *
 * <ol>
 *   <li><b>规则1：观测数 ≥ 24（MAR31.12）</b><br>
 *       12个月窗口内，桶内有效实际价格观测数 ≥ 24 条。
 *
 *   <li><b>规则2：每90天段至少4条观测（MAR31.12）</b><br>
 *       在满足规则1的前提下，将12个月窗口按
 *       {@link RfetConstants#OBSERVATION_PERIOD_DAYS}（90天）分段，
 *       每段内的观测数必须 ≥ {@link RfetConstants#MIN_OBSERVATIONS_PER_PERIOD}（4条）。
 *       若任意一段不满足，则规则1+2 联合失败。
 *
 *   <li><b>规则3：Own Bucket 补救（MAR31.15）</b><br>
 *       当规则1或规则2失败时，检查同 curveId 下所有桶的观测总数是否 ≥ 100。
 *       若满足，则补救为 MODELLABLE（{@code passedViaOwnBucket=true}）。
 * </ol>
 *
 * <p>三条规则均失败 → NMRF。
 *
 * <p>调用方（mr-app）从 {@code List<RfetResult>} 构建：
 * <ul>
 *   <li>modellable=true 的桶 → 将 tenorDays 合并到 modellableTenorsByCurve
 *   <li>modellable=false 的桶 → 构建 NmrfBucketMeta，进入 NmrfScenarioRunner
 * </ul>
 */
public class RfetChecker {

    private final RealPriceValidator priceValidator = new RealPriceValidator();
    private final RegulatoryBucketBuilder bucketBuilder = new RegulatoryBucketBuilder();
    private final OwnBucketValidator ownBucketValidator = new OwnBucketValidator();

    /**
     * 执行 RFET 判定。
     *
     * @param bucketDefinitions RF_CONFIG 桶定义列表（由调用方从数据库加载）
     * @param observations      实际价格观测原始数据（全量，含窗口期外的历史记录）
     * @param valuationDate     估值日期
     * @return 每个桶的判定结果列表
     */
    public List<RfetResult> check(List<RfBucketDefinition> bucketDefinitions,
                                  List<RealPriceObservation> observations,
                                  LocalDate valuationDate) {
        List<RfetResult> results = new ArrayList<>();
        if (bucketDefinitions == null || bucketDefinitions.isEmpty()) {
            return results;
        }

        // ① 过滤窗口期内有效观测
        List<RealPriceObservation> validObs = priceValidator.filterValid(observations, valuationDate);

        // 窗口起始日（供规则2分段使用）
        LocalDate windowStart = valuationDate.minusDays(RfetConstants.OBSERVATION_WINDOW_DAYS);

        // ② 构建桶并统计各桶观测数和日期
        Map<String, RiskFactorBucket> bucketMap = bucketBuilder.build(bucketDefinitions, validObs);

        // ③ 逐桶判定（三条规则依次检查）
        for (RfBucketDefinition def : bucketDefinitions) {
            String bucketId = def.getBucketId();
            RiskFactorBucket bucket = bucketMap.get(bucketId);
            int count = (bucket != null) ? bucket.getObservationCount() : 0;

            // 计算各90天段最小观测数
            int minPeriodObs = calcMinPeriodObservations(bucket, windowStart);

            // 规则1：观测数 >= 24
            boolean rule1Pass = count >= RfetConstants.MIN_REAL_PRICES;

            // 规则2：每90天段至少4条观测（仅在规则1通过时有意义）
            boolean rule2Pass = rule1Pass && minPeriodObs >= RfetConstants.MIN_OBSERVATIONS_PER_PERIOD;

            // 规则3：Own Bucket 补救（规则1+2 未全部通过时检查）
            boolean ownBucketPass = false;
            int ownBucketTotal = 0;
            if (!rule2Pass) {
                ownBucketPass = ownBucketValidator.passes(def.getCurveId(), bucketMap);
                ownBucketTotal = ownBucketValidator.totalObservations(def.getCurveId(), bucketMap);
            }

            boolean modellable = rule2Pass || ownBucketPass;
            String reason = buildReason(bucketId, count, minPeriodObs, rule1Pass, rule2Pass,
                    ownBucketPass, ownBucketTotal);

            RfetResult result = new RfetResult(
                    bucketId,
                    def.getCurveId(),
                    def.getRfType(),
                    bucket != null ? bucket.getTenorDays() : java.util.Collections.emptySet(),
                    modellable,
                    ownBucketPass,
                    count,
                    minPeriodObs,
                    reason);
            // 填入桶的范围信息，供 RfetModellableIndex 做范围匹配（非点匹配）
            result.setTenorMin(def.getTenorMin());
            result.setTenorMax(def.getTenorMax());
            result.setDeltaMin(def.getDeltaMin());
            result.setDeltaMax(def.getDeltaMax());
            // 透传 Reduced Set 标签（来自 RF_CONFIG，桶级别）
            result.setReducedSet(def.isReducedSet());
            results.add(result);
        }
        return results;
    }

    // ==================== 私有工具方法 ====================

    /**
     * 计算12个月窗口内各90天分段中观测条数的最小值。
     *
     * <p>将窗口 [windowStart, windowStart+365) 按 OBSERVATION_PERIOD_DAYS（90天）分段，
     * 统计每段内的观测条数，返回最小值。
     *
     * @param bucket      监管桶（含观测日期列表）
     * @param windowStart 观测窗口起始日（= valuationDate - 365天）
     * @return 最小段观测数；若桶为null或无观测则返回 -1
     */
    private int calcMinPeriodObservations(RiskFactorBucket bucket, LocalDate windowStart) {
        if (bucket == null) return -1;
        List<LocalDate> dates = bucket.getSortedObservationDates();
        if (dates.isEmpty()) return -1;

        LocalDate windowEnd = windowStart.plusDays(RfetConstants.OBSERVATION_WINDOW_DAYS);
        int minCount = Integer.MAX_VALUE;

        LocalDate periodStart = windowStart;
        while (periodStart.isBefore(windowEnd)) {
            LocalDate periodEnd = periodStart.plusDays(RfetConstants.OBSERVATION_PERIOD_DAYS);
            if (periodEnd.isAfter(windowEnd)) {
                periodEnd = windowEnd;
            }
            // 统计本段内的观测数
            int count = 0;
            for (LocalDate d : dates) {
                if (!d.isBefore(periodStart) && d.isBefore(periodEnd)) {
                    count++;
                }
            }
            if (count < minCount) {
                minCount = count;
            }
            periodStart = periodEnd;
        }
        return minCount == Integer.MAX_VALUE ? -1 : minCount;
    }

    /**
     * 生成判定说明字符串。
     */
    private String buildReason(String bucketId, int count, int minPeriodObs,
                                boolean rule1Pass, boolean rule2Pass,
                                boolean ownBucketPass, int ownBucketTotal) {
        StringBuilder sb = new StringBuilder();
        sb.append("桶[").append(bucketId).append("] ");

        if (rule2Pass) {
            // 规则1+2 均通过
            sb.append("观测数=").append(count)
              .append("(≥").append(RfetConstants.MIN_REAL_PRICES).append(")")
              .append(", 最小段观测数=").append(minPeriodObs)
              .append("(≥").append(RfetConstants.MIN_OBSERVATIONS_PER_PERIOD).append("/90天)")
              .append(" → MODELLABLE");
        } else if (ownBucketPass) {
            // Own Bucket 补救通过
            sb.append("标准判定失败");
            if (!rule1Pass) {
                sb.append("(观测数=").append(count)
                  .append("<").append(RfetConstants.MIN_REAL_PRICES).append(")");
            } else {
                sb.append("(最小段观测数=").append(minPeriodObs)
                  .append("<").append(RfetConstants.MIN_OBSERVATIONS_PER_PERIOD).append("/90天)");
            }
            sb.append(", Own Bucket 总观测数=").append(ownBucketTotal)
              .append("(≥").append(RfetConstants.OWN_BUCKET_MIN_OBSERVATIONS).append(")")
              .append(" → MODELLABLE(via Own Bucket)");
        } else {
            // 全部失败 → NMRF
            sb.append("观测数=").append(count)
              .append("(需≥").append(RfetConstants.MIN_REAL_PRICES).append(")");
            if (rule1Pass) {
                sb.append(", 最小段观测数=").append(minPeriodObs)
                  .append("(需≥").append(RfetConstants.MIN_OBSERVATIONS_PER_PERIOD).append("/90天)");
            }
            sb.append(", Own Bucket 总观测数=").append(ownBucketTotal)
              .append("(需≥").append(RfetConstants.OWN_BUCKET_MIN_OBSERVATIONS).append(")")
              .append(" → NMRF");
        }
        return sb.toString();
    }
}
