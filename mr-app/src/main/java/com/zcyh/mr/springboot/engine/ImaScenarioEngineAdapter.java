package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.model.NmrfPnlRecord;
import com.zcyh.mr.frtbima.model.SubsetPnlRecord;
import com.zcyh.mr.frtbima.scenariopnl.NmrfScenarioRunner;
import com.zcyh.mr.frtbima.scenariopnl.SubsetScenarioRunner;
import com.zcyh.mr.frtbima.rfet.bucket.RfetModellableIndex;
import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.springboot.service.ImaModellablePnlPersistService;
import com.zcyh.mr.springboot.service.ImaNmrfPnlPersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * IMA Phase1 引擎适配器。
 *
 * <p>职责：
 * <ol>
 *   <li>接收包含 baseCalcJson / baseMarketData / modellableIndex /
 *       scenarioEntries / nmrfBuckets / stressScenarios 的输入 JSON。</li>
 *   <li>调用 {@link SubsetScenarioRunner} 完成可建模因子情景重定价（Phase1-A）。</li>
 *   <li>调用 {@link NmrfScenarioRunner} 完成 NMRF 桶 UP/DOWN 重定价（Phase1-B）。</li>
 *   <li>通过 PersistService 将结果分别写入 Doris 两张结果表。</li>
 * </ol>
 *
 * <p><b>Reduced Set 优化</b>：如果 RfetModellableIndex 中所有可建模桶均属于
 * Reduced Set（isAllReducedSet=true），则 NORMAL_FULL 与 NORMAL_REDUCED 的
 * 冲击因子集完全相同，跳过 NORMAL_REDUCED 的 Calc.run()，直接复制 NORMAL_FULL
 * 结果并改写 scenarioType，节省约 33% Phase1 计算量。
 *
 * <p>输入 JSON 关键字段：
 * <pre>
 * {
 *   "batch_id": "...",
 *   "job_id": "...",
 *   "request_id": "...",
 *   "data_date": "20260409",
 *   "scenario_type": "STRESS_REDUCED",   // 情景类型（STRESS_REDUCED/NORMAL_FULL/NORMAL_REDUCED）
 *   "scenario_id": "IMA_HIST_2024",      // 情景集ID
 *   "base_calc_json": "...",              // 基准 Calc JSON 模板
 *   "modellable_index_cache_key": "...",  // ScenarioCache 中 RfetModellableIndex 键
 *   "scenario_cache_key": "...",          // ScenarioCache 中可建模情景条目键
 *   "nmrf_scenario_cache_key": "...",     // ScenarioCache 中 NMRF 压力情景条目键
 *   "nmrf_buckets_cache_key": "..."       // ScenarioCache 中 NmrfBucketMeta 列表键
 * }
 * </pre>
 *
 * <p>调用方（BatchJobService/EngineOrchestratorService）负责在调用前将上述数据
 * 存入 {@code ScenarioCache}，适配器只负责读取 + 计算 + 落库。
 */
public class ImaScenarioEngineAdapter implements EngineAdapter {

    public static final String CODE = "ima_scenario";

    private static final Logger log = LoggerFactory.getLogger(ImaScenarioEngineAdapter.class);

    private final LiquidityHorizonTable lhTable;
    private final ImaModellablePnlPersistService modellablePersistService;
    private final ImaNmrfPnlPersistService nmrfPersistService;

    public ImaScenarioEngineAdapter(LiquidityHorizonTable lhTable,
                                    ImaModellablePnlPersistService modellablePersistService,
                                    ImaNmrfPnlPersistService nmrfPersistService) {
        this.lhTable = lhTable;
        this.modellablePersistService = modellablePersistService;
        this.nmrfPersistService = nmrfPersistService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "IMA Phase1：可建模情景 PnL 重定价 + NMRF 桶 UP/DOWN 重定价";
    }

    @Override
    public String calculate(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("IMA Phase1 input 不能为空");
        }

        String batchId    = required(req, "batch_id");
        String jobId      = req.getString("job_id");
        String requestId  = req.getString("request_id");
        String dataDate   = required(req, "data_date");
        String scenType   = req.getString("scenario_type");
        String scenId     = req.getString("scenario_id");
        String opCode     = req.getString("op_code");
        String baseCalcJson = required(req, "base_calc_json");

        // Phase1-A: 可建模因子情景重定价
        String scenarioCacheKey = req.getString("scenario_cache_key");
        String modellableIndexCacheKey = req.getString("modellable_index_cache_key");
        // 兼容旧字段名
        if (modellableIndexCacheKey == null) {
            modellableIndexCacheKey = req.getString("modellable_tenors_cache_key");
        }

