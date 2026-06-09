package com.zcyh.mr.frtbima.rfet.bucket;

import com.zcyh.mr.frtbima.rfet.model.RfetResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RFET 可建模索引，供 SubsetScenarioRunner 做历史情景范围过滤。
 *
 * <p><b>设计说明</b>：RFET 判定的粒度是监管桶（tenorMin~tenorMax）。
 * 一旦桶通过判定，桶范围内的<b>所有</b>期限点均视为可建模，
 * 而不仅限于 RFET 实际观测到的样本 tenor 点。
 * 本类封装该"范围包含"语义，替代原先的点集合（{@code Map<String, Set<Integer>>}）匹配。
 *
 * <p>用法：
 * <pre>
 *   RfetModellableIndex index = RfetModellableIndex.build(rfetResults);
 *   // 一维（非波动率）：tenor 是否落在某可建模桶的期限范围内
 *   boolean ok = index.isModellable("IR_SPOT", "CNY_SWAP", 365);
 *   // 二维（波动率曲面）：(tenor, delta) 是否落在某可建模桶的范围内
 *   boolean ok = index.isModellable("EQ_VOL", "CSI300_VOL", 180, 0.25);
 *   // 曲线级：该 curveId 是否存在任意可建模桶（用于 FX/Vol 整曲线替换）
 *   boolean ok = index.hasAnyModellableBucket("FX_SPOT", "USDCNY_FWD");
 * </pre>
 */
public class RfetModellableIndex {

    /** curveType|curveId → 该曲线所有可建模桶的结果列表 */
    private final Map<String, List<RfetResult>> index;

    private RfetModellableIndex(Map<String, List<RfetResult>> index) {
        this.index = index;
    }

    /**
     * 从 RFET 判定结果列表构建索引（仅收录 modellable=true 的桶）。
     *
     * @param results RfetChecker.check() 的完整输出
     * @return 可建模索引
     */
    public static RfetModellableIndex build(List<RfetResult> results) {
        Map<String, List<RfetResult>> map = new HashMap<>();
        if (results != null) {
            for (RfetResult r : results) {
                if (r.isModellable()) {
                    map.computeIfAbsent(indexKey(r.getRfType(), r.getCurveId()), k -> new ArrayList<>()).add(r);
                }
            }
        }
        return new RfetModellableIndex(map);
    }

    /**
     * 判断 (curveId, tenorDays) 是否属于某个可建模桶的期限范围。
     * 适用于一维桶（IR_SPOT / FX_SPOT / COMM_SPOT / EQ_SPOT / CREDIT_SPOT）。
     *
     * @param rfType         风险因子类型
     * @param curveId        曲线ID
     * @param tenorDays      期限天数
     * @param reducedSetOnly true=仅匹配 Reduced Set 桶（REDUCED 情景用）；false=匹配全部可建模桶
     * @return true 表示该期限点可建模
     */
    public boolean isModellable(String rfType, String curveId, int tenorDays, boolean reducedSetOnly) {
        List<RfetResult> buckets = index.get(indexKey(rfType, curveId));
        if (buckets == null) return false;
        for (RfetResult r : buckets) {
            if (reducedSetOnly && !r.isReducedSet()) continue;
            if (tenorDays >= r.getTenorMin() && tenorDays <= r.getTenorMax()) {
                return true;
            }
        }
        return false;
    }

    /** 等价于 isModellable(rfType, curveId, tenorDays, false)，默认 Full Set 匹配 */
    public boolean isModellable(String rfType, String curveId, int tenorDays) {
        return isModellable(rfType, curveId, tenorDays, false);
    }

    /**
     * 判断 (curveId, tenorDays, delta) 是否属于某个可建模桶的范围。
     * 适用于二维桶（*_VOL 类型，到期日 × delta）。
     *
     * @param rfType         风险因子类型
     * @param curveId        曲线ID
     * @param tenorDays      到期日天数（Row C）
     * @param delta          ITM 概率（Row D）
     * @param reducedSetOnly true=仅匹配 Reduced Set 桶
     * @return true 表示该点可建模
     */
    public boolean isModellable(String rfType, String curveId, int tenorDays, Double delta, boolean reducedSetOnly) {
        if (delta == null || delta.isNaN() || delta.isInfinite()) {
            return false;
        }
        List<RfetResult> buckets = index.get(indexKey(rfType, curveId));
        if (buckets == null) return false;
        for (RfetResult r : buckets) {
            if (reducedSetOnly && !r.isReducedSet()) continue;
            // 期限维度
            if (tenorDays < r.getTenorMin() || tenorDays > r.getTenorMax()) continue;
            // delta 维度（二维桶）
            Double dMin = r.getDeltaMin();
            Double dMax = r.getDeltaMax();
            if (dMin != null && dMax != null) {
                if (delta < dMin) continue;
                if (dMax < 1.0) {
                    if (delta >= dMax) continue;
                } else {
                    if (delta > dMax) continue;
                }
            }
            return true;
        }
        return false;
    }

    /** 等价于 isModellable(rfType, curveId, tenorDays, delta, false)，默认 Full Set 匹配 */
    public boolean isModellable(String rfType, String curveId, int tenorDays, Double delta) {
        return isModellable(rfType, curveId, tenorDays, delta, false);
    }

    /**
     * 判断指定 curveId 是否存在任意可建模桶（曲线级别检查）。
     * 用于 FX spot 和 Vol 整曲线替换场景，不做期限/delta 细粒度判断。
     *
     * @param rfType         风险因子类型
     * @param curveId        曲线ID
     * @param reducedSetOnly true=仅检查 Reduced Set 桶
     * @return true 表示该曲线至少有一个符合条件的可建模桶
     */
    public boolean hasAnyModellableBucket(String rfType, String curveId, boolean reducedSetOnly) {
        List<RfetResult> buckets = index.get(indexKey(rfType, curveId));
        if (buckets == null) return false;
        if (!reducedSetOnly) return !buckets.isEmpty();
        for (RfetResult r : buckets) {
            if (r.isReducedSet()) return true;
        }
        return false;
    }

    /** 等价于 hasAnyModellableBucket(rfType, curveId, false)，默认 Full Set */
    public boolean hasAnyModellableBucket(String rfType, String curveId) {
        return hasAnyModellableBucket(rfType, curveId, false);
    }

    /**
     * 返回指定 curveId 的所有可建模桶结果（只读）。
     */
    public List<RfetResult> getBuckets(String rfType, String curveId) {
        List<RfetResult> buckets = index.get(indexKey(rfType, curveId));
        return buckets != null ? Collections.unmodifiableList(buckets) : Collections.emptyList();
    }

    /**
     * 判断索引中所有可建模桶是否均属于 Reduced Set。
     *
     * <p>如果全部桶都是 reducedSet=true，则 NORMAL_FULL 和 NORMAL_REDUCED
     * 的冲击因子集完全相同，重定价结果一致，可跳过 NORMAL_REDUCED 批次。
     *
     * @return true=所有桶都在 Reduced Set 中；false=存在非 Reduced Set 桶；索引为空时返回 true
     */
    public boolean isAllReducedSet() {
        if (index.isEmpty()) return true;
        for (List<RfetResult> buckets : index.values()) {
            for (RfetResult r : buckets) {
                if (!r.isReducedSet()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String indexKey(String rfType, String curveId) {
        String safeRfType = normalize(rfType);
        String safeCurveId = normalize(curveId);
        if (safeRfType == null || safeCurveId == null) {
            throw new IllegalArgumentException("RFET 可建模索引缺少 curve_type 或 curve_id");
        }
        return safeRfType + "|" + safeCurveId;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }
}
