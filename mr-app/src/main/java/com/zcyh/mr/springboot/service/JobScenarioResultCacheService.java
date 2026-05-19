package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 异步 Job 情景结果缓存服务。
 */
@Service
public class JobScenarioResultCacheService {
    private static final Logger log = LoggerFactory.getLogger(JobScenarioResultCacheService.class);
    private static final String KEY_PREFIX = "JOB:SCENARIO_RESULT:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public JobScenarioResultCacheService(StringRedisTemplate redisTemplate,
                                         @Value("${mr.job.scenario-result.redis.ttl-seconds:${mr.result.redis.ttl-seconds:3600}}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds <= 0 ? 3600L : ttlSeconds;
    }

    public void putScenarioResult(String jobId, JSONArray scenarioResult) {
        String key = buildKey(jobId);
        JSONArray safeResult = scenarioResult == null ? new JSONArray() : scenarioResult;
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(safeResult), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("写入 Job 情景结果缓存失败，jobId={}, key={}, error={}", jobId, key, ex.getMessage());
            throw new IllegalStateException("写入 Job 情景结果缓存失败: " + jobId, ex);
        }
    }

    public JSONArray getScenarioResult(String jobId) {
        String key = buildKey(jobId);
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("读取 Job 情景结果缓存失败，jobId={}, key={}, error={}", jobId, key, ex.getMessage());
            throw new IllegalStateException("读取 Job 情景结果缓存失败: " + jobId, ex);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return JSON.parseArray(raw);
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    private static String buildKey(String jobId) {
        String safeJobId = trimToNull(jobId);
        if (safeJobId == null) {
            throw new IllegalArgumentException("jobId 不能为空");
        }
        return KEY_PREFIX + safeJobId;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
