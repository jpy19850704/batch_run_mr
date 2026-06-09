package com.zcyh.mr.springboot.service;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
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
                record.setRfetBucketId(null);
                record.setRfetModellable(Boolean.FALSE);
                record.setRfetReducedSet(Boolean.FALSE);
            } else {
                record.setRfetBucketId(matched.bucketId);
                record.setRfetModellable(matched.modellable);
                record.setRfetReducedSet(matched.reducedSet);
            }
        }
    }

    private List<SnapshotBucket> loadBuckets(String batchId) {
        List<Map<String, Object>> rows = engineDbJdbcTemplate.queryForList("""
                SELECT curve_code,
                       curve_type,
                       bucket_id,
                       delta_bucket_flag,
                       tenor_min,
                       tenor_max,
                       delta_min,
                       delta_max,
                       modellable,
                       reduced_set
                FROM MR_IMA_RFET_RESULT_SNAPSHOT
                WHERE batch_id=?
                ORDER BY curve_type, curve_code, tenor_min, tenor_max, bucket_id
                """, batchId);
        List<SnapshotBucket> buckets = new ArrayList<SnapshotBucket>();
        for (Map<String, Object> row : rows) {
            SnapshotBucket bucket = new SnapshotBucket();
            bucket.curveCode = normalizeText(row.get("curve_code"));
            bucket.curveType = normalizeText(row.get("curve_type"));
            bucket.bucketId = text(row.get("bucket_id"));
            bucket.deltaBucketFlag = toBoolean(row.get("delta_bucket_flag"));
            bucket.tenorMin = toInt(row.get("tenor_min"));
            bucket.tenorMax = toInt(row.get("tenor_max"));
            bucket.deltaMin = toBigDecimal(row.get("delta_min"));
            bucket.deltaMax = toBigDecimal(row.get("delta_max"));
            bucket.modellable = toBoolean(row.get("modellable"));
            bucket.reducedSet = toBoolean(row.get("reduced_set"));
            buckets.add(bucket);
        }
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
        for (SnapshotBucket bucket : buckets) {
            if (!curveCode.equals(bucket.curveCode) || !curveType.equals(bucket.curveType)) {
                continue;
            }
            if (termDays < bucket.tenorMin || termDays > bucket.tenorMax) {
                continue;
            }
            if (!bucket.deltaBucketFlag) {
                return bucket;
            }
            if (delta == null || bucket.deltaMin == null || bucket.deltaMax == null) {
                continue;
            }
            int lower = delta.compareTo(bucket.deltaMin);
            int upper = delta.compareTo(bucket.deltaMax);
            boolean includeUpper = bucket.deltaMax.compareTo(BigDecimal.ONE) >= 0;
            if (lower >= 0 && (includeUpper ? upper <= 0 : upper < 0)) {
                return bucket;
            }
        }
        return null;
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
        private String bucketId;
        private boolean deltaBucketFlag;
        private int tenorMin;
        private int tenorMax;
        private BigDecimal deltaMin;
        private BigDecimal deltaMax;
        private boolean modellable;
        private boolean reducedSet;
    }
}
