package com.zcyh.mr.calc;

import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.marketdata.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 风险因子匹配器。
 * 建立"市场数据曲线 ↔ 交易"的依赖关系索引，
 * 并在场景估值时逐场景判断哪些交易受影响，实现交易级快速跳过。
 *
 * 三层核心逻辑：
 * 1. buildIndex       — 从基准市场数据构建别名索引（TYPE:ID ↔ 标准键）
 * 2. buildPerTradeKeys — 读取每笔交易的 _MARKET_DATA_KEYS 风险因子声明
 * 3. resolveAffected   — 场景 × 交易匹配，返回受影响的交易 ID 集合
 */
public final class RiskFactorMatcher {

    private static final Logger log = LoggerFactory.getLogger(RiskFactorMatcher.class);
    private static final String EXPLICIT_MARKET_DATA_KEYS_FIELD = "_MARKET_DATA_KEYS";

    private RiskFactorMatcher() {}

    /* ========== 数据结构定义 ========== */

    /**
     * 风险因子别名索引：alias → 标准键集合。
     * 同时注册 "TYPE:ID" （canonical）和 "ID"（短名），
     * 使场景 impact keys 无论用哪种写法都能匹配。
     */
    public static final class Index {
        final Map<String, Set<String>> aliasToCanonical = new HashMap<>();
    }

    /**
     * 场景影响解析结果。
     * canonicalKeys 为场景 impact keys 成功解析后的标准键；
     * hasUnresolved = true 表示存在无法识别的 key，应全量重估。
     */
    public static final class ScenarioImpactResolution {
        final Set<String> canonicalKeys = new LinkedHashSet<>();
        boolean hasUnresolved = false;
    }

    /* ========== 第一层：构建索引 ========== */

    /**
     * 从基准市场数据构建风险因子别名索引。
     */
    public static Index buildIndex(MarketData md) {
        Index index = new Index();
        if (md == null) {
            return index;
        }
        registerIrSpotKeys(index, md);
        registerTypedKeys(index, EngineConstants.RF_TYPE.IR_VOL, md.irVol == null ? null : md.irVol.keySet());
        registerTypedKeys(index, EngineConstants.RF_TYPE.EQ_SPOT, md.eqSpot == null ? null : md.eqSpot.keySet());
        registerTypedKeys(index, EngineConstants.RF_TYPE.EQ_VOL, md.eqVol == null ? null : md.eqVol.keySet());
        registerTypedKeys(index, EngineConstants.RF_TYPE.FX_VOL, md.fxVol == null ? null : md.fxVol.keySet());
        registerTypedKeys(index, EngineConstants.RF_TYPE.COMM_SPOT, md.commSpot == null ? null : md.commSpot.keySet());
        registerTypedKeys(index, EngineConstants.RF_TYPE.COMM_VOL, md.commVol == null ? null : md.commVol.keySet());
        registerTypedKeys(index, EngineConstants.RF_TYPE.FIXING, md.fixingRate == null ? null : md.fixingRate.keySet());
        registerFxSpotKeys(index, md);
        return index;
    }

