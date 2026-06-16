package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.common.ImaConstants;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.frtbima.rfet.bucket.RfetModellableIndex;
import com.zcyh.mr.frtbima.rfet.model.RfetResult;
import com.zcyh.mr.frtbima.scenariopnl.NmrfScenarioRunner;
import com.zcyh.mr.scenario.ScenarioCache;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.springboot.context.RequestContextHolder;
import com.zcyh.mr.springboot.engine.ImaCapitalEngineAdapter;
import com.zcyh.mr.springboot.engine.ImaScenarioEngineAdapter;
import com.zcyh.mr.springboot.engine.ScenarioEngineAdapter;
import com.zcyh.mr.springboot.model.AggregationRule;
import com.zcyh.mr.springboot.model.BatchRunResult;
import com.zcyh.mr.springboot.model.ImaBatchRunRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * IMA 批量计量工作流。
 */
@Service
public class ImaBatchRunService {
    public static final String ENGINE_CODE = "ima_batch_run";
    private static final Logger log = LoggerFactory.getLogger(ImaBatchRunService.class);
    private static final String DEFAULT_USER = "outer_service";
    private static final String OP_CODE = "IMA";
    private static final Pattern DATE_8_PATTERN = Pattern.compile("^\\d{8}$");
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final DateTimeFormatter GENERATED_BATCH_TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmssSSS");

    private final BatchPrepareTask prepareTask;
    private final BatchTradeLoadTask tradeLoadTask;
    private final BatchMarketDataLoadTask marketDataLoadTask;
    private final BatchChunkBuildTask chunkBuildTask;
    private final BatchPayloadBuildTask payloadBuildTask;
    private final ObjectProvider<BatchJobService> batchJobServiceProvider;
    private final AlertService alertService;
    private final TradeFilterResolver tradeFilterResolver;
    private final ScenarioEngineAdapter scenarioEngineAdapter;
    private final ScenarioGeneratedPersistService scenarioGeneratedPersistService;
    private final ImaScenarioEngineAdapter imaScenarioEngineAdapter;
    private final ImaCapitalEngineAdapter imaCapitalEngineAdapter;
    private final ImaRfetSnapshotService imaRfetSnapshotService;
    private final ImaRiskFactorConfigSnapshotService imaRiskFactorConfigSnapshotService;
    private final ExecutorService batchRunWorkflowExecutor;

    public ImaBatchRunService(
            BatchPrepareTask prepareTask,
            BatchTradeLoadTask tradeLoadTask,
            BatchMarketDataLoadTask marketDataLoadTask,
            BatchChunkBuildTask chunkBuildTask,
            BatchPayloadBuildTask payloadBuildTask,
            ObjectProvider<BatchJobService> batchJobServiceProvider,
            AlertService alertService,
            TradeFilterResolver tradeFilterResolver,
            ScenarioEngineAdapter scenarioEngineAdapter,
            ScenarioGeneratedPersistService scenarioGeneratedPersistService,
            ImaScenarioEngineAdapter imaScenarioEngineAdapter,
            ImaCapitalEngineAdapter imaCapitalEngineAdapter,
            ImaRfetSnapshotService imaRfetSnapshotService,
            ImaRiskFactorConfigSnapshotService imaRiskFactorConfigSnapshotService,
            @Qualifier("batchRunWorkflowExecutor") ExecutorService batchRunWorkflowExecutor) {
        this.prepareTask = prepareTask;
        this.tradeLoadTask = tradeLoadTask;
        this.marketDataLoadTask = marketDataLoadTask;
        this.chunkBuildTask = chunkBuildTask;
        this.payloadBuildTask = payloadBuildTask;
        this.batchJobServiceProvider = batchJobServiceProvider;
        this.alertService = alertService;
        this.tradeFilterResolver = tradeFilterResolver;
        this.scenarioEngineAdapter = scenarioEngineAdapter;
        this.scenarioGeneratedPersistService = scenarioGeneratedPersistService;
        this.imaScenarioEngineAdapter = imaScenarioEngineAdapter;
        this.imaCapitalEngineAdapter = imaCapitalEngineAdapter;
        this.imaRfetSnapshotService = imaRfetSnapshotService;
        this.imaRiskFactorConfigSnapshotService = imaRiskFactorConfigSnapshotService;
        this.batchRunWorkflowExecutor = batchRunWorkflowExecutor;
    }

