package com.zcyh.mr.frtbsa.drc;

import com.zcyh.mr.product.basic.frtb.DrcDetail;
import com.zcyh.mr.core.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * DRC资本计算器 — 统一处理NS、NCTP、CTP三类产品
 *
 * <p>
 * 包含DRC计算的全部数据结构和计算逻辑：
 * <ol>
 * <li>按风险因子分组，计算JTD净额</li>
 * <li>分离净多头和净空头</li>
 * <li>按对冲组汇总空头（保留优先级分层），确定分解参数(PA/PB/PC/PD)</li>
 * <li>按Bucket汇总：未加权净JTD → HBR，加权JTD → DRC公式</li>
 * <li>计算PDER偏导数，分解资本到交易级</li>
 * </ol>
 *
 * <p>
 * 对冲规则（FRTB MAR22.16）：
 * 同一义务人在同一Bucket内，低优先级（次级/劣后级）的空头可以对冲高优先级（优先级）的多头，
 * 反向不成立。通过{@code TreeMap.tailMap(seniority)}实现单向过滤。
 *
 * <p>
 * 核心公式：
 * <ul>
 * <li>HBR = NetJTD_Long / (NetJTD_Long + |NetJTD_Short|)</li>
 * <li>DRC = max(WtdJTD_Long - HBR × WtdJTD_Short, 0)</li>
 * </ul>
 */
public class DrcCalculator {
    private static final Logger log = LoggerFactory.getLogger(DrcCalculator.class);
    private static final int MAX_NULL_JTD_CNY_LOG = 10;

    private DrcCalculator() {
    }

    // ==================== 数据结构 ====================

    /** 风险因子分组键：同一键值下的JTD进行净额轧差 */
    static class RiskFactorKey {
        final String securityType;
        final String securityId;
        final String legalEntity;
        final String drcBucket;
        final String jtdType;
        final int seniority;
        final double riskWeight;

        RiskFactorKey(String securityType, String securityId, String legalEntity, String drcBucket,
                String jtdType, int seniority, double riskWeight) {
            this.securityType = securityType;
            this.securityId = normalizeSecurityId(securityType, securityId);
            this.legalEntity = legalEntity;
            this.drcBucket = drcBucket;
            this.jtdType = jtdType;
            this.seniority = seniority;
            this.riskWeight = riskWeight;
        }

