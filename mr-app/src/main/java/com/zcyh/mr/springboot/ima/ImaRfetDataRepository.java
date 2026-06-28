package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IMA RFET 数据读取。
 */
@Repository
public class ImaRfetDataRepository {
    private final JdbcTemplate engineDbJdbcTemplate;

    public ImaRfetDataRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate engineDbJdbcTemplate) {
        this.engineDbJdbcTemplate = engineDbJdbcTemplate;
    }

    public List<RfetResult> loadRfetResults(LocalDate dataDate) {
        if (dataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
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
                       reduced_set,
                       passed_via_own_bucket,
                       observation_count,
                       min_observations_in_any_period,
                       reason
                FROM MR_IMA_RFET_RESULT
                WHERE data_date=?
                ORDER BY curve_type, curve_code, tenor_min, tenor_max, bucket_id
                """, Date.valueOf(dataDate));
        List<RfetResult> results = new ArrayList<RfetResult>();
        for (Map<String, Object> row : rows) {
            RfetResult result = new RfetResult();
            String curveCode = normalizeText(row.get("curve_code"));
            String curveType = normalizeText(row.get("curve_type"));
            String groupId = normalizeText(row.get("group_id"));
            String groupType = normalizeText(row.get("group_type"));
            String sourceBucketId = text(row.get("bucket_id"));
            result.setBucketId(globalBucketId(curveType, curveCode, groupType, groupId, sourceBucketId));
            result.setCurveId(curveCode);
            result.setRfType(curveType);
            result.setGroupId(groupId);
            result.setGroupType(groupType);
            result.setDeltaBucketFlag(toBoolean(row.get("delta_bucket_flag")));
            result.setTenorMin(toInt(row.get("tenor_min")));
            result.setTenorMax(toInt(row.get("tenor_max")));
            result.setDeltaMin(toDouble(row.get("delta_min")));
            result.setDeltaMax(toDouble(row.get("delta_max")));
            result.setModellable(toBoolean(row.get("modellable")));
            result.setReducedSet(toBoolean(row.get("reduced_set")));
            result.setPassedViaOwnBucket(toBoolean(row.get("passed_via_own_bucket")));
            result.setObservationCount(toInt(row.get("observation_count")));
            result.setMinObservationsInAnyPeriod(toInt(row.get("min_observations_in_any_period")));
            result.setReason(text(row.get("reason")));
            result.setTenorDays(new LinkedHashSet<Integer>());
            results.add(result);
        }
        validateNoConflictingBuckets(dataDate, results);
        validateSingleGroupPerCurve(dataDate, results);
        return results;
    }

    private static void validateNoConflictingBuckets(LocalDate dataDate, List<RfetResult> buckets) {
        Map<String, RfetResult> seen = new java.util.LinkedHashMap<String, RfetResult>();
        for (RfetResult bucket : buckets) {
            String key = bucket.getRfType()
                    + "|" + bucket.getCurveId()
                    + "|" + bucket.getTenorMin()
                    + "|" + bucket.getTenorMax()
                    + "|" + decimalKey(bucket.getDeltaMin())
                    + "|" + decimalKey(bucket.getDeltaMax());
            RfetResult existing = seen.get(key);
            if (existing == null) {
                seen.put(key, bucket);
                continue;
            }
            if (!sameGroup(existing, bucket)) {
                throw new IllegalStateException("RFET 同一曲线同一区间存在多个观测组，dataDate="
                        + dataDate + ", curve_type=" + bucket.getRfType()
                        + ", curve_code=" + bucket.getCurveId()
                        + ", tenor_min=" + bucket.getTenorMin()
                        + ", tenor_max=" + bucket.getTenorMax()
                        + ", group1=" + existing.getGroupType() + "/" + existing.getGroupId()
                        + ", group2=" + bucket.getGroupType() + "/" + bucket.getGroupId());
            }
        }
    }

    private static void validateSingleGroupPerCurve(LocalDate dataDate, List<RfetResult> buckets) {
        Map<String, RfetResult> seen = new java.util.LinkedHashMap<String, RfetResult>();
        for (RfetResult bucket : buckets) {
            String key = bucket.getRfType() + "|" + bucket.getCurveId();
            RfetResult existing = seen.get(key);
            if (existing == null) {
                seen.put(key, bucket);
                continue;
            }
            if (!sameGroup(existing, bucket)) {
                throw new IllegalStateException("RFET 同一曲线存在多个观测组，dataDate="
                        + dataDate + ", curve_type=" + bucket.getRfType()
                        + ", curve_code=" + bucket.getCurveId()
                        + ", group1=" + existing.getGroupType() + "/" + existing.getGroupId()
                        + ", group2=" + bucket.getGroupType() + "/" + bucket.getGroupId());
            }
        }
    }

    private static String globalBucketId(String curveType,
                                         String curveCode,
                                         String groupType,
                                         String groupId,
                                         String bucketId) {
        String raw = curveType + "|" + curveCode + "|" + groupType + "|" + groupId + "|" + bucketId;
        String prefix = sanitize(curveType) + "_" + sanitize(curveCode);
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

    private static boolean sameGroup(RfetResult left, RfetResult right) {
        return equalsText(left.getGroupId(), right.getGroupId())
                && equalsText(left.getGroupType(), right.getGroupType());
    }

    private static boolean equalsText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String decimalKey(Double value) {
        if (value == null) {
            return "";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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

    private static Double toDouble(Object value) {
        String text = text(value);
        return text == null ? null : Double.valueOf(text);
    }
}
