package com.zcyh.mr.springboot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易分片策略。
 * 按产品代码分组后，根据权重预算将交易拆分为多个 chunk。
 */
@Component
public class TradeChunkSplitter {

    private final int defaultWeight;
    private final Map<String, Integer> productWeightRules;

    public TradeChunkSplitter(
            @Value("${mr.batch.weight-default:5}") int defaultWeight,
            @Value("${mr.batch.product-weight-rules:}") String weightRulesText
    ) {
        this.defaultWeight = normalizeWeight(defaultWeight);
        this.productWeightRules = parseWeightRules(weightRulesText);
    }

    /**
     * 按产品代码分组并按权重预算拆分交易列表为多个 chunk。
     */
    public List<List<BatchTradeDataLoader.TradeRow>> splitChunks(
            List<BatchTradeDataLoader.TradeRow> trades, int maxWeightBudget) {
        List<List<BatchTradeDataLoader.TradeRow>> chunks = new ArrayList<>();
        if (trades == null || trades.isEmpty()) {
            return chunks;
        }

        // 先按产品代码分组
        Map<String, List<BatchTradeDataLoader.TradeRow>> grouped = new LinkedHashMap<>();
        for (BatchTradeDataLoader.TradeRow trade : trades) {
            String productCode = normalizeProductCode(trade.productCode);
            grouped.computeIfAbsent(productCode, k -> new ArrayList<>()).add(trade);
        }

        // 每个产品组内按权重预算切分
        for (Map.Entry<String, List<BatchTradeDataLoader.TradeRow>> entry : grouped.entrySet()) {
            chunks.addAll(splitSingleGroup(entry.getValue(), maxWeightBudget));
        }
        return chunks;
    }

    private List<List<BatchTradeDataLoader.TradeRow>> splitSingleGroup(
            List<BatchTradeDataLoader.TradeRow> productTrades, int maxWeightBudget) {
        List<List<BatchTradeDataLoader.TradeRow>> chunks = new ArrayList<>();
        if (productTrades == null || productTrades.isEmpty()) {
            return chunks;
        }

        List<BatchTradeDataLoader.TradeRow> currentChunk = new ArrayList<>();
        int currentWeight = 0;
        for (BatchTradeDataLoader.TradeRow trade : productTrades) {
            int tradeWeight = resolveWeight(trade.productCode);
            if (!currentChunk.isEmpty() && currentWeight + tradeWeight > maxWeightBudget) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentWeight = 0;
            }
            currentChunk.add(trade);
            currentWeight += tradeWeight;
            if (currentWeight >= maxWeightBudget) {
                chunks.add(currentChunk);
                currentChunk = new ArrayList<>();
                currentWeight = 0;
            }
        }
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk);
        }
        return chunks;
    }

    private int resolveWeight(String productCode) {
        String key = productCode == null ? null : productCode.trim().toUpperCase();
        Integer weight = key == null ? null : productWeightRules.get(key);
        if (weight == null) {
            return defaultWeight;
        }
        return normalizeWeight(weight);
    }

    private static String normalizeProductCode(String productCode) {
        if (productCode == null || productCode.trim().isEmpty()) {
            return "UNKNOWN";
        }
        return productCode.trim();
    }

    static int normalizeWeight(int weight) {
        if (weight < 1) {
            return 1;
        }
        if (weight > 10) {
            return 10;
        }
        return weight;
    }

    static Map<String, Integer> parseWeightRules(String txt) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (txt == null || txt.trim().isEmpty()) {
            return map;
        }
        String[] pairs = txt.split(",");
        for (String pair : pairs) {
            if (pair == null) {
                continue;
            }
            String item = pair.trim();
            if (item.isEmpty()) {
                continue;
            }
            int idx = item.indexOf('=');
            if (idx <= 0 || idx >= item.length() - 1) {
                continue;
            }
            String key = item.substring(0, idx).trim().toUpperCase();
            String val = item.substring(idx + 1).trim();
            try {
                map.put(key, normalizeWeight(Integer.parseInt(val)));
            } catch (NumberFormatException ignore) {
                // 忽略非法权重配置
            }
        }
        return map;
    }
}