    /* ========== 第二层：交易 → 引用键映射 ========== */
    /**
     * 逐笔交易构建风险因子引用映射：INSTRUMENT_ID → 该交易引用的标准键集合。
     * _MARKET_DATA_KEYS 未提供时，默认该交易对全部风险因子有效。
     * _MARKET_DATA_KEYS 已提供但解析为空时，表示该交易无有效风险因子。
     */
    public static Map<String, Set<String>> buildPerTradeKeys(
            List<HashMap<String, Object>> trades, Index index) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (trades == null || trades.isEmpty() || index == null || index.aliasToCanonical.isEmpty()) {
            return result;
        }
        for (HashMap<String, Object> trade : trades) {
            if (trade == null) continue;
            String instrumentId = Objects.toString(trade.get("INSTRUMENT_ID"), "");
            Set<String> keys;
            if (!trade.containsKey(EXPLICIT_MARKET_DATA_KEYS_FIELD)) {
                keys = allCanonicalKeys(index);
            } else {
                keys = readExplicitMarketDataKeys(trade, index);
                if (keys.isEmpty()) {
                    log.warn("交易 _MARKET_DATA_KEYS 已提供但无有效风险因子，instrumentId={}", instrumentId);
                }
            }
            result.put(instrumentId, keys);
        }
        return result;
    }

    /**
     * 构建“风险因子 -> 交易ID集合”反向索引，便于场景直接命中受影响交易。
     */
    public static Map<String, Set<String>> buildFactorToTradeIndex(Map<String, Set<String>> perTradeKeys) {
        Map<String, Set<String>> factorToTradeIds = new LinkedHashMap<>();
        if (perTradeKeys == null || perTradeKeys.isEmpty()) {
            return factorToTradeIds;
        }
        for (Map.Entry<String, Set<String>> entry : perTradeKeys.entrySet()) {
            String instrumentId = normalize(entry.getKey());
            if (instrumentId.isEmpty() || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            for (String key : entry.getValue()) {
                factorToTradeIds.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        return factorToTradeIds;
    }

    /**
     * 读取批量层注入的 _MARKET_DATA_KEYS。
     * 该字段是交易风险因子声明的主来源，不再扫描交易其他字段。
     */
    static Set<String> readExplicitMarketDataKeys(Map<String, Object> trade, Index index) {
        Set<String> used = new LinkedHashSet<>();
        if (trade == null || index == null || index.aliasToCanonical.isEmpty()) {
            return used;
        }
        Object rawKeys = trade.get(EXPLICIT_MARKET_DATA_KEYS_FIELD);
        if (rawKeys == null) {
            return used;
        }
        collectExplicitKeys(rawKeys, index, used);
        return used;
    }

    /* ========== 第三层：场景 × 交易匹配 ========== */

    /**
     * 将场景的 impact keys 解析为 canonical key。
     * 存在无法识别的 key 时，标记 hasUnresolved，调用方应全量重估。
     */
    public static ScenarioImpactResolution resolveScenarioKeys(
            Set<String> rawImpactKeys, Index index) {
        ScenarioImpactResolution result = new ScenarioImpactResolution();
        if (rawImpactKeys == null || rawImpactKeys.isEmpty()) {
            return result;
        }
        for (String raw : rawImpactKeys) {
            String token = normalize(raw);
            if (token.isEmpty()) {
                continue;
            }
            Set<String> mapped = (index == null) ? null : index.aliasToCanonical.get(token);
            if (mapped == null || mapped.isEmpty()) {
                result.hasUnresolved = true;
                continue;
            }
            result.canonicalKeys.addAll(mapped);
        }
        return result;
    }

    /**
     * 根据逐笔交易的引用键和场景的 impact resolution，判定哪些交易受影响。
     * 返回 null：无法判断（场景缺少 impact 信息），应全量重估。
     * 返回空集合：无交易受影响，可跳过。
     */
    public static Set<String> resolveAffectedTradeIds(
            Map<String, Set<String>> perTradeKeys,
            ScenarioImpactResolution scenarioImpact) {
        if (scenarioImpact == null || scenarioImpact.canonicalKeys.isEmpty()
                || scenarioImpact.hasUnresolved) {
            return null;
        }
        Set<String> affected = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : perTradeKeys.entrySet()) {
            if (!Collections.disjoint(entry.getValue(), scenarioImpact.canonicalKeys)) {
                affected.add(entry.getKey());
            }
        }
        return affected;
    }

    /**
     * 使用“风险因子 -> 交易ID集合”反向索引直接解析受影响交易，避免每个情景都全量扫交易。
     */
    public static Set<String> resolveAffectedTradeIdsFast(
            Map<String, Set<String>> factorToTradeIds,
            ScenarioImpactResolution scenarioImpact) {
        if (scenarioImpact == null || scenarioImpact.canonicalKeys.isEmpty()
                || scenarioImpact.hasUnresolved) {
            return null;
        }
        Set<String> affected = new LinkedHashSet<>();
        if (factorToTradeIds == null || factorToTradeIds.isEmpty()) {
            return affected;
        }
        for (String canonicalKey : scenarioImpact.canonicalKeys) {
            Set<String> tradeIds = factorToTradeIds.get(canonicalKey);
            if (tradeIds != null && !tradeIds.isEmpty()) {
                affected.addAll(tradeIds);
            }
        }
        return affected;
    }

    /* ========== 内部辅助方法 ========== */

    private static void registerTypedKeys(Index index, String type, Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            String canonical = canonicalKey(type, id);
            if (canonical.isEmpty()) {
                continue;
            }
            registerAlias(index, canonical, canonical);
            registerAlias(index, id, canonical);
            /*
             * 同时注册类型级别别名，兼容批量层注入的通用键（例如 FX_SPOT）。
             * 这样场景与交易都可以使用“TYPE”或“TYPE:ID”两种形式匹配。
             */
            registerAlias(index, type, canonical);
        }
    }

    private static void registerIrSpotKeys(Index index, MarketData md) {
        if (md == null || md.irSpot == null || md.irSpot.isEmpty()) {
            return;
        }
        for (Map.Entry<String, com.zcyh.mr.marketdata.IrSpot.IrSpotInfo> entry : md.irSpot.entrySet()) {
            com.zcyh.mr.marketdata.IrSpot.IrSpotInfo info = entry.getValue();
            if (info == null || info.curveType == null) {
                continue;
            }
            if (EngineConstants.RF_TYPE.IR_SPOT.equals(info.curveType)
                    || EngineConstants.RF_TYPE.CREDIT_SPOT.equals(info.curveType)) {
                registerTypedKeys(index, info.curveType, Collections.singleton(entry.getKey()));
            }
        }
    }

    /**
     * FX_SPOT 按通用外汇风险组处理。
     * 交易侧只要出现任意非 CNY 币种，即视为存在 FX 风险暴露；
     * 场景侧只要出现任意 FX_SPOT 情景，也统一映射到通用 FX_SPOT。
     */
    private static void registerFxSpotKeys(Index index, MarketData md) {
        if (md == null || md.fxSpot == null || md.fxSpot.curveData == null || md.fxSpot.curveData.isEmpty()) {
            return;
        }
        registerAlias(index, EngineConstants.RF_TYPE.FX_SPOT, EngineConstants.RF_TYPE.FX_SPOT);
        for (String ccyPair : md.fxSpot.curveData.keySet()) {
            String pair = normalize(ccyPair);
            if (pair.isEmpty()) {
                continue;
            }
            registerAlias(index, pair, EngineConstants.RF_TYPE.FX_SPOT);
            registerAlias(index, canonicalKey(EngineConstants.RF_TYPE.FX_SPOT, pair), EngineConstants.RF_TYPE.FX_SPOT);
            String[] parts = pair.split("/");
            for (String part : parts) {
                if (!part.isEmpty() && !"CNY".equals(part)) {
                    registerAlias(index, part, EngineConstants.RF_TYPE.FX_SPOT);
                }
            }
        }
    }

    /**
     * 返回索引中全部标准风险因子，用于未提供 _MARKET_DATA_KEYS 的交易。
     */
    private static Set<String> allCanonicalKeys(Index index) {
        Set<String> all = new LinkedHashSet<>();
        if (index == null || index.aliasToCanonical == null || index.aliasToCanonical.isEmpty()) {
            return all;
        }
        for (Set<String> mapped : index.aliasToCanonical.values()) {
            if (mapped != null) {
                all.addAll(mapped);
            }
        }
        return all;
    }

    /**
     * 递归读取显式市场数据键数组。
     */
    private static void collectExplicitKeys(Object rawKeys, Index index, Set<String> used) {
        if (rawKeys == null) {
            return;
        }
        if (rawKeys instanceof CharSequence) {
            String token = normalize(rawKeys.toString());
            if (!token.isEmpty()) {
                Set<String> mapped = index.aliasToCanonical.get(token);
                if (mapped != null) {
                    used.addAll(mapped);
                }
            }
            return;
        }
        if (rawKeys instanceof Collection<?>) {
            for (Object item : (Collection<?>) rawKeys) {
                collectExplicitKeys(item, index, used);
            }
            return;
        }
        if (rawKeys.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(rawKeys);
            for (int i = 0; i < len; i++) {
                collectExplicitKeys(java.lang.reflect.Array.get(rawKeys, i), index, used);
            }
        }
    }

    private static void registerAlias(Index index, String alias, String canonical) {
        String aliasNorm = normalize(alias);
        String canonicalNorm = normalize(canonical);
        if (aliasNorm.isEmpty() || canonicalNorm.isEmpty()) {
            return;
        }
        index.aliasToCanonical.computeIfAbsent(aliasNorm, ignored -> new LinkedHashSet<>()).add(canonicalNorm);
    }

    private static String canonicalKey(String type, String id) {
        String typeNorm = normalize(type);
        String idNorm = normalize(id);
        if (typeNorm.isEmpty() || idNorm.isEmpty()) {
            return "";
        }
        return typeNorm + ":" + idNorm;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