        HedgeGroupKey toHedgeGroupKey() {
            return new HedgeGroupKey(legalEntity, drcBucket, securityId);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof RiskFactorKey))
                return false;
            RiskFactorKey k = (RiskFactorKey) o;
            return seniority == k.seniority
                    && Double.compare(k.riskWeight, riskWeight) == 0
                    && Objects.equals(securityType, k.securityType)
                    && Objects.equals(securityId, k.securityId)
                    && Objects.equals(legalEntity, k.legalEntity)
                    && Objects.equals(drcBucket, k.drcBucket)
                    && Objects.equals(jtdType, k.jtdType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(securityType, securityId, legalEntity, drcBucket, jtdType, seniority, riskWeight);
        }
    }

    /**
     * 对冲组分组键
     * <p>
     * 根据FRTB MAR22.16，同一义务人在同一Bucket内可跨优先级对冲，
     * 因此仅按(legalEntity, drcBucket)分组。
     */
    static class HedgeGroupKey {
        final String legalEntity;
        final String drcBucket;
        final String securityId;

        HedgeGroupKey(String legalEntity, String drcBucket, String securityId) {
            this.legalEntity = legalEntity;
            this.drcBucket = drcBucket;
            this.securityId = securityId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof HedgeGroupKey))
                return false;
            HedgeGroupKey k = (HedgeGroupKey) o;
            return Objects.equals(legalEntity, k.legalEntity)
                    && Objects.equals(drcBucket, k.drcBucket)
                    && Objects.equals(securityId, k.securityId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(legalEntity, drcBucket, securityId);
        }
    }

    static class StandardGroupMetrics {
        final String securityType;
        final String legalEntity;
        final String drcBucket;
        final double riskWeight;
        final Map<RiskFactorKey, Double> netJtdByKey;
        double netJtdLong;
        double netJtdShort;
        double contribution;

        StandardGroupMetrics(String securityType, String legalEntity, String drcBucket,
                double riskWeight, Map<RiskFactorKey, Double> netJtdByKey) {
            this.securityType = securityType;
            this.legalEntity = legalEntity;
            this.drcBucket = drcBucket;
            this.riskWeight = riskWeight;
            this.netJtdByKey = netJtdByKey;
        }
    }

    /** 交易级JTD记录 */
    static class TradeRecord {
        final RiskFactorKey rfKey;
        final double jtd;
        double contribution;

        TradeRecord(DrcDetail d) {
            this.rfKey = new RiskFactorKey(
                    d.securityType, d.securityId, d.legalEntity, d.drcBucket, d.jtdType,
                    d.seniority != null ? d.seniority : 0,
                    d.riskWeight != null ? d.riskWeight : 0.0);
            if (d.jtdCny == null) {
                throw new IllegalArgumentException("drc 明细缺少 JTD_CNY，instrument_id=" + d.instrumentId);
            }
            this.jtd = d.jtdCny;
        }
    }

    /** Bucket级计算指标 */
    static class BucketMetrics {
        final String drcBucket;
        double netJtdLong;
        double netJtdShort;
        double hbr;
        double wtdJtdLong;
        double wtdJtdShort;
        double drc;

        BucketMetrics(String drcBucket) {
            this.drcBucket = drcBucket;
        }
    }

    /** 法人级资本贡献 */
    public static class LegalEntityContribution {
        public final String securityType;
        public final String legalEntity;
        public final String drcBucket;
        public double contribution;

        LegalEntityContribution(String securityType, String legalEntity, String drcBucket) {
            this.securityType = securityType;
            this.legalEntity = legalEntity;
            this.drcBucket = drcBucket;
        }
    }

    /** 明细级资本贡献（按风险因子） */
    public static class DetailContribution {
        public final String securityType;
        public final String legalEntity;
        public final String drcBucket;
        public final String jtdType;
        public final int seniority;
        public final double riskWeight;
        public double jtd;
        public double contribution;

        DetailContribution(RiskFactorKey key) {
            this.securityType = key.securityType;
            this.legalEntity = key.legalEntity;
            this.drcBucket = key.drcBucket;
            this.jtdType = key.jtdType;
            this.seniority = key.seniority;
            this.riskWeight = key.riskWeight;
        }
    }

    /** 单类产品的完整计算结果 */
    public static class TypeResult {
        public final List<LegalEntityContribution> legalEntityDecomp;
        public final List<DetailContribution> detailDecomp;

        TypeResult(List<LegalEntityContribution> legalEntityDecomp,
                List<DetailContribution> detailDecomp) {
            this.legalEntityDecomp = legalEntityDecomp;
            this.detailDecomp = detailDecomp;
        }
    }

    // ==================== 计算入口 ====================

    /**
     * 执行DRC计算（根据产品类型自动路由）
     *
     * @param securityType 产品类型常量（JTD_N / JTD_S_N_CTP / JTD_S_CTP）
     * @param details      该类型的交易JTD数据
     * @return 组合级和义务人级资本分解结果
     */
    public static TypeResult calculate(String securityType, List<DrcDetail> details) {
        List<DrcDetail> validDetails = filterNullJtdCnyDetails(securityType, details);
        if (validDetails.isEmpty()) {
            return new TypeResult(new ArrayList<>(), new ArrayList<>());
        }
        if (Constants.FRTB.DRC.JTD_S_CTP.equalsIgnoreCase(securityType)) {
            return doCalculateCtp(validDetails);
        }
        return doCalculateStandard(validDetails);
    }

    // ==================== 核心计算流程 ====================

    private static TypeResult doCalculateStandard(List<DrcDetail> details) {
        if (details.isEmpty()) {
            return new TypeResult(new ArrayList<>(), new ArrayList<>());
        }

        List<TradeRecord> trades = details.stream()
                .map(TradeRecord::new).collect(Collectors.toList());

        // 按风险因子分组，计算净JTD
        Map<RiskFactorKey, Double> netJtdMap = trades.stream()
                .collect(Collectors.groupingBy(t -> t.rfKey, Collectors.summingDouble(t -> t.jtd)));

        // Bucket级指标(HBR, 加权JTD, DRC)
        List<StandardGroupMetrics> groupMetrics = calcStandardGroupMetrics(netJtdMap);
        Map<String, BucketMetrics> bucketMap = calcBucketMetrics(groupMetrics);
        allocateStandardGroupContribution(groupMetrics, bucketMap);

        return new TypeResult(aggregateStandardLegalEntity(groupMetrics), aggregateStandardDetail(groupMetrics));
    }

    /**
     * CTP 独立计算路径。
     *
     * <p>
     * 实现口径：
     * <ul>
     * <li>CTP 的 HBR 在全体 CTP 头寸层面统一计算，而非逐 bucket 计算</li>
     * <li>bucket 级 D_b 允许为负值，不做 bucket 级 0 下限</li>
     * <li>总资本聚合时，对负 bucket 按 0.5 系数计入</li>
     * </ul>
     */
    private static TypeResult doCalculateCtp(List<DrcDetail> details) {
        if (details.isEmpty()) {
            return new TypeResult(new ArrayList<>(), new ArrayList<>());
        }

        List<TradeRecord> trades = details.stream()
                .map(TradeRecord::new).collect(Collectors.toList());

        Map<RiskFactorKey, Double> netJtdMap = trades.stream()
                .collect(Collectors.groupingBy(t -> t.rfKey, Collectors.summingDouble(t -> t.jtd)));

        Map<RiskFactorKey, Double> longPos = new HashMap<>();
        Map<RiskFactorKey, Double> shortPos = new HashMap<>();
        netJtdMap.forEach((key, jtd) -> {
            if (jtd > 0) {
                longPos.put(key, jtd);
            } else if (jtd < 0) {
                shortPos.put(key, jtd);
            }
        });

        Map<String, BucketMetrics> bucketMap = calcCtpBucketMetrics(longPos, shortPos);
        Map<RiskFactorKey, Double> pderMap = calcCtpPder(netJtdMap, longPos, shortPos, bucketMap);

        for (TradeRecord t : trades) {
            t.contribution = t.jtd * pderMap.getOrDefault(t.rfKey, 0.0);
        }

        return new TypeResult(aggregateLegalEntity(trades), aggregateDetail(trades));
    }

    /**
     * 过滤 JTD_CNY 为空的记录，避免单条脏数据导致整批 DRC 失败。
     */
    private static List<DrcDetail> filterNullJtdCnyDetails(String securityType, List<DrcDetail> details) {
        if (details == null || details.isEmpty()) {
            return Collections.emptyList();
        }
        List<DrcDetail> valid = new ArrayList<>(details.size());
        int nullCount = 0;
        int logged = 0;
        for (DrcDetail d : details) {
            if (d == null || d.jtdCny == null) {
                nullCount++;
                if (logged < MAX_NULL_JTD_CNY_LOG) {
                    log.warn("DRC明细JTD_CNY为空，已过滤: securityType={}, instrumentId={}, legalEntity={}, drcBucket={}",
                            securityType,
                            d == null ? null : d.instrumentId,
                            d == null ? null : d.legalEntity,
                            d == null ? null : d.drcBucket);
                    logged++;
                }
                continue;
            }
            valid.add(d);
        }
        if (nullCount > 0) {
            log.warn("DRC明细存在JTD_CNY为空记录，已按规则过滤: securityType={}, total={}, filtered={}, valid={}, loggedRows={}",
                    securityType, details.size(), nullCount, valid.size(), Math.min(nullCount, MAX_NULL_JTD_CNY_LOG));
        }
        return valid;
    }

    // ==================== 计算步骤 ====================

    private static String normalizeSecurityId(String securityType, String securityId) {
        return Constants.FRTB.DRC.JTD_S_N_CTP.equalsIgnoreCase(securityType) ? securityId : null;
    }

    private static List<StandardGroupMetrics> calcStandardGroupMetrics(Map<RiskFactorKey, Double> netJtdMap) {
        Map<HedgeGroupKey, Map<RiskFactorKey, Double>> grouped = new LinkedHashMap<>();
        netJtdMap.forEach((key, jtd) -> grouped
                .computeIfAbsent(key.toHedgeGroupKey(), k -> new LinkedHashMap<>())
                .merge(key, jtd, Double::sum));

        List<StandardGroupMetrics> result = new ArrayList<>();
        for (Map.Entry<HedgeGroupKey, Map<RiskFactorKey, Double>> entry : grouped.entrySet()) {
            TreeMap<Integer, Double> senioritySums = new TreeMap<>();
            RiskFactorKey representative = null;
            Double riskWeight = null;
            for (Map.Entry<RiskFactorKey, Double> item : entry.getValue().entrySet()) {
                RiskFactorKey key = item.getKey();
                if (representative == null) {
                    representative = key;
                }
                if (riskWeight == null) {
                    riskWeight = key.riskWeight;
                } else if (Math.abs(riskWeight - key.riskWeight) > 1e-12) {
                    throw new IllegalArgumentException("DRC同一净额组存在多个风险权重: securityType=" + key.securityType
                            + ", legalEntity=" + key.legalEntity + ", drcBucket=" + key.drcBucket
                            + ", securityId=" + key.securityId);
                }
                senioritySums.merge(key.seniority, item.getValue(), Double::sum);
            }
            if (representative == null || riskWeight == null) {
                continue;
            }
            StandardGroupMetrics metrics = new StandardGroupMetrics(
                    representative.securityType, representative.legalEntity,
                    representative.drcBucket, riskWeight, entry.getValue());
            metrics.netJtdLong = calcNetJtdLong(senioritySums);
            metrics.netJtdShort = calcNetJtdShort(senioritySums);
            result.add(metrics);
        }
        return result;
    }

    private static double calcNetJtdLong(TreeMap<Integer, Double> senioritySums) {
        double value = 0.0;
        for (Double sum : senioritySums.values()) {
            value = Math.max(value + sum, 0.0);
        }
        return value;
    }

    private static double calcNetJtdShort(TreeMap<Integer, Double> senioritySums) {
        double value = 0.0;
        for (Double sum : senioritySums.descendingMap().values()) {
            value = Math.min(value + sum, 0.0);
        }
        return value;
    }

    /** 计算Bucket级指标(HBR, 加权JTD, DRC) */
    private static Map<String, BucketMetrics> calcBucketMetrics(List<StandardGroupMetrics> groupMetrics) {

        Map<String, BucketMetrics> bucketMap = new HashMap<>();
        for (StandardGroupMetrics metrics : groupMetrics) {
            BucketMetrics bm = bucketMap.computeIfAbsent(metrics.drcBucket, BucketMetrics::new);
            if (metrics.netJtdLong > 0) {
                bm.netJtdLong += metrics.netJtdLong;
                bm.wtdJtdLong += metrics.netJtdLong * metrics.riskWeight;
            }
            if (metrics.netJtdShort < 0) {
                double absShort = Math.abs(metrics.netJtdShort);
                bm.netJtdShort += absShort;
                bm.wtdJtdShort += absShort * metrics.riskWeight;
            }
        }
        bucketMap.values().forEach(bm -> {
            double d = bm.netJtdLong + bm.netJtdShort;
            bm.hbr = d > 0 ? bm.netJtdLong / d : 0;
            bm.drc = Math.max(bm.wtdJtdLong - bm.hbr * bm.wtdJtdShort, 0);
        });
        return bucketMap;
    }

    private static void allocateStandardGroupContribution(List<StandardGroupMetrics> groupMetrics,
            Map<String, BucketMetrics> bucketMap) {
        for (StandardGroupMetrics metrics : groupMetrics) {
            BucketMetrics bm = bucketMap.get(metrics.drcBucket);
            if (bm == null || bm.drc <= 0.0) {
                metrics.contribution = 0.0;
                continue;
            }
            metrics.contribution = metrics.netJtdLong * metrics.riskWeight
                    - bm.hbr * Math.abs(metrics.netJtdShort) * metrics.riskWeight;
        }
    }

    /**
     * 计算 CTP bucket 指标。
     *
     * <p>
     * 这里的 HBR 使用全体 CTP 头寸统一计算，并直接作用于各 bucket 的 D_b。
     * 与 NS/NCTP 不同，D_b 不做 bucket 级别的 0 下限。
     */
    private static Map<String, BucketMetrics> calcCtpBucketMetrics(
            Map<RiskFactorKey, Double> longPos, Map<RiskFactorKey, Double> shortPos) {

        Map<String, BucketMetrics> bucketMap = new HashMap<>();
        longPos.forEach((key, jtd) -> {
            BucketMetrics bm = bucketMap.computeIfAbsent(key.drcBucket, BucketMetrics::new);
            bm.netJtdLong += jtd;
            bm.wtdJtdLong += jtd * key.riskWeight;
        });
        shortPos.forEach((key, jtd) -> {
            BucketMetrics bm = bucketMap.computeIfAbsent(key.drcBucket, BucketMetrics::new);
            bm.netJtdShort += Math.abs(jtd);
            bm.wtdJtdShort += Math.abs(jtd) * key.riskWeight;
        });

        double totalLong = longPos.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalShort = shortPos.values().stream().mapToDouble(v -> Math.abs(v)).sum();
        double denom = totalLong + totalShort;
        double hbr = denom > 0 ? totalLong / denom : 0.0;

        bucketMap.values().forEach(bm -> {
            bm.hbr = hbr;
            bm.drc = bm.wtdJtdLong - hbr * bm.wtdJtdShort;
        });
        return bucketMap;
    }

    /**
     * 计算 CTP 路径的偏导数。
     *
     * <p>
     * 先根据各 bucket 的 D_b 确定负侧 0.5 系数，再对总资本函数做欧拉分解。
     * 当总资本经 0 下限后为 0 时，全部偏导置 0。
     */
    private static Map<RiskFactorKey, Double> calcCtpPder(
            Map<RiskFactorKey, Double> netJtdMap,
            Map<RiskFactorKey, Double> longPos,
            Map<RiskFactorKey, Double> shortPos,
            Map<String, BucketMetrics> bucketMap) {

        Map<RiskFactorKey, Double> result = new HashMap<>();

        double totalLong = longPos.values().stream().mapToDouble(Double::doubleValue).sum();
        double totalShort = shortPos.values().stream().mapToDouble(v -> Math.abs(v)).sum();
        double denom = totalLong + totalShort;
        if (denom <= 0) {
            return result;
        }

        Map<String, Double> bucketFactorMap = new HashMap<>();
        double weightedShortSum = 0.0;
        double totalCapital = 0.0;
        for (BucketMetrics bm : bucketMap.values()) {
            double factor = bm.drc >= 0 ? 1.0 : 0.5;
            bucketFactorMap.put(bm.drcBucket, factor);
            weightedShortSum += factor * bm.wtdJtdShort;
            totalCapital += factor * bm.drc;
        }
        if (totalCapital <= 0) {
            return result;
        }

        double longHbrPart = totalShort / (denom * denom);
        double shortHbrPart = totalLong / (denom * denom);
        double hbr = totalLong / denom;
        double finalWeightedShortSum = weightedShortSum;

        netJtdMap.forEach((key, netJtd) -> {
            double factor = bucketFactorMap.getOrDefault(key.drcBucket, 1.0);
            double pder;
            if (netJtd > 0) {
                pder = factor * key.riskWeight - finalWeightedShortSum * longHbrPart;
            } else if (netJtd < 0) {
                pder = factor * key.riskWeight * hbr - finalWeightedShortSum * shortHbrPart;
            } else {
                pder = 0.0;
            }
            result.put(key, pder);
        });
        return result;
    }

    // ==================== 结果汇总 ====================

    private static List<LegalEntityContribution> aggregateStandardLegalEntity(List<StandardGroupMetrics> groupMetrics) {
        Map<String, LegalEntityContribution> grouped = new LinkedHashMap<>();
        for (StandardGroupMetrics metrics : groupMetrics) {
            String gk = metrics.securityType + "|" + metrics.legalEntity + "|" + metrics.drcBucket;
            grouped.computeIfAbsent(gk, k -> new LegalEntityContribution(
                    metrics.securityType, metrics.legalEntity, metrics.drcBucket)).contribution += metrics.contribution;
        }
        return new ArrayList<>(grouped.values());
    }

    private static List<DetailContribution> aggregateStandardDetail(List<StandardGroupMetrics> groupMetrics) {
        List<DetailContribution> result = new ArrayList<>();
        for (StandardGroupMetrics metrics : groupMetrics) {
            double denominator = metrics.netJtdByKey.values().stream().mapToDouble(Math::abs).sum();
            for (Map.Entry<RiskFactorKey, Double> entry : metrics.netJtdByKey.entrySet()) {
                DetailContribution oc = new DetailContribution(entry.getKey());
                oc.jtd = entry.getValue();
                oc.contribution = denominator > 0.0
                        ? metrics.contribution * Math.abs(entry.getValue()) / denominator
                        : 0.0;
                result.add(oc);
            }
        }
        return result;
    }

    private static List<LegalEntityContribution> aggregateLegalEntity(List<TradeRecord> trades) {
        Map<String, LegalEntityContribution> grouped = new LinkedHashMap<>();
        for (TradeRecord t : trades) {
            String gk = t.rfKey.securityType + "|" + t.rfKey.legalEntity + "|" + t.rfKey.drcBucket;
            grouped.computeIfAbsent(gk, k -> new LegalEntityContribution(
                    t.rfKey.securityType, t.rfKey.legalEntity, t.rfKey.drcBucket)).contribution += t.contribution;
        }
        return new ArrayList<>(grouped.values());
    }

    private static List<DetailContribution> aggregateDetail(List<TradeRecord> trades) {
        Map<RiskFactorKey, double[]> grouped = new LinkedHashMap<>();
        for (TradeRecord t : trades) {
            double[] s = grouped.computeIfAbsent(t.rfKey, k -> new double[2]);
            s[0] += t.contribution;
            s[1] += t.jtd;
        }
        List<DetailContribution> result = new ArrayList<>();
        grouped.forEach((key, s) -> {
            DetailContribution oc = new DetailContribution(key);
            oc.contribution = s[0];
            oc.jtd = s[1];
            result.add(oc);
        });
        return result;
    }
}