    public BatchRunResult run(ImaBatchRunRequest request) {
        ImaWorkflowContext context = buildContext(request);
        initializeWorkflow(context);
        try {
            batchRunWorkflowExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    runWorkflow(context);
                }
            });
        } catch (RejectedExecutionException ex) {
            batchJobService().markWorkflowFailed(context.batchId, "IMA 批量工作流提交失败: 执行队列已满");
            alertService.error("IMA_BATCH_RUN_REJECTED", "IMA 批量工作流提交失败，batchId=" + context.batchId, ex);
            throw new IllegalStateException("IMA 批量工作流提交失败，执行队列已满，请稍后重试");
        }
        return buildAcceptedResult(context);
    }

    private ImaWorkflowContext buildContext(ImaBatchRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String dataDate = normalizeDataDate(request.getDataDate());
        String externalBatchId = trimToNull(request.getBatchId());
        String batchId = externalBatchId == null ? buildGeneratedBatchId(dataDate) : externalBatchId;
        validateBatchId(batchId);

        ImaWorkflowContext context = new ImaWorkflowContext();
        context.request = request;
        context.batchId = batchId;
        context.dataDate = dataDate;
        context.user = trimToNull(request.getUser()) == null ? DEFAULT_USER : trimToNull(request.getUser());
        context.normalFullScenarioIdList = requireNonBlank(request.getNormalFullScenarioIdList(), "normal_full_scenario_id_list 不能为空");
        context.normalReducedScenarioIdList = requireNonBlank(request.getNormalReducedScenarioIdList(), "normal_reduced_scenario_id_list 不能为空");
        context.stressReducedScenarioIdList = requireNonBlank(request.getStressReducedScenarioIdList(), "stress_reduced_scenario_id_list 不能为空");
        context.nmrfScenarioIdList = requireNonBlank(request.getNmrfScenarioIdList(), "nmrf_scenario_id_list 不能为空");
        context.imaRuleIdList = requireNonBlank(request.getImaRuleIdList(), "ima_rule_id_list 不能为空");
        if (Boolean.FALSE.equals(request.getPersistResult())) {
            throw new IllegalArgumentException("ima_batch_run 当前要求 persist_result=true，Phase2 需要读取 Phase1 落库结果");
        }
        context.persistResult = request.getPersistResult() == null || Boolean.TRUE.equals(request.getPersistResult());
        context.persistScenario = request.getPersistScenario();
        context.cacheScenarioResult = Boolean.TRUE.equals(request.getCacheScenarioResult());
        context.frtbDisabled = Boolean.TRUE.equals(request.getFrtbDisable());
        context.tradeFilter = tradeFilterResolver.resolve(request.getTradeFilter());
        context.normalFullScenarioCacheKey = buildCacheKey(batchId, "normal_full");
        context.normalReducedScenarioCacheKey = buildCacheKey(batchId, "normal_reduced");
        context.stressReducedScenarioCacheKey = buildCacheKey(batchId, "stress_reduced");
        context.nmrfScenarioCacheKey = buildCacheKey(batchId, "nmrf");
        context.modellableIndexCacheKey = buildCacheKey(batchId, "modellable_index");
        context.nmrfBucketsCacheKey = buildCacheKey(batchId, "nmrf_buckets");
        context.liquidityHorizonTableCacheKey = buildCacheKey(batchId, "liquidity_horizon_table");
        context.normalFullResultsCacheKey = buildCacheKey(batchId, "normal_full_results");
        return context;
    }

    private void initializeWorkflow(ImaWorkflowContext context) {
        RequestContextHolder.setBatchId(context.batchId);
        RequestContextHolder.setEngineCode(ENGINE_CODE);
        batchJobService().initializeWorkflowBatch(
                context.batchId,
                context.batchId,
                ENGINE_CODE,
                OP_CODE,
                LocalDate.parse(context.dataDate, DateTimeFormatter.BASIC_ISO_DATE),
                null,
                null,
                System.currentTimeMillis(),
                "IMA 批量工作流已启动",
                context.persistResult);
    }

    private BatchRunResult buildAcceptedResult(ImaWorkflowContext context) {
        BatchRunResult result = new BatchRunResult();
        result.setBatchId(context.batchId);
        result.setDataDate(context.dataDate);
        result.setUser(context.user);
        result.setMode("IMA");
        result.setRunMode(null);
        result.setPersistResult(context.persistResult);
        result.setScenarioGenerated(false);
        result.setScenarioCount(0);
        result.setScenarioData(null);
        result.setBatchDetail(batchJobService().getDetail(context.batchId));
        return result;
    }

    private void runWorkflow(ImaWorkflowContext context) {
        RequestContextHolder.setBatchId(context.batchId);
        RequestContextHolder.setEngineCode(ENGINE_CODE);
        try {
            executeStage(context, "IMA_PREPARE_RUNNING", new Runnable() {
                @Override
                public void run() {
                    prepare(context);
                }
            });
            executeStage(context, "IMA_SCENARIO_RUNNING", new Runnable() {
                @Override
                public void run() {
                    generateScenarios(context);
                }
            });
            executeStage(context, "IMA_RFET_INDEX_RUNNING", new Runnable() {
                @Override
                public void run() {
                    buildRfetCaches(context);
                }
            });
            executeStage(context, "IMA_PAYLOAD_BUILDING", new Runnable() {
                @Override
                public void run() {
                    buildPayloads(context);
                }
            });
            executeStage(context, "IMA_PHASE1_RUNNING", new Runnable() {
                @Override
                public void run() {
                    runPhase1(context);
                }
            });
            executeStage(context, "IMA_PHASE2_RUNNING", new Runnable() {
                @Override
                public void run() {
                    runPhase2(context);
                }
            });
            batchJobService().markWorkflowSuccess(context.batchId, "IMA 批量工作流执行完成");
            log.info("IMA 批量工作流异步执行完成，batchId={}", context.batchId);
        } catch (Throwable ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            batchJobService().markWorkflowFailed(context.batchId, "IMA 批量工作流执行失败: " + message);
            alertService.error("IMA_BATCH_RUN_FAILED", "IMA 批量工作流异步执行失败，batchId=" + context.batchId, ex);
        } finally {
            evictCaches(context);
            RequestContextHolder.clear();
        }
    }

    private void executeStage(ImaWorkflowContext context, String message, Runnable runnable) {
        batchJobService().markWorkflowRunning(context.batchId, message);
        runnable.run();
    }

    private void prepare(ImaWorkflowContext context) {
        BatchRunWorkflowContext batchContext = toBatchContext(context);
        prepareTask.execute(batchContext);
    }

    private void generateScenarios(ImaWorkflowContext context) {
        context.normalFullRecords = generateScenarioRecords(
                context,
                context.normalFullScenarioIdList,
                context.normalFullScenarioCacheKey);
        context.normalReducedRecords = generateScenarioRecords(
                context,
                context.normalReducedScenarioIdList,
                context.normalReducedScenarioCacheKey);
        context.stressReducedRecords = generateScenarioRecords(
                context,
                context.stressReducedScenarioIdList,
                context.stressReducedScenarioCacheKey);
        context.nmrfRecords = generateScenarioRecords(
                context,
                context.nmrfScenarioIdList,
                context.nmrfScenarioCacheKey);
        context.scenarioCount = size(context.normalFullRecords)
                + size(context.normalReducedRecords)
                + size(context.stressReducedRecords)
                + size(context.nmrfRecords);
        persistGeneratedScenarios(context);
    }

    private List<ScenarioGeneratedRecord> generateScenarioRecords(
            ImaWorkflowContext context,
            String scenarioIdList,
            String cacheKey) {
        JSONObject payload = new JSONObject();
        payload.put("mode", "service");
        payload.put("scenario_id_list", scenarioIdList);
        payload.put("data_date", context.dataDate);
        payload.put("user", context.user);
        payload.put("batch_id", context.batchId);
        List<ScenarioGeneratedRecord> records = scenarioEngineAdapter.generateRecords(payload);
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("IMA 情景生成结果为空，scenario_id_list=" + scenarioIdList);
        }
        putScenarioCache(cacheKey, records, context.dataDate);
        return records;
    }

    private void persistGeneratedScenarios(ImaWorkflowContext context) {
        List<ScenarioGeneratedRecord> allRecords = new ArrayList<>();
        appendRecords(allRecords, context.normalFullRecords);
        appendRecords(allRecords, context.normalReducedRecords);
        appendRecords(allRecords, context.stressReducedRecords);
        appendRecords(allRecords, context.nmrfRecords);
        scenarioGeneratedPersistService.persist(
                context.batchId,
                context.dataDate,
                context.persistScenario,
                allRecords);
    }

    private static void appendRecords(List<ScenarioGeneratedRecord> target, List<ScenarioGeneratedRecord> records) {
        if (records != null && !records.isEmpty()) {
            target.addAll(records);
        }
    }

    private void putScenarioCache(String cacheKey, List<ScenarioGeneratedRecord> records, String dataDate) {
        JSONArray rows = new JSONArray();
        for (ScenarioGeneratedRecord record : records) {
            JSONObject row = new JSONObject();
            row.put("SCENARIO_ID", record.getScenarioId());
            row.put("SUBSCENARIO_ID", record.getSubScenarioId());
            row.put("SCENARIO_NAME", record.getScenarioName());
            row.put("SCENARIO_TYPE", record.getScenarioType());
            row.put("CURVE_TYPE", record.getCurveType());
            row.put("CURVE_CODE", record.getCurveCode());
            row.put("TERM_DAYS", record.getTermDays());
            row.put("DIMENSION2", record.getDimension2());
            row.put("CHANGED_RATE", record.getChangedValue());
            rows.add(row);
        }
        ScenarioCache.evict(cacheKey);
        ScenarioCache.loadFromArray(cacheKey, rows, LocalDate.parse(dataDate, DateTimeFormatter.BASIC_ISO_DATE));
    }

    private void buildRfetCaches(ImaWorkflowContext context) {
        List<RfetResult> rfetResults = imaRfetSnapshotService.loadRfetResults(context.batchId);
        if (rfetResults.isEmpty()) {
            throw new IllegalStateException("MR_IMA_RFET_RESULT_SNAPSHOT 未找到当前批次快照，batchId=" + context.batchId);
        }
        RfetModellableIndex index = RfetModellableIndex.build(rfetResults);
        ScenarioCache.putObject(context.modellableIndexCacheKey, index);
        LiquidityHorizonTable liquidityHorizonTable = imaRiskFactorConfigSnapshotService.loadLiquidityHorizonTable(context.batchId);
        ScenarioCache.putObject(context.liquidityHorizonTableCacheKey, liquidityHorizonTable);
        List<NmrfScenarioRunner.NmrfBucketMeta> nmrfBuckets = buildNmrfBuckets(context.nmrfRecords);
        ScenarioCache.putObject(context.nmrfBucketsCacheKey, nmrfBuckets);
        context.nmrfBucketCount = nmrfBuckets.size();
        log.info("IMA RFET 缓存构建完成，batchId={}, rfetBuckets={}, nmrfBuckets={}",
                context.batchId, rfetResults.size(), nmrfBuckets.size());
    }

    private List<NmrfScenarioRunner.NmrfBucketMeta> buildNmrfBuckets(List<ScenarioGeneratedRecord> nmrfRecords) {
        LinkedHashMap<String, NmrfBucketBuilder> builders = new LinkedHashMap<String, NmrfBucketBuilder>();
        if (nmrfRecords != null) {
            for (ScenarioGeneratedRecord record : nmrfRecords) {
                if (record == null || Boolean.TRUE.equals(record.getRfetModellable())) {
                    continue;
                }
                Integer termDays = record.getTermDays();
                String curveCode = trimToNull(record.getCurveCode());
                String curveType = normalizeText(record.getCurveType());
                if (curveCode == null || curveType == null || termDays == null) {
                    throw new IllegalStateException("NMRF 情景记录缺少 curve_type/curve_code/term_days，scenario_id="
                            + record.getScenarioId()
                            + ", sub_scenario_id=" + record.getSubScenarioId());
                }
                if (!isSupportedNmrfRfType(curveType)) {
                    throw new IllegalStateException("NMRF Phase1 当前不支持风险因子类型: " + curveType
                            + "，curve_code=" + curveCode);
                }
                String bucketId = trimToNull(record.getRfetBucketId());
                if (bucketId == null) {
                    throw new IllegalStateException("NMRF 情景记录缺少 RFET bucket，curve_type="
                            + curveType + ", curve_code=" + curveCode + ", term_days=" + termDays);
                }
                String key = curveType + "|" + curveCode + "|" + bucketId;
                NmrfBucketBuilder builder = builders.get(key);
                if (builder == null) {
                    builder = new NmrfBucketBuilder();
                    builder.bucketId = bucketId;
                    builder.curveId = curveCode;
                    builder.rfType = curveType;
                    builder.nmrfType = nmrfType(curveType);
                    builders.put(key, builder);
                }
                builder.tenorDays.add(termDays);
            }
        }
        List<NmrfScenarioRunner.NmrfBucketMeta> buckets = new ArrayList<NmrfScenarioRunner.NmrfBucketMeta>();
        for (NmrfBucketBuilder builder : builders.values()) {
            buckets.add(new NmrfScenarioRunner.NmrfBucketMeta(
                    builder.bucketId,
                    builder.curveId,
                    builder.tenorDays,
                    builder.rfType,
                    builder.nmrfType));
        }
        return buckets;
    }

    private void buildPayloads(ImaWorkflowContext context) {
        BatchRunWorkflowContext batchContext = toBatchContext(context);
        tradeLoadTask.execute(batchContext);
        marketDataLoadTask.execute(batchContext);
        chunkBuildTask.execute(batchContext);
        payloadBuildTask.execute(batchContext);
        context.loadedTrades = batchContext.getLoadedTrades();
        context.loadedMarketData = batchContext.getLoadedMarketData();
        context.tradeChunks = batchContext.getTradeChunks();
        context.jobPayloads = batchContext.getJobPayloads();
        batchJobService().prepareBatchSubmission(
                context.batchId,
                context.batchId,
                ENGINE_CODE,
                OP_CODE,
                LocalDate.parse(context.dataDate, DateTimeFormatter.BASIC_ISO_DATE),
                null,
                null,
                context.loadedTrades.size(),
                context.jobPayloads.size(),
                System.currentTimeMillis());
    }

    private BatchRunWorkflowContext toBatchContext(ImaWorkflowContext context) {
        BatchRunWorkflowContext batchContext = new BatchRunWorkflowContext();
        batchContext.setBatchId(context.batchId);
        batchContext.setDataDate(context.dataDate);
        batchContext.setUser(context.user);
        batchContext.setScenarioMode(false);
        batchContext.setPersistResult(context.persistResult);
        batchContext.setCacheScenarioResult(context.cacheScenarioResult);
        batchContext.setFrtbDisabled(context.frtbDisabled);
        batchContext.setTradeFilter(context.tradeFilter);
        return batchContext;
    }

    private void runPhase1(ImaWorkflowContext context) {
        int total = context.jobPayloads.size();
        int success = 0;
        for (BatchJobPayload jobPayload : context.jobPayloads) {
            int seqNo = jobPayload.getSeqNo();
            batchJobService().updateBatchStatus(
                    context.batchId,
                    "RUNNING",
                    Math.max(0, total - success - 1),
                    1,
                    success,
                    0,
                    0,
                    System.currentTimeMillis(),
                    "IMA Phase1 执行中: " + seqNo + "/" + total);
            try {
                runPhase1ForPayload(context, jobPayload);
                success++;
            } catch (RuntimeException ex) {
                batchJobService().updateBatchStatus(
                        context.batchId,
                        "FAILED",
                        Math.max(0, total - success - 1),
                        0,
                        success,
                        1,
                        0,
                        System.currentTimeMillis(),
                        "IMA Phase1 分片失败: " + seqNo + "/" + total + "，" + ex.getMessage());
                throw ex;
            }
        }
        batchJobService().updateBatchStatus(
                context.batchId,
                "RUNNING",
                0,
                0,
                success,
                0,
                0,
                System.currentTimeMillis(),
                "IMA Phase1 执行完成");
    }

    private void runPhase1ForPayload(ImaWorkflowContext context, BatchJobPayload jobPayload) {
        String jobId = context.batchId + "_IMA_J" + jobPayload.getSeqNo();
        String requestId = context.batchId + "-IMA-J" + jobPayload.getSeqNo();
        JSONObject basePayload = jobPayload.getPayload();
        String baseCalcJson = JSON.toJSONString(basePayload, JSONWriter.Feature.WriteBigDecimalAsPlain);

        JSONObject common = new JSONObject();
        common.put("batch_id", context.batchId);
        common.put("job_id", jobId);
        common.put("request_id", requestId);
        common.put("data_date", context.dataDate);
        common.put("op_code", OP_CODE);
        common.put("base_calc_json", baseCalcJson);
        common.put("modellable_index_cache_key", context.modellableIndexCacheKey);
        common.put("liquidity_horizon_table_cache_key", context.liquidityHorizonTableCacheKey);
        String normalFullResultsCacheKey = context.normalFullResultsCacheKey + "_" + jobPayload.getSeqNo();
        common.put("normal_full_results_cache_key", normalFullResultsCacheKey);

        try {
            runModelablePhase1(common, context.normalFullScenarioCacheKey,
                    ImaConstants.SCENARIO_TYPE_NORMAL_FULL, context.normalFullScenarioIdList);
            runModelablePhase1(common, context.normalReducedScenarioCacheKey,
                    ImaConstants.SCENARIO_TYPE_NORMAL_REDUCED, context.normalReducedScenarioIdList);
            runModelablePhase1(common, context.stressReducedScenarioCacheKey,
                    ImaConstants.SCENARIO_TYPE_STRESS_REDUCED, context.stressReducedScenarioIdList);
            runNmrfPhase1(common, context);
        } finally {
            ScenarioCache.evictObject(normalFullResultsCacheKey);
        }
    }

    private void runModelablePhase1(JSONObject common, String scenarioCacheKey, String scenarioType, String scenarioId) {
        JSONObject payload = new JSONObject();
        payload.putAll(common);
        payload.put("scenario_cache_key", scenarioCacheKey);
        payload.put("scenario_type", scenarioType);
        payload.put("scenario_id", scenarioId);
        imaScenarioEngineAdapter.calculate(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private void runNmrfPhase1(JSONObject common, ImaWorkflowContext context) {
        JSONObject payload = new JSONObject();
        payload.putAll(common);
        payload.remove("scenario_cache_key");
        payload.remove("scenario_type");
        payload.remove("modellable_index_cache_key");
        payload.remove("normal_full_results_cache_key");
        payload.put("scenario_id", context.nmrfScenarioIdList);
        payload.put("nmrf_scenario_cache_key", context.nmrfScenarioCacheKey);
        payload.put("nmrf_buckets_cache_key", context.nmrfBucketsCacheKey);
        imaScenarioEngineAdapter.calculate(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
    }

    private void runPhase2(ImaWorkflowContext context) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", context.batchId);
        payload.put("data_date", context.dataDate);
        payload.put("ima_rule_id_list", context.imaRuleIdList);
        if (context.request.getSaByDesk() != null) {
            payload.put("sa_by_desk", context.request.getSaByDesk());
        }
        if (context.request.getAmberDesks() != null) {
            payload.put("amber_desks", context.request.getAmberDesks());
        }
        if (context.request.getGreenDesks() != null) {
            payload.put("green_desks", context.request.getGreenDesks());
        }
        String raw = imaCapitalEngineAdapter.calculate(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        context.capitalResult = JSON.parseObject(raw);
    }

    private void evictCaches(ImaWorkflowContext context) {
        ScenarioCache.evict(context.normalFullScenarioCacheKey);
        ScenarioCache.evict(context.normalReducedScenarioCacheKey);
        ScenarioCache.evict(context.stressReducedScenarioCacheKey);
        ScenarioCache.evict(context.nmrfScenarioCacheKey);
        ScenarioCache.evictObject(context.modellableIndexCacheKey);
        ScenarioCache.evictObject(context.nmrfBucketsCacheKey);
        ScenarioCache.evictObject(context.liquidityHorizonTableCacheKey);
    }

    private static String nmrfType(String curveType) {
        if (ImaConstants.RF_TYPE_CREDIT_SPOT.equals(curveType)) {
            return ImaConstants.NMRF_TYPE_IDIO_CREDIT;
        }
        if (ImaConstants.RF_TYPE_EQ_SPOT.equals(curveType) || ImaConstants.RF_TYPE_EQ_VOL.equals(curveType)) {
            return ImaConstants.NMRF_TYPE_IDIO_EQUITY;
        }
        return ImaConstants.NMRF_TYPE_OTHER;
    }

    private static boolean isSupportedNmrfRfType(String curveType) {
        return ImaConstants.RF_TYPE_IR_SPOT.equals(curveType)
                || ImaConstants.RF_TYPE_CREDIT_SPOT.equals(curveType)
                || ImaConstants.RF_TYPE_EQ_SPOT.equals(curveType)
                || ImaConstants.RF_TYPE_FX_SPOT.equals(curveType)
                || ImaConstants.RF_TYPE_COMM_SPOT.equals(curveType);
    }

    private BatchJobService batchJobService() {
        return batchJobServiceProvider.getObject();
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static String buildCacheKey(String batchId, String suffix) {
        return "ima_batch_" + batchId + "_" + suffix;
    }

    private static String normalizeText(String value) {
        String safe = trimToNull(value);
        return safe == null ? null : safe.toUpperCase(Locale.ROOT);
    }

    private static String normalizeDataDate(String txt) {
        String safe = requireNonBlank(txt, "dataDate 不能为空");
        if (!DATE_8_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException("dataDate 格式错误，仅支持 yyyyMMdd");
        }
        try {
            LocalDate.parse(safe, DateTimeFormatter.BASIC_ISO_DATE);
            return safe;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("dataDate 格式错误，仅支持 yyyyMMdd");
        }
    }

    private static String buildGeneratedBatchId(String dataDate) {
        String random = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        return "ima_batch_" + dataDate + "_" + LocalDateTime.now().format(GENERATED_BATCH_TIME_FORMATTER) + "_" + random;
    }

    private static void validateBatchId(String batchId) {
        String safe = requireNonBlank(batchId, "batchId 不能为空");
        if (!BATCH_ID_PATTERN.matcher(safe).matches()) {
            throw new IllegalArgumentException("batchId 格式非法，仅支持字母、数字、点、下划线、中划线");
        }
    }

    private static String requireNonBlank(String txt, String message) {
        String safe = trimToNull(txt);
        if (safe == null) {
            throw new IllegalArgumentException(message);
        }
        return safe;
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }

    private static class ImaWorkflowContext {
        private ImaBatchRunRequest request;
        private String batchId;
        private String dataDate;
        private String user;
        private String normalFullScenarioIdList;
        private String normalReducedScenarioIdList;
        private String stressReducedScenarioIdList;
        private String nmrfScenarioIdList;
        private String imaRuleIdList;
        private Boolean persistScenario;
        private boolean persistResult;
        private boolean cacheScenarioResult;
        private boolean frtbDisabled;
        private AggregationRule.FilterExpression tradeFilter;
        private String normalFullScenarioCacheKey;
        private String normalReducedScenarioCacheKey;
        private String stressReducedScenarioCacheKey;
        private String nmrfScenarioCacheKey;
        private String modellableIndexCacheKey;
        private String nmrfBucketsCacheKey;
        private String liquidityHorizonTableCacheKey;
        private String normalFullResultsCacheKey;
        private List<ScenarioGeneratedRecord> normalFullRecords = new ArrayList<ScenarioGeneratedRecord>();
        private List<ScenarioGeneratedRecord> normalReducedRecords = new ArrayList<ScenarioGeneratedRecord>();
        private List<ScenarioGeneratedRecord> stressReducedRecords = new ArrayList<ScenarioGeneratedRecord>();
        private List<ScenarioGeneratedRecord> nmrfRecords = new ArrayList<ScenarioGeneratedRecord>();
        private int scenarioCount;
        private int nmrfBucketCount;
        private List<BatchTradeDataLoader.TradeRow> loadedTrades = new ArrayList<BatchTradeDataLoader.TradeRow>();
        private List<BatchTradeDataLoader.CurveRow> loadedMarketData = new ArrayList<BatchTradeDataLoader.CurveRow>();
        private List<List<BatchTradeDataLoader.TradeRow>> tradeChunks = new ArrayList<List<BatchTradeDataLoader.TradeRow>>();
        private List<BatchJobPayload> jobPayloads = new ArrayList<BatchJobPayload>();
        private JSONObject capitalResult;
    }

    private static class NmrfBucketBuilder {
        private String bucketId;
        private String curveId;
        private String rfType;
        private String nmrfType;
        private Set<Integer> tenorDays = new LinkedHashSet<Integer>();
    }
}