        int modellableCount = 0;
        if (scenarioCacheKey != null && modellableIndexCacheKey != null) {
            List<Loader.ScenarioEntry> scenEntries = loadScenarioEntries(scenarioCacheKey);
            MarketData baseMarketData = loadBaseMarketData(req);
            RfetModellableIndex modellableIndex = loadModellableIndex(modellableIndexCacheKey);

            String effectiveScenType = scenType != null ? scenType : ImaConstants.SCENARIO_TYPE_NORMAL_FULL;

            // Reduced Set 优化：NORMAL_REDUCED 且所有桶都在 Reduced Set 中 → 跳过重定价
            if (ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED.equals(effectiveScenType)
                    && modellableIndex.isAllReducedSet()) {
                // 从 NORMAL_FULL 缓存中加载结果并复制为 NORMAL_REDUCED
                String fullResultsCacheKey = req.getString("normal_full_results_cache_key");
                // 自动推导缓存键：编排层未显式传入时，使用 NORMAL_FULL 的标准缓存键
                if (fullResultsCacheKey == null || fullResultsCacheKey.isEmpty()) {
                    fullResultsCacheKey = "ima-full-results-" + batchId;
                }
                List<SubsetPnlRecord> fullRecords = loadPnlRecords(fullResultsCacheKey);

                if (!fullRecords.isEmpty()) {
                    List<SubsetPnlRecord> reducedRecords = new ArrayList<>(fullRecords.size());
                    for (SubsetPnlRecord rec : fullRecords) {
                        reducedRecords.add(rec.copyWithScenarioType(ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED));
                    }
                    modellablePersistService.persist(reducedRecords, opCode);
                    modellableCount = reducedRecords.size();
                    // 释放 NORMAL_FULL 结果缓存，避免内存泄漏
                    com.zcyh.mr.scenario.ScenarioCache.evictObject(fullResultsCacheKey);
                    log.info("IMA Phase1-A 完成（Reduced Set 优化）: batchId={}, 跳过重定价, "
                            + "从 NORMAL_FULL 复制 {} 条记录, 缓存已释放", batchId, modellableCount);
                } else {
                    // 缓存中未找到 NORMAL_FULL 结果，回退到标准重定价
                    log.warn("Reduced Set 优化: NORMAL_FULL 结果缓存为空, 回退到标准重定价流程");
                    List<SubsetPnlRecord> modellableRecords = runSubsetScenario(
                            baseCalcJson, baseMarketData, scenEntries, modellableIndex,
                            effectiveScenType, scenId, batchId, jobId, requestId, dataDate);
                    modellablePersistService.persist(modellableRecords, opCode);
                    modellableCount = modellableRecords.size();
                }
            } else {
                // 标准路径：执行实际重定价
                List<SubsetPnlRecord> modellableRecords = runSubsetScenario(
                        baseCalcJson, baseMarketData, scenEntries, modellableIndex,
                        effectiveScenType, scenId, batchId, jobId, requestId, dataDate);
                modellablePersistService.persist(modellableRecords, opCode);
                modellableCount = modellableRecords.size();

                // NORMAL_FULL 完成后，如果所有桶均在 Reduced Set，缓存结果供后续 NORMAL_REDUCED 复用
                if (ImaConstants.SCENARIO_TYPE_NORMAL_FULL.equals(effectiveScenType)
                        && modellableIndex.isAllReducedSet()) {
                    String cacheKey = "ima-full-results-" + batchId;
                    com.zcyh.mr.scenario.ScenarioCache.putObject(cacheKey, modellableRecords);
                    log.info("IMA Phase1-A: 所有桶均在 Reduced Set, "
                            + "NORMAL_FULL 结果已缓存 (key={}), 后续 NORMAL_REDUCED 可直接复制",
                            cacheKey);
                }
            }
            log.info("IMA Phase1-A 完成: batchId={}, modellableRows={}", batchId, modellableCount);
        }

        // Phase1-B: NMRF 桶重定价
        String nmrfScenarioCacheKey = req.getString("nmrf_scenario_cache_key");
        String nmrfBucketsCacheKey  = req.getString("nmrf_buckets_cache_key");

