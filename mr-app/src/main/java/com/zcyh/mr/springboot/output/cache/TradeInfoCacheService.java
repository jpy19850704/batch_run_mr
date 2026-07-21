package com.zcyh.mr.springboot.output.cache;

import com.zcyh.mr.springboot.input.db.PortfolioHierarchyRow;

import com.zcyh.mr.springboot.input.db.TradeInputRow;
import com.zcyh.mr.springboot.input.trade.TradeAttributeCategory;
import com.zcyh.mr.springboot.input.trade.TradeAttributeDefinition;
import com.zcyh.mr.springboot.input.trade.TradeAttributeRegistry;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 批次交易维度快照缓存服务。
 */
@Service
public class TradeInfoCacheService {
    private static final Logger log = LoggerFactory.getLogger(TradeInfoCacheService.class);
    private static final String KEY_PREFIX = "BATCH:TRADE_INFO:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public TradeInfoCacheService(StringRedisTemplate redisTemplate,
                                 @Value("${mr.job.trade-info.redis.ttl-seconds:${mr.result.redis.ttl-seconds:3600}}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds <= 0 ? 3600L : ttlSeconds;
    }

    public void putBatchTradeInfo(String batchId,
                                  String dataDate,
                                  List<TradeInputRow> trades,
                                  Map<String, PortfolioHierarchyRow> portfolioFlatRows) {
        String safeBatchId = requireText(batchId, "batchId 不能为空");
        JSONObject snapshot = buildSnapshot(safeBatchId, dataDate, trades, portfolioFlatRows);
        String key = buildKey(safeBatchId);
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(snapshot), ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("写入批次交易维度快照缓存失败，batchId={}, key={}, error={}", safeBatchId, key, ex.getMessage());
            throw new IllegalStateException("写入批次交易维度快照缓存失败: " + safeBatchId, ex);
        }
    }

    public JSONObject getBatchTradeInfo(String batchId) {
        String safeBatchId = requireText(batchId, "batchId 不能为空");
        String key = buildKey(safeBatchId);
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("读取批次交易维度快照缓存失败，batchId={}, key={}, error={}", safeBatchId, key, ex.getMessage());
            throw new IllegalStateException("读取批次交易维度快照缓存失败: " + safeBatchId, ex);
        }
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalStateException("批次交易维度快照缓存不存在或已过期: " + safeBatchId);
        }
        return JSON.parseObject(raw);
    }

    private JSONObject buildSnapshot(String batchId,
                                     String dataDate,
                                     List<TradeInputRow> trades,
                                     Map<String, PortfolioHierarchyRow> portfolioFlatRows) {
        JSONArray rows = new JSONArray();
        Set<String> instrumentIds = new LinkedHashSet<String>();
        if (trades != null) {
            for (TradeInputRow trade : trades) {
                JSONObject row = buildTradeInfoRow(trade, portfolioFlatRows);
                String instrumentId = row.getString("instrumentId");
                if (!instrumentIds.add(instrumentId)) {
                    throw new IllegalStateException("批次交易维度快照存在重复 instrumentId: " + instrumentId);
                }
                rows.add(row);
            }
        }

        JSONObject snapshot = new JSONObject();
        snapshot.put("batchId", batchId);
        snapshot.put("dataDate", dataDate);
        JSONArray dimensionFields = new JSONArray();
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions(TradeAttributeCategory.DIMENSION)) {
            dimensionFields.add(definition.getColumnName());
        }
        snapshot.put("dimensionFields", dimensionFields);
        snapshot.put("total", rows.size());
        snapshot.put("rows", rows);
        snapshot.put("cacheTtlSeconds", ttlSeconds);
        snapshot.put("cachedAt", LocalDateTime.now().toString());
        return snapshot;
    }

    private JSONObject buildTradeInfoRow(TradeInputRow trade,
                                         Map<String, PortfolioHierarchyRow> portfolioFlatRows) {
        if (trade == null) {
            throw new IllegalStateException("批次交易维度快照包含空交易行");
        }
        String instrumentId = requireText(trade.instrumentId, "批次交易维度快照缺少 instrumentId");
        JSONObject row = new JSONObject();
        row.put("instrumentId", instrumentId);
        putIfHasText(row, "productCode", trade.productCode);
        for (TradeAttributeDefinition definition : TradeAttributeRegistry.definitions(TradeAttributeCategory.DIMENSION)) {
            putIfHasText(row, definition.getColumnName(), trade.getTextAttribute(definition.getFieldName()));
        }
        String portfolio = trimToNull(trade.getTextAttribute("PORTFOLIO"));
        PortfolioHierarchyRow flatRow = portfolioFlatRows == null ? null : portfolioFlatRows.get(portfolio);
        appendPortfolioHierarchy(row, flatRow);
        return row;
    }

    private static void appendPortfolioHierarchy(JSONObject row, PortfolioHierarchyRow flatRow) {
        if (flatRow == null) {
            return;
        }
        putIfHasText(row, "portfolioCode1", flatRow.portfolioCode1);
        putIfHasText(row, "portfolioCode2", flatRow.portfolioCode2);
        putIfHasText(row, "portfolioCode3", flatRow.portfolioCode3);
        putIfHasText(row, "portfolioCode4", flatRow.portfolioCode4);
        putIfHasText(row, "portfolioCode5", flatRow.portfolioCode5);
        putIfHasText(row, "portfolioCode6", flatRow.portfolioCode6);
        putIfHasText(row, "portfolioCode7", flatRow.portfolioCode7);
    }

    private static void putIfHasText(JSONObject row, String field, String value) {
        String safeValue = trimToNull(value);
        if (safeValue != null) {
            row.put(field, safeValue);
        }
    }

    private static String buildKey(String batchId) {
        return KEY_PREFIX + batchId;
    }

    private static String requireText(String text, String message) {
        String safe = trimToNull(text);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
