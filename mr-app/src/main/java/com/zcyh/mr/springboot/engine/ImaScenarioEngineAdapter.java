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
import com.zcyh.mr.marketdata.CommSpot;
import com.zcyh.mr.marketdata.EqSpot;
import com.zcyh.mr.marketdata.IrSpot;
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
 *   <li>接收包含 baseCalcJson / modellableIndex /
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

    private final ImaModellablePnlPersistService modellablePersistService;
    private final ImaNmrfPnlPersistService nmrfPersistService;

    public ImaScenarioEngineAdapter(ImaModellablePnlPersistService modellablePersistService,
                                    ImaNmrfPnlPersistService nmrfPersistService) {
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

        boolean hasModellableInput = scenarioCacheKey != null || modellableIndexCacheKey != null;
        boolean hasNmrfInput = req.getString("nmrf_scenario_cache_key") != null || req.getString("nmrf_buckets_cache_key") != null;
        if (!hasModellableInput && !hasNmrfInput) {
            throw new IllegalArgumentException("IMA Phase1 必须提供可建模或 NMRF 缓存键");
        }

        int modellableCount = 0;
        if (hasModellableInput) {
            scenarioCacheKey = required(req, "scenario_cache_key");
            modellableIndexCacheKey = required(req, "modellable_index_cache_key");
            List<Loader.ScenarioEntry> scenEntries = loadScenarioEntries(scenarioCacheKey);
            MarketData baseMarketData = loadBaseMarketData(req);
            RfetModellableIndex modellableIndex = loadModellableIndex(modellableIndexCacheKey);
            LiquidityHorizonTable lhTable = loadLiquidityHorizonTable(required(req, "liquidity_horizon_table_cache_key"));

            String effectiveScenType = scenType != null ? scenType : ImaConstants.SCENARIO_TYPE_NORMAL_FULL;

            // Reduced Set 优化：NORMAL_REDUCED 且所有桶都在 Reduced Set 中 → 跳过重定价
            if (ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED.equals(effectiveScenType)
                    && modellableIndex.isAllReducedSet()) {
                // 从 NORMAL_FULL 缓存中加载结果并复制为 NORMAL_REDUCED
                String fullResultsCacheKey = required(req, "normal_full_results_cache_key");
                List<SubsetPnlRecord> fullRecords = loadPnlRecords(fullResultsCacheKey);

                if (fullRecords.isEmpty()) {
                    throw new IllegalStateException("NORMAL_REDUCED 缺少 NORMAL_FULL 缓存结果: " + fullResultsCacheKey);
                }
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
                // 标准路径：执行实际重定价
                List<SubsetPnlRecord> modellableRecords = runSubsetScenario(
                        baseCalcJson, baseMarketData, scenEntries, modellableIndex, lhTable,
                        effectiveScenType, scenId, batchId, jobId, requestId, dataDate);
                if (modellableRecords.isEmpty()) {
                    throw new IllegalStateException("IMA Phase1-A 可建模 PnL 结果为空，batchId=" + batchId
                            + ", scenarioType=" + effectiveScenType);
                }
                modellablePersistService.persist(modellableRecords, opCode);
                modellableCount = modellableRecords.size();

                // NORMAL_FULL 完成后，如果所有桶均在 Reduced Set，缓存结果供后续 NORMAL_REDUCED 复用
                if (ImaConstants.SCENARIO_TYPE_NORMAL_FULL.equals(effectiveScenType)
                        && modellableIndex.isAllReducedSet()) {
                    String cacheKey = required(req, "normal_full_results_cache_key");
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
        if (hasNmrfInput) {
            nmrfScenarioCacheKey = required(req, "nmrf_scenario_cache_key");
            nmrfBucketsCacheKey = required(req, "nmrf_buckets_cache_key");
            List<Loader.ScenarioEntry> stressScenarios = loadScenarioEntries(nmrfScenarioCacheKey);
            List<NmrfScenarioRunner.NmrfBucketMeta> nmrfBuckets = loadNmrfBuckets(nmrfBucketsCacheKey);
            MarketData baseMarketData = loadBaseMarketData(req);
            List<NmrfScenarioRunner.NmrfBucketMeta> activeNmrfBuckets = filterActiveNmrfBuckets(baseMarketData, nmrfBuckets);

            NmrfScenarioRunner nmrfRunner = new NmrfScenarioRunner();
            List<NmrfPnlRecord> nmrfRecords = nmrfRunner.run(
                    baseCalcJson, baseMarketData, activeNmrfBuckets, stressScenarios,
                    batchId, jobId, requestId, dataDate);

            if (!activeNmrfBuckets.isEmpty() && nmrfRecords.isEmpty()) {
                throw new IllegalStateException("IMA Phase1-B NMRF PnL 结果为空，batchId=" + batchId);
            }
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
                                                     LiquidityHorizonTable lhTable,
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
            List<Loader.ScenarioEntry> entries = (List<Loader.ScenarioEntry>) cached;
            if (entries.isEmpty()) {
                throw new IllegalStateException("ScenarioCache 中情景条目为空: key=" + cacheKey);
            }
            return entries;
        }
        throw new IllegalStateException("ScenarioCache 中未找到情景条目: key=" + cacheKey);
    }

    /**
     * 从 base_calc_json 解析基准市场数据。
     */
    private MarketData loadBaseMarketData(JSONObject req) {
        String baseCalcJson = required(req, "base_calc_json");
        return new Loader(baseCalcJson, null).getMarketData();
    }

    /**
     * 从 ScenarioCache 加载 RfetModellableIndex。
     */
    private RfetModellableIndex loadModellableIndex(String cacheKey) {
        Object cached = com.zcyh.mr.scenario.ScenarioCache.getObject(cacheKey);
        if (cached instanceof RfetModellableIndex) {
            return (RfetModellableIndex) cached;
        }
        throw new IllegalStateException("ScenarioCache 中未找到 RfetModellableIndex: key=" + cacheKey);
    }

    private LiquidityHorizonTable loadLiquidityHorizonTable(String cacheKey) {
        Object cached = com.zcyh.mr.scenario.ScenarioCache.getObject(cacheKey);
        if (cached instanceof LiquidityHorizonTable) {
            return (LiquidityHorizonTable) cached;
        }
        throw new IllegalStateException("ScenarioCache 中未找到 LiquidityHorizonTable: key=" + cacheKey);
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
        throw new IllegalStateException("ScenarioCache 中未找到 nmrfBuckets: key=" + cacheKey);
    }

    private List<NmrfScenarioRunner.NmrfBucketMeta> filterActiveNmrfBuckets(
            MarketData baseMarketData,
            List<NmrfScenarioRunner.NmrfBucketMeta> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return Collections.emptyList();
        }
        List<NmrfScenarioRunner.NmrfBucketMeta> active = new ArrayList<NmrfScenarioRunner.NmrfBucketMeta>();
        for (NmrfScenarioRunner.NmrfBucketMeta bucket : buckets) {
            if (bucket != null && hasAnyBasePoint(baseMarketData, bucket)) {
                active.add(bucket);
            }
        }
        return active;
    }

    private boolean hasAnyBasePoint(MarketData marketData, NmrfScenarioRunner.NmrfBucketMeta bucket) {
        if (marketData == null || bucket.tenorDays == null || bucket.tenorDays.isEmpty()) {
            return false;
        }
        for (Integer tenorDays : bucket.tenorDays) {
            if (tenorDays != null && getSpotValue(marketData, bucket.rfType, bucket.curveId, tenorDays) != null) {
                return true;
            }
        }
        return false;
    }

    private Double getSpotValue(MarketData marketData, String rfType, String curveId, int tenorDays) {
        if (ImaConstants.RF_TYPE_IR_SPOT.equals(rfType)) {
            IrSpot.IrSpotInfo info = marketData.irSpot == null ? null : marketData.irSpot.get(curveId);
            return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
        }
        if (ImaConstants.RF_TYPE_CREDIT_SPOT.equals(rfType)) {
            IrSpot.IrSpotInfo info = marketData.irSpot == null ? null : marketData.irSpot.get(curveId);
            return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
        }
        if (ImaConstants.RF_TYPE_EQ_SPOT.equals(rfType)) {
            EqSpot.EqSpotInfo info = marketData.eqSpot == null ? null : marketData.eqSpot.get(curveId);
            return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
        }
        if (ImaConstants.RF_TYPE_COMM_SPOT.equals(rfType)) {
            CommSpot.CommSpotInfo info = marketData.commSpot == null ? null : marketData.commSpot.get(curveId);
            return info != null && info.curveData != null ? info.curveData.get(tenorDays) : null;
        }
        if (ImaConstants.RF_TYPE_FX_SPOT.equals(rfType)) {
            return marketData.fxSpot != null && marketData.fxSpot.curveData != null
                    ? marketData.fxSpot.curveData.get(curveId) : null;
        }
        throw new IllegalArgumentException("NMRF 当前不支持风险因子类型: " + rfType);
    }

    private static String required(JSONObject obj, String key) {
        String v = obj.getString(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " 必填");
        }
        return v.trim();
    }
}