        int nmrfCount = 0;
        if (nmrfScenarioCacheKey != null && nmrfBucketsCacheKey != null) {
            List<Loader.ScenarioEntry> stressScenarios = loadScenarioEntries(nmrfScenarioCacheKey);
            List<NmrfScenarioRunner.NmrfBucketMeta> nmrfBuckets = loadNmrfBuckets(nmrfBucketsCacheKey);
            MarketData baseMarketData = loadBaseMarketData(req);

            NmrfScenarioRunner nmrfRunner = new NmrfScenarioRunner();
            List<NmrfPnlRecord> nmrfRecords = nmrfRunner.run(
                    baseCalcJson, baseMarketData, nmrfBuckets, stressScenarios,
                    batchId, jobId, requestId, dataDate);

            nmrfPersistService.persist(nmrfRecords, opCode);
            nmrfCount = nmrfRecords.size();
            log.info("IMA Phase1-B 完成: batchId={}, nmrfRows={}", batchId, nmrfCount);
        }

        JSONObject result = new JSONObject();
        result.put("batch_id", batchId);
        result.put("modellable_rows", modellableCount);
        result.put("nmrf_rows", nmrfCount);
        result.put("status", "OK");
        return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    /**
     * 执行子集情景重定价。
     */
    private List<SubsetPnlRecord> runSubsetScenario(String baseCalcJson,
                                                     MarketData baseMarketData,
                                                     List<Loader.ScenarioEntry> scenEntries,
                                                     RfetModellableIndex modellableIndex,
                                                     String scenarioType,
                                                     String scenarioId,
                                                     String batchId,
                                                     String jobId,
                                                     String requestId,
                                                     String dataDate) {
        SubsetScenarioRunner subsetRunner = new SubsetScenarioRunner(lhTable);
        return subsetRunner.run(baseCalcJson, baseMarketData, scenEntries, modellableIndex,
                scenarioType, scenarioId, batchId, jobId, requestId, dataDate);
    }

    // ==================== 数据加载 ====================

    /**
     * 从 ScenarioCache 加载情景条目列表。
     */
    @SuppressWarnings("unchecked")
    private List<Loader.ScenarioEntry> loadScenarioEntries(String cacheKey) {
        Object cached = com.zcyh.mr.scenario.ScenarioCache.get(cacheKey);
        if (cached instanceof List) {
            return (List<Loader.ScenarioEntry>) cached;
        }
        log.warn("ScenarioCache 中未找到情景条目: key={}", cacheKey);
        return Collections.emptyList();
    }

    /**
     * 从请求 JSON 的 base_market_data 字段解析基准市场数据。
     */
    private MarketData loadBaseMarketData(JSONObject req) {
        String mdJson = req.getString("base_market_data");
        if (mdJson == null || mdJson.isEmpty()) {
            log.warn("base_market_data 为空，使用空 MarketData");
            return new MarketData();
        }
        return JSON.parseObject(mdJson, MarketData.class);
    }

    /**
     * 从 ScenarioCache 加载 RfetModellableIndex。
     * 兼容两种缓存格式：RfetModellableIndex 实例 或 List&lt;RfetResult&gt;。
     */
    @SuppressWarnings("unchecked")
    private RfetModellableIndex loadModellableIndex(String cacheKey) {
        Object cached = com.zcyh.mr.scenario.ScenarioCache.getObject(cacheKey);
        if (cached instanceof RfetModellableIndex) {
            return (RfetModellableIndex) cached;
        }
        if (cached instanceof List) {
            return RfetModellableIndex.build((List<RfetResult>) cached);
        }
        log.warn("ScenarioCache 中未找到 modellableIndex: key={}", cacheKey);
        return RfetModellableIndex.build(Collections.emptyList());
    }

    /**
     * 从 ScenarioCache 加载已计算的 PnL 记录列表（Reduced Set 优化用）。
     */
    @SuppressWarnings("unchecked")
    private List<SubsetPnlRecord> loadPnlRecords(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) {
            return Collections.emptyList();
        }
        Object cached = com.zcyh.mr.scenario.ScenarioCache.getObject(cacheKey);
        if (cached instanceof List) {
            return (List<SubsetPnlRecord>) cached;
        }
        return Collections.emptyList();
    }

    /**
     * 从 ScenarioCache 加载 NMRF 桶元数据列表。
     */
    @SuppressWarnings("unchecked")
    private List<NmrfScenarioRunner.NmrfBucketMeta> loadNmrfBuckets(String cacheKey) {
        Object cached = com.zcyh.mr.scenario.ScenarioCache.getObject(cacheKey);
        if (cached instanceof List) {
            return (List<NmrfScenarioRunner.NmrfBucketMeta>) cached;
        }
        log.warn("ScenarioCache 中未找到 nmrfBuckets: key={}", cacheKey);
        return Collections.emptyList();
    }

    private static String required(JSONObject obj, String key) {
        String v = obj.getString(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return v.trim();
    }
}

