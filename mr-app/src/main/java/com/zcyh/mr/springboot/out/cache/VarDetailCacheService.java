package com.zcyh.mr.springboot.out.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * VaR 维度明细缓存服务。
 */
@Service
public class VarDetailCacheService {
    private static final Logger log = LoggerFactory.getLogger(VarDetailCacheService.class);
    private static final String KEY_PREFIX = "VAR:DETAIL";
    private static final String NULL_TOKEN = "__NULL__";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public VarDetailCacheService(StringRedisTemplate redisTemplate,
                                 @Value("${mr.var.detail.redis.ttl-seconds:${mr.result.redis.ttl-seconds:3600}}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds <= 0 ? 3600L : ttlSeconds;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public boolean putDimensionDetail(String requestId,
                                      String quantile,
                                      String ruleId,
                                      String scenarioId,
                                      String groupType,
                                      String groupValue,
                                      JSONObject detail) {
        String key = buildKey(requestId, quantile, ruleId, scenarioId, groupType, groupValue);
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(detail), ttlSeconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            log.warn("写入 VaR 维度缓存失败，key={}, error={}", key, ex.getMessage());
            return false;
        }
    }

    public JSONObject getDimensionDetail(String requestId,
                                         String quantile,
                                         String ruleId,
                                         String scenarioId,
                                         String groupType,
                                         String groupValue) {
        String key = buildKey(requestId, quantile, ruleId, scenarioId, groupType, groupValue);
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("读取 VaR 维度缓存失败，key={}, error={}", key, ex.getMessage());
            throw new IllegalStateException("读取 VaR 维度缓存失败", ex);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return JSON.parseObject(raw);
    }

    private static String buildKey(String requestId,
                                   String quantile,
                                   String ruleId,
                                   String scenarioId,
                                   String groupType,
                                   String groupValue) {
        return KEY_PREFIX
                + ":" + encodeToken(requestId)
                + ":" + encodeToken(quantile)
                + ":" + encodeToken(ruleId)
                + ":" + encodeToken(scenarioId)
                + ":" + encodeToken(groupType)
                + ":" + encodeToken(groupValue);
    }

    private static String encodeToken(String text) {
        String safe = trimToNull(text);
        if (safe == null) {
            safe = NULL_TOKEN;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
