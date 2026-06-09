package com.zcyh.mr.springboot.service;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IMA RFET 快照读取与情景点位标记服务。
 */
@Service
public class ImaRfetSnapshotService {
    private final JdbcTemplate engineDbJdbcTemplate;

    public ImaRfetSnapshotService(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public void annotate(String batchId, List<ScenarioGeneratedRecord> records) {
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId == null || records == null || records.isEmpty()) {
            return;
        }
        List<SnapshotBucket> buckets = loadBuckets(safeBatchId);
        for (ScenarioGeneratedRecord record : records) {
            if (record == null || !isImaScenario(record.getScenarioType())) {
                continue;
            }
            SnapshotBucket matched = match(buckets, record);
            if (matched == null) {
                throw new IllegalStateException("IMA 情景点未匹配 RFET bucket，scenario_id="
                        + record.getScenarioId()
                        + ", sub_scenario_id=" + record.getSubScenarioId()
                        + ", curve_type=" + record.getCurveType()
                        + ", curve_code=" + record.getCurveCode()
                        + ", term_days=" + record.getTermDays()
                        + ", dimension2=" + record.getDimension2());
            } else {
                record.setRfetBucketId(globalBucketId(matched));
                record.setRfetModellable(matched.modellable);
                record.setRfetReducedSet(matched.reducedSet);
            }
        }
    }

    public List<RfetResult> loadRfetResults(String batchId) {
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        List<SnapshotBucket> buckets = loadBuckets(safeBatchId);
        List<RfetResult> results = new ArrayList<RfetResult>();
        for (SnapshotBucket bucket : buckets) {
            RfetResult result = new RfetResult();
            result.setBucketId(globalBucketId(bucket));
            result.setCurveId(bucket.curveCode);
            result.setRfType(bucket.curveType);
            result.setGroupId(bucket.groupId);
            result.setGroupType(bucket.groupType);
            result.setTenorMin(bucket.tenorMin);
            result.setTenorMax(bucket.tenorMax);
            result.setDeltaMin(bucket.deltaMin == null ? null : bucket.deltaMin.doubleValue());
            result.setDeltaMax(bucket.deltaMax == null ? null : bucket.deltaMax.doubleValue());
            result.setReducedSet(bucket.reducedSet);
            result.setModellable(bucket.modellable);
            result.setPassedViaOwnBucket(bucket.passedViaOwnBucket);
            result.setObservationCount(bucket.observationCount);
            result.setMinObservationsInAnyPeriod(bucket.minObservationsInAnyPeriod);
            result.setReason(bucket.reason);
            result.setTenorDays(new LinkedHashSet<Integer>());
            results.add(result);
        }
        return results;
    }

    private List<SnapshotBucket> loadBuckets(String batchId) {
        List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList("""
                SELECT curve_code,
                       curve_type,
                       group_id,
                       group_type,
                       bucket_id,
                       delta_bucket_flag,
                       tenor_min,
                       tenor_max,
                       delta_min,
                       delta_max,
                       modellable,
                       reduced_set
                       ,passed_via_own_bucket
                       ,observation_count
                       ,min_observations_in_any_period
                       ,reason
                FROM MR_IMA_RFET_RESULT_SNAPSHOT
                WHERE batch_id=?
                ORDER BY curve_type, curve_code, tenor_min, tenor_max, bucket_id
                """, batchId);
        List<SnapshotBucket> buckets = new ArrayList<SnapshotBucket>();
        for (Map<String, Object> row : rows) {
            SnapshotBucket bucket = new SnapshotBucket();
            bucket.curveCode = normalizeText(row.get("curve_code"));
            bucket.curveType = normalizeText(row.get("curve_type"));
            bucket.groupId = normalizeText(row.get("group_id"));
            bucket.groupType = normalizeText(row.get("group_type"));
            bucket.bucketId = text(row.get("bucket_id"));
            bucket.deltaBucketFlag = toBoolean(row.get("delta_bucket_flag"));
            bucket.tenorMin = toInt(row.get("tenor_min"));
            bucket.tenorMax = toInt(row.get("tenor_max"));
            bucket.deltaMin = toBigDecimal(row.get("delta_min"));
            bucket.deltaMax = toBigDecimal(row.get("delta_max"));
            bucket.modellable = toBoolean(row.get("modellable"));
            bucket.reducedSet = toBoolean(row.get("reduced_set"));
            bucket.passedViaOwnBucket = toBoolean(row.get("passed_via_own_bucket"));
            bucket.observationCount = toInt(row.get("observation_count"));
            bucket.minObservationsInAnyPeriod = toInt(row.get("min_observations_in_any_period"));
            bucket.reason = text(row.get("reason"));
            buckets.add(bucket);
        }
        validateNoConflictingBuckets(batchId, buckets);
        validateSingleGroupPerCurve(batchId, buckets);
        return buckets;
    }

