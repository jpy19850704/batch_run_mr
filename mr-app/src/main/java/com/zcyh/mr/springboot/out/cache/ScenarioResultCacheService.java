package com.zcyh.mr.springboot.out.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Scenario 结果 Redis 查询缓存服务。
 */
@Service
public class ScenarioResultCacheService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioResultCacheService.class);
    private static final String KEY_PREFIX = "SCENARIO:RESULT";
    private static final String NULL_TOKEN = "__NULL__";
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int SUBSCENARIO_GROUP_SIZE = 20;

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public ScenarioResultCacheService(StringRedisTemplate redisTemplate,
                                      @Value("${mr.scenario.result.redis.ttl-seconds:${mr.result.redis.ttl-seconds:3600}}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds <= 0 ? 3600L : ttlSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * 缓存场景结果并构建前端查询索引。
     */
    public JSONObject cacheRunResult(String runId,
                                     String scenarioIdList,
                                     String dataDate,
                                     List<ScenarioGeneratedRecord> records) {
        String safeRunId = trimToNull(runId);
        if (safeRunId == null) {
            throw new IllegalArgumentException("runId 不能为空");
        }
        if (records == null) {
            records = Collections.emptyList();
        }

        LinkedHashSet<String> scenarioIds = new LinkedHashSet<String>();
        LinkedHashMap<String, LinkedHashSet<String>> subScenariosByScenario = new LinkedHashMap<String, LinkedHashSet<String>>();
        LinkedHashMap<String, LinkedHashSet<String>> curveTypesByScenarioSub = new LinkedHashMap<String, LinkedHashSet<String>>();
        LinkedHashMap<String, LinkedHashSet<String>> curveIdsByScenarioSubType = new LinkedHashMap<String, LinkedHashSet<String>>();
        LinkedHashMap<String, List<JSONObject>> detailByDimension = new LinkedHashMap<String, List<JSONObject>>();

        for (ScenarioGeneratedRecord record : records) {
            if (record == null) {
                continue;
            }
            String scenarioId = safeToken(record.getScenarioId());
            String subScenarioId = safeToken(record.getSubScenarioId());
            String curveType = safeToken(record.getCurveType());
            String curveId = safeToken(record.getCurveCode());

            scenarioIds.add(scenarioId);
            addToMapSet(subScenariosByScenario, scenarioId, subScenarioId);
            addToMapSet(curveTypesByScenarioSub, joinKey(scenarioId, subScenarioId), curveType);
            addToMapSet(curveIdsByScenarioSubType, joinKey(scenarioId, subScenarioId, curveType), curveId);
            detailByDimension.computeIfAbsent(joinKey(scenarioId, subScenarioId, curveType, curveId),
                            key -> new ArrayList<JSONObject>())
                    .add(toDetailRow(record));
        }

        try {
            HashOperations<String, String, String> hashOperations = redisTemplate.opsForHash();
            String detailHashKey = buildKey(safeRunId, "DETAIL");
            String subHashKey = buildKey(safeRunId, "INDEX", "SUB");
            String curveTypeHashKey = buildKey(safeRunId, "INDEX", "CURVE_TYPE");
            String curveIdHashKey = buildKey(safeRunId, "INDEX", "CURVE_ID");
            String scenarioListKey = buildKey(safeRunId, "INDEX", "SCENARIO");
            String summaryKey = buildKey(safeRunId, "SUMMARY");

            for (Map.Entry<String, List<JSONObject>> entry : detailByDimension.entrySet()) {
                hashOperations.put(detailHashKey, encodeToken(entry.getKey()), JSON.toJSONString(entry.getValue()));
            }
            for (Map.Entry<String, LinkedHashSet<String>> entry : subScenariosByScenario.entrySet()) {
                hashOperations.put(subHashKey, encodeToken(entry.getKey()), JSON.toJSONString(new ArrayList<String>(entry.getValue())));
            }
            for (Map.Entry<String, LinkedHashSet<String>> entry : curveTypesByScenarioSub.entrySet()) {
                hashOperations.put(curveTypeHashKey, encodeToken(entry.getKey()), JSON.toJSONString(new ArrayList<String>(entry.getValue())));
            }
            for (Map.Entry<String, LinkedHashSet<String>> entry : curveIdsByScenarioSubType.entrySet()) {
                hashOperations.put(curveIdHashKey, encodeToken(entry.getKey()), JSON.toJSONString(new ArrayList<String>(entry.getValue())));
            }

            redisTemplate.opsForValue().set(scenarioListKey, JSON.toJSONString(new ArrayList<String>(scenarioIds)), ttlSeconds, TimeUnit.SECONDS);
            JSONObject summary = buildSummary(
                    safeRunId,
                    scenarioIdList,
                    dataDate,
                    records.size(),
                    scenarioIds,
                    subScenariosByScenario,
                    curveTypesByScenarioSub,
                    curveIdsByScenarioSubType);
            redisTemplate.opsForValue().set(summaryKey, JSON.toJSONString(summary), ttlSeconds, TimeUnit.SECONDS);

            expireKey(detailHashKey);
            expireKey(subHashKey);
            expireKey(curveTypeHashKey);
            expireKey(curveIdHashKey);
            return summary;
        } catch (Exception ex) {
            log.warn("写入 Scenario Redis 缓存失败，runId={}, error={}", safeRunId, ex.getMessage());
            JSONObject summary = buildSummary(
                    safeRunId,
                    scenarioIdList,
                    dataDate,
                    records.size(),
                    scenarioIds,
                    subScenariosByScenario,
                    curveTypesByScenarioSub,
                    curveIdsByScenarioSubType);
            summary.put("cache_ready", false);
            summary.put("cache_error", ex.getMessage());
            return summary;
        }
    }

    /**
     * 读取场景运行摘要。
     */
    public JSONObject getRunSummary(String runId) {
        String raw = redisTemplate.opsForValue().get(buildKey(runId, "SUMMARY"));
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return JSON.parseObject(raw);
    }

    /**
     * 读取前端筛选联动所需维度列表。
     */
    public JSONObject listDimensions(String runId,
                                     String scenarioId,
                                     String subScenarioId,
                                     String curveType) {
        String safeRunId = requireRunId(runId);
        JSONObject result = new JSONObject(new LinkedHashMap<String, Object>());
        result.put("run_id", safeRunId);
        result.put("scenario_ids", readArrayValue(buildKey(safeRunId, "INDEX", "SCENARIO")));

        if (trimToNull(scenarioId) != null) {
            result.put("sub_scenario_ids", readArrayHash(buildKey(safeRunId, "INDEX", "SUB"), scenarioId));
        } else {
            result.put("sub_scenario_ids", new JSONArray());
        }

        if (trimToNull(scenarioId) != null && trimToNull(subScenarioId) != null) {
            result.put("curve_types",
                    readArrayHash(buildKey(safeRunId, "INDEX", "CURVE_TYPE"), joinKey(safeToken(scenarioId), safeToken(subScenarioId))));
        } else {
            result.put("curve_types", new JSONArray());
        }

        if (trimToNull(scenarioId) != null && trimToNull(subScenarioId) != null && trimToNull(curveType) != null) {
            result.put("curve_ids",
                    readArrayHash(buildKey(safeRunId, "INDEX", "CURVE_ID"),
                            joinKey(safeToken(scenarioId), safeToken(subScenarioId), safeToken(curveType))));
        } else {
            result.put("curve_ids", new JSONArray());
        }
        return result;
    }

    /**
     * 按维度精确读取明细结果。
     */
    public JSONObject getDetail(String runId,
                                String scenarioId,
                                String subScenarioId,
                                String curveType,
                                String curveId) {
        String safeRunId = requireRunId(runId);
        String safeScenarioId = requireText(scenarioId, "scenarioId 不能为空");
        String safeSubScenarioId = requireText(subScenarioId, "subScenarioId 不能为空");
        String safeCurveType = requireText(curveType, "curveType 不能为空");
        String safeCurveId = requireText(curveId, "curveId 不能为空");

        Object rawValue = redisTemplate.opsForHash().get(
                buildKey(safeRunId, "DETAIL"),
                encodeToken(joinKey(
                        safeToken(safeScenarioId),
                        safeToken(safeSubScenarioId),
                        safeToken(safeCurveType),
                        safeToken(safeCurveId))));
        String raw = rawValue == null ? null : String.valueOf(rawValue);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }

        JSONArray rows = JSON.parseArray(raw);
        JSONObject result = new JSONObject(new LinkedHashMap<String, Object>());
        result.put("run_id", safeRunId);
        result.put("scenario_id", safeScenarioId);
        result.put("sub_scenario_id", safeSubScenarioId);
        result.put("curve_type", safeCurveType);
        result.put("curve_id", safeCurveId);
        result.put("total", rows == null ? 0 : rows.size());
        result.put("rows", rows == null ? new JSONArray() : rows);
        return result;
    }

    private JSONObject buildSummary(String runId,
                                    String scenarioIdList,
                                    String dataDate,
                                    int recordCount,
                                    Set<String> scenarioIds,
                                    Map<String, LinkedHashSet<String>> subScenariosByScenario,
                                    Map<String, LinkedHashSet<String>> curveTypesByScenarioSub,
                                    Map<String, LinkedHashSet<String>> curveIdsByScenarioSubType) {
        int subScenarioCount = 0;
        for (Set<String> values : subScenariosByScenario.values()) {
            subScenarioCount += values.size();
        }
        JSONObject summary = new JSONObject(new LinkedHashMap<String, Object>());
        summary.put("run_id", runId);
        summary.put("scenario_id_list", trimToNull(scenarioIdList));
        summary.put("data_date", trimToNull(dataDate));
        summary.put("record_count", recordCount);
        summary.put("scenario_count", scenarioIds.size());
        summary.put("sub_scenario_count", subScenarioCount);
        summary.put("curve_type_group_count", curveTypesByScenarioSub.size());
        summary.put("curve_id_group_count", curveIdsByScenarioSubType.size());
        summary.put("scenario_ids", restoreList(new ArrayList<String>(scenarioIds)));
        summary.put("sub_scenario_groups", buildSubScenarioGroups(subScenariosByScenario));
        summary.put("cache_ready", true);
        summary.put("ttl_seconds", ttlSeconds);
        return summary;
    }

    private JSONArray buildSubScenarioGroups(Map<String, LinkedHashSet<String>> subScenariosByScenario) {
        JSONArray groups = new JSONArray();
        for (Map.Entry<String, LinkedHashSet<String>> entry : subScenariosByScenario.entrySet()) {
            List<String> ordered = new ArrayList<String>(entry.getValue());
            Collections.sort(ordered);
            for (int index = 0; index < ordered.size(); index += SUBSCENARIO_GROUP_SIZE) {
                int end = Math.min(index + SUBSCENARIO_GROUP_SIZE, ordered.size());
                JSONObject item = new JSONObject(new LinkedHashMap<String, Object>());
                item.put("scenario_id", restoreNullToken(entry.getKey()));
                item.put("group_no", index / SUBSCENARIO_GROUP_SIZE + 1);
                item.put("sub_scenario_start", restoreNullToken(ordered.get(index)));
                item.put("sub_scenario_end", restoreNullToken(ordered.get(end - 1)));
                item.put("sub_scenario_count", end - index);
                groups.add(item);
            }
        }
        return groups;
    }

    private JSONObject toDetailRow(ScenarioGeneratedRecord record) {
        JSONObject item = new JSONObject(new LinkedHashMap<String, Object>());
        item.put("scenario_id", trimToNull(record.getScenarioId()));
        item.put("sub_scenario_id", trimToNull(record.getSubScenarioId()));
        item.put("scenario_name", trimToNull(record.getScenarioName()));
        item.put("scenario_type", trimToNull(record.getScenarioType()));
        item.put("risk_group_id", trimToNull(record.getRiskGroupId()));
        item.put("curve_type", trimToNull(record.getCurveType()));
        item.put("curve_id", trimToNull(record.getCurveCode()));
        item.put("data_date", record.getDataDate() == null ? null : record.getDataDate().format(DATE_8_FORMATTER));
        item.put("term_code", trimToNull(record.getTermCode()));
        item.put("term_days", record.getTermDays());
        item.put("dimension2", trimToNull(record.getDimension2()));
        item.put("original_value", toPlainString(record.getOriginalValue()));
        item.put("changed_value", toPlainString(record.getChangedValue()));
        item.put("shift_value", toPlainString(record.getShiftValue()));
        item.put("shift_rule", trimToNull(record.getShiftRule()));
        item.put("rfet_bucket_id", trimToNull(record.getRfetBucketId()));
        item.put("rfet_modellable", record.getRfetModellable());
        item.put("rfet_reduced_set", record.getRfetReducedSet());
        item.put("modifier", trimToNull(record.getModifier()));
        return item;
    }

    private JSONArray readArrayHash(String key, String fieldValue) {
        Object rawValue = redisTemplate.opsForHash().get(key, encodeToken(fieldValue));
        String raw = rawValue == null ? null : String.valueOf(rawValue);
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONArray();
        }
        return restoreArray(JSON.parseArray(raw));
    }

    private JSONArray readArrayValue(String key) {
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONArray();
        }
        return restoreArray(JSON.parseArray(raw));
    }

    private void expireKey(String key) {
        redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
    }

    private static void addToMapSet(Map<String, LinkedHashSet<String>> mapping, String key, String value) {
        mapping.computeIfAbsent(key, k -> new LinkedHashSet<String>()).add(value);
    }

    private static String joinKey(String... values) {
        StringBuilder builder = new StringBuilder();
        if (values != null) {
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    builder.append('|');
                }
                builder.append(safeToken(values[index]));
            }
        }
        return builder.toString();
    }

    private static String buildKey(String runId, String... segments) {
        StringBuilder builder = new StringBuilder(KEY_PREFIX)
                .append(':')
                .append(encodeToken(runId));
        if (segments != null) {
            for (String segment : segments) {
                builder.append(':').append(segment);
            }
        }
        return builder.toString();
    }

    private static String requireRunId(String runId) {
        return requireText(runId, "runId 不能为空");
    }

    private static String requireText(String text, String message) {
        String value = trimToNull(text);
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String toPlainString(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String safeToken(String text) {
        String value = trimToNull(text);
        return value == null ? NULL_TOKEN : value;
    }

    private static String restoreNullToken(String text) {
        return NULL_TOKEN.equals(text) ? null : text;
    }

    private static String encodeToken(String text) {
        String safe = safeToken(text);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> restoreList(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(restoreNullToken(value));
        }
        return result;
    }

    private static JSONArray restoreArray(JSONArray values) {
        JSONArray result = new JSONArray();
        if (values == null) {
            return result;
        }
        for (Object value : values) {
            if (value == null) {
                result.add(null);
                continue;
            }
            result.add(restoreNullToken(String.valueOf(value)));
        }
        return result;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
