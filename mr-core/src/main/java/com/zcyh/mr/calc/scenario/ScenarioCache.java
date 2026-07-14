package com.zcyh.mr.calc.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.zcyh.mr.loader.Loader;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

/**
 * 场景数据缓存。
 * 保存已解析的 ScenarioEntry 列表，供后续 Calc 通过 cache_key 获取。
 * 当前实现为进程内 ConcurrentHashMap，未来可替换为 Redis 等分布式缓存。
 */
public class ScenarioCache {

    private static final ScenarioFileReader FILE_READER = new ScenarioFileReader();

    private static final ConcurrentHashMap<String, List<Loader.ScenarioEntry>> CACHE =
            new ConcurrentHashMap<>();
    private static final Queue<String> CACHE_KEY_ORDER = new ConcurrentLinkedQueue<>();

    private static final int MAX_SCENARIO_CACHE_ENTRIES = Math.max(
            1, Integer.getInteger("mr.scenario.cache.max-entries", 512));

    /**
     * 从 CSV 场景文件加载并缓存场景数据。
     *
     * @param filePath  场景文件路径
     * @param dataDate  基准日期
     * @return cache_key（基于文件名生成）
     */
    public static String loadFromFile(String filePath, LocalDate dataDate) {
        Path path = Paths.get(filePath);
        String cacheKey = deriveCacheKey(path);

        if (CACHE.containsKey(cacheKey)) {
            return cacheKey;
        }

        JSONArray scenArray = FILE_READER.read(path);
        List<Loader.ScenarioEntry> entries = FILE_READER.parseScenarioList(scenArray, dataDate);
        putScenarioEntries(cacheKey, entries);
        return cacheKey;
    }

    /**
     * 从多个情景文件加载并合并缓存。
     *
     * @param cacheKey   缓存键
     * @param filePaths  场景文件路径列表
     * @param dataDate   基准日期
     * @return cache_key
     */
    public static String loadFromFiles(String cacheKey, List<String> filePaths, LocalDate dataDate) {
        String safeCacheKey = trimToNull(cacheKey);
        if (safeCacheKey == null) {
            throw new IllegalArgumentException("scenario cache_key 不能为空");
        }
        if (CACHE.containsKey(safeCacheKey)) {
            return safeCacheKey;
        }
        if (filePaths == null || filePaths.isEmpty()) {
            throw new IllegalArgumentException("scenario 文件列表不能为空, cache_key=" + safeCacheKey);
        }

        JSONArray merged = new JSONArray();
        for (String filePath : filePaths) {
            String safeFilePath = trimToNull(filePath);
            if (safeFilePath == null) {
                continue;
            }
            merged.addAll(FILE_READER.read(Paths.get(safeFilePath)));
        }
        List<Loader.ScenarioEntry> entries = FILE_READER.parseScenarioList(merged, dataDate);
        putScenarioEntries(safeCacheKey, entries);
        return safeCacheKey;
    }

    /**
     * 直接将 scenario_data JSONArray 加载到缓存。
     * 用于批处理层已组装好场景数据的场景。
     *
     * @param cacheKey   缓存键
     * @param scenData   scenario_data JSON 数组
     * @param dataDate   基准日期
     */
    public static void loadFromArray(String cacheKey, JSONArray scenData, LocalDate dataDate) {
        if (CACHE.containsKey(cacheKey)) {
            return;
        }
        List<Loader.ScenarioEntry> entries = FILE_READER.parseScenarioList(scenData, dataDate);
        putScenarioEntries(cacheKey, entries);
    }

    /**
     * 通过 cache_key 获取场景列表。
     *
     * @param cacheKey 缓存键
     * @return 场景条目列表，不存在时返回 null
     */
    public static List<Loader.ScenarioEntry> get(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return null;
        }
        return CACHE.get(cacheKey);
    }

    /**
     * 检查缓存中是否存在指定 key。
     */
    public static boolean contains(String cacheKey) {
        return cacheKey != null && CACHE.containsKey(cacheKey);
    }

    /**
     * 移除指定缓存。
     */
    public static void evict(String cacheKey) {
        if (cacheKey != null) {
            CACHE.remove(cacheKey);
        }
    }

    /**
     * 清理指定批次的情景缓存。
     */
    public static void evictByBatchId(String batchId) {
        String safeBatchId = trimToNull(batchId);
        if (safeBatchId == null) {
            return;
        }
        String batchToken = ":" + safeBatchId + ":";
        CACHE.keySet().removeIf(key -> key != null && key.contains(batchToken));
    }

    /**
     * 清空所有缓存。
     */
    public static void clear() {
        CACHE.clear();
        CACHE_KEY_ORDER.clear();
    }

    /**
     * 直接存入已解析的场景条目列表。
     */
    public static void put(String cacheKey, List<Loader.ScenarioEntry> entries) {
        putScenarioEntries(cacheKey, entries);
    }

    public static int scenarioEntryCacheSize() {
        return CACHE.size();
    }

    private static void putScenarioEntries(String cacheKey, List<Loader.ScenarioEntry> entries) {
        if (cacheKey == null) {
            throw new IllegalArgumentException("scenario cache_key 不能为空");
        }
        boolean existed = CACHE.containsKey(cacheKey);
        CACHE.put(cacheKey, Collections.unmodifiableList(new ArrayList<>(entries)));
        if (!existed) {
            CACHE_KEY_ORDER.offer(cacheKey);
            trimCache(CACHE, CACHE_KEY_ORDER, MAX_SCENARIO_CACHE_ENTRIES);
        }
    }

    private static void trimCache(ConcurrentHashMap<String, ?> cache, Queue<String> order, int maxEntries) {
        while (cache.size() > maxEntries) {
            String key = order.poll();
            if (key == null) {
                return;
            }
            cache.remove(key);
        }
    }

    /**
     * 返回当前缓存的 key 数量。
     */
    public static int size() {
        return CACHE.size();
    }

    /**
     * 从文件路径推导缓存键（使用文件名去掉扩展名）。
     */
    private static String deriveCacheKey(Path path) {
        String fileName = path.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        return dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