    private SnapshotBucket match(List<SnapshotBucket> buckets, ScenarioGeneratedRecord record) {
        String curveCode = normalizeText(record.getCurveCode());
        String curveType = normalizeText(record.getCurveType());
        Integer termDays = record.getTermDays();
        if (curveCode == null || curveType == null || termDays == null) {
            return null;
        }
        BigDecimal delta = toBigDecimal(record.getDimension2());
        SnapshotBucket matched = null;
        for (SnapshotBucket bucket : buckets) {
            if (!curveCode.equals(bucket.curveCode) || !curveType.equals(bucket.curveType)) {
                continue;
            }
            if (termDays < bucket.tenorMin || termDays > bucket.tenorMax) {
                continue;
            }
            if (!bucket.deltaBucketFlag) {
                matched = ensureSingleMatch(record, matched, bucket);
                continue;
            }
            if (delta == null || bucket.deltaMin == null || bucket.deltaMax == null) {
                continue;
            }
            int lower = delta.compareTo(bucket.deltaMin);
            int upper = delta.compareTo(bucket.deltaMax);
            boolean includeUpper = bucket.deltaMax.compareTo(BigDecimal.ONE) >= 0;
            if (lower >= 0 && (includeUpper ? upper <= 0 : upper < 0)) {
                matched = ensureSingleMatch(record, matched, bucket);
            }
        }
        return matched;
    }

    private SnapshotBucket ensureSingleMatch(
            ScenarioGeneratedRecord record,
            SnapshotBucket matched,
            SnapshotBucket candidate) {
        if (matched != null) {
            throw new IllegalStateException("RFET 快照匹配到多个桶，curve_type="
                    + record.getCurveType()
                    + ", curve_code=" + record.getCurveCode()
                    + ", term_days=" + record.getTermDays()
                    + ", dimension2=" + record.getDimension2()
                    + ", bucket1=" + matched.bucketId
                    + ", group1=" + matched.groupType + "/" + matched.groupId
                    + ", bucket2=" + candidate.bucketId
                    + ", group2=" + candidate.groupType + "/" + candidate.groupId);
        }
        return candidate;
    }

    private void validateNoConflictingBuckets(String batchId, List<SnapshotBucket> buckets) {
        Map<String, SnapshotBucket> seen = new java.util.LinkedHashMap<String, SnapshotBucket>();
        for (SnapshotBucket bucket : buckets) {
            String key = bucket.curveType
                    + "|" + bucket.curveCode
                    + "|" + bucket.tenorMin
                    + "|" + bucket.tenorMax
                    + "|" + decimalKey(bucket.deltaMin)
                    + "|" + decimalKey(bucket.deltaMax);
            SnapshotBucket existing = seen.get(key);
            if (existing == null) {
                seen.put(key, bucket);
                continue;
            }
            if (!sameGroup(existing, bucket)) {
                throw new IllegalStateException("RFET 快照同一曲线同一区间存在多个观测组，batchId="
                        + batchId
                        + ", curve_type=" + bucket.curveType
                        + ", curve_code=" + bucket.curveCode
                        + ", tenor_min=" + bucket.tenorMin
                        + ", tenor_max=" + bucket.tenorMax
                        + ", group1=" + existing.groupType + "/" + existing.groupId
                        + ", group2=" + bucket.groupType + "/" + bucket.groupId);
            }
        }
    }

    private void validateSingleGroupPerCurve(String batchId, List<SnapshotBucket> buckets) {
        Map<String, SnapshotBucket> seen = new java.util.LinkedHashMap<String, SnapshotBucket>();
        for (SnapshotBucket bucket : buckets) {
            String key = bucket.curveType + "|" + bucket.curveCode;
            SnapshotBucket existing = seen.get(key);
            if (existing == null) {
                seen.put(key, bucket);
                continue;
            }
            if (!sameGroup(existing, bucket)) {
                throw new IllegalStateException("RFET 快照同一曲线存在多个观测组，batchId="
                        + batchId
                        + ", curve_type=" + bucket.curveType
                        + ", curve_code=" + bucket.curveCode
                        + ", group1=" + existing.groupType + "/" + existing.groupId
                        + ", group2=" + bucket.groupType + "/" + bucket.groupId);
            }
        }
    }

    private static String globalBucketId(SnapshotBucket bucket) {
        String raw = bucket.curveType
                + "|" + bucket.curveCode
                + "|" + bucket.groupType
                + "|" + bucket.groupId
                + "|" + bucket.bucketId;
        String prefix = sanitize(bucket.curveType) + "_" + sanitize(bucket.curveCode);
        if (prefix.length() > 80) {
            prefix = prefix.substring(0, 80);
        }
        return prefix + "_" + sha256Hex(raw).substring(0, 16);
    }

    private static String sanitize(String value) {
        String text = normalizeText(value);
        if (text == null) {
            return "NA";
        }
        return text.replaceAll("[^A-Z0-9_]", "_");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }

    private static boolean sameGroup(SnapshotBucket left, SnapshotBucket right) {
        return equalsText(left.groupId, right.groupId) && equalsText(left.groupType, right.groupType);
    }

    private static boolean equalsText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String decimalKey(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean isImaScenario(String scenarioType) {
        String value = normalizeText(scenarioType);
        return "IMA_NORMAL".equals(value) || "IMA_STRESS".equals(value) || "IMA_NMRF".equals(value);
    }

    private static String normalizeText(Object value) {
        String text = text(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value).trim());
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        String text = text(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text);
    }

    private static BigDecimal toBigDecimal(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        return new BigDecimal(text);
    }

    private static class SnapshotBucket {
        private String curveCode;
        private String curveType;
        private String groupId;
        private String groupType;
        private String bucketId;
        private boolean deltaBucketFlag;
        private int tenorMin;
        private int tenorMax;
        private BigDecimal deltaMin;
        private BigDecimal deltaMax;
        private boolean modellable;
        private boolean reducedSet;
        private boolean passedViaOwnBucket;
        private int observationCount;
        private int minObservationsInAnyPeriod;
        private String reason;
    }
}
