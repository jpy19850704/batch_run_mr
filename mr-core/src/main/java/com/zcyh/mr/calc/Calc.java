package com.zcyh.mr.calc;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.result.CalcResultMergeService;
import com.zcyh.mr.calc.result.ScenarioPnlService;
import com.zcyh.mr.calc.result.ScenarioPnlResultAssembler;
import com.zcyh.mr.calc.scenario.CalcScenarioInputResolver;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.calendar.SystemCalendarCache;
import com.zcyh.mr.loader.Loader;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.marketdata.curvegeneration.CurveGeneration;
import com.zcyh.mr.marketdata.curvegeneration.CurveGenerationExport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 估值计算统一入口。
 * 负责解析 JSON 输入，按产品类型分组并分发到对应计算器，最后合并输出结果。
 * 同时支持可选的 scenario_data 压力情景估值。
 */
public class Calc {
        private static final Logger log = LoggerFactory.getLogger(Calc.class);
        private static final String RESULT_KIND_SCENARIO = "SCENARIO";
        private static final CalcResultMergeService RESULT_MERGE_SERVICE = new CalcResultMergeService();
        private static final ScenarioPnlService SCENARIO_PNL_SERVICE = new ScenarioPnlService();
        private static final ScenarioPnlResultAssembler SCENARIO_PNL_RESULT_ASSEMBLER = new ScenarioPnlResultAssembler();

        List<HashMap<String, Object>> trades;
        MarketData marketData;
        LocalDate dataDate;
        String calcMode;
        String rawCalcMode;
        String rawJsonData;
        boolean frtbDisabled;
        Calendar calendar;
        JSONObject otherData;
        JSONArray validationErrors;
        List<Loader.ScenarioEntry> scenarioDataList;
        List<CurveGeneration.CurveInput> curveGenerationInputs;
        JSONArray generatedMarketData = new JSONArray();
        List<String> curveGenerationErrors = new ArrayList<>();

        public Calc(String jsonData, Calendar calendar) {
                this(jsonData, calendar, null);
        }

        public Calc(String jsonData, Calendar calendar, LiquidityHorizonTable imaRiskFactorConfig) {
                Loader loader = new Loader(jsonData, SystemCalendarCache.resolve(calendar));
                this.rawJsonData = jsonData;
                this.trades = loader.getTrades();
                this.marketData = loader.getMarketData();
                this.dataDate = loader.getDataDate();
                this.rawCalcMode = loader.getCalcMode();
                this.calcMode = EngineConstants.CALC_MODE.PRICING;
                this.frtbDisabled = readFrtbDisabled(jsonData);
                this.calendar = loader.getCalendar();
                this.otherData = loader.getOtherData();
                this.validationErrors = loader.getValidationErrors();
                this.curveGenerationInputs = loader.getCurveGenerationInputs();

                this.scenarioDataList = CalcScenarioInputResolver.resolveScenarioData(
                                jsonData, loader, imaRiskFactorConfig);
        }

        /**
         * 执行估值主流程，按产品分组分发并合并结果。
         * 传入场景数据时生成并附加 scenario_result。
         * 当 calc_mode=CURVE_GENERATION 时，仅生成曲线并返回 generated_market_data。
         *
         * @return 包含基准估值和场景估值的 JSON 字符串
         */
        public String run() {
                long totalStart = System.nanoTime();
                OperModeControl.init(this.rawCalcMode);
                FrtbCalcControl.init(this.frtbDisabled);
                try {
                        long phaseStart = System.nanoTime();
                        applyCurveGeneration();
                        double curveGenerationMs = elapsedMs(phaseStart);
                        if (OperModeControl.isCurveGenerationOnly()) {
                                return RESULT_MERGE_SERVICE.buildCurveGenerationOnlyResult(
                                                generatedMarketData, curveGenerationErrors);
                        }

                        // 先执行基准估值，并收集计算器实例与可场景复用的产品类型
                        List<ProductCalculator> cachedCalcs = new ArrayList<>();
                        Set<String> scenarioProductCodes = new LinkedHashSet<>();
                        phaseStart = System.nanoTime();
                        String baseResult = runWithMarketData(this.marketData, cachedCalcs, scenarioProductCodes);
                        double baseCalcMs = elapsedMs(phaseStart);
                        JSONObject baseJson = JSON.parseObject(baseResult);
                        JSONObject dataObj = baseJson.getJSONObject("data");
                        if (dataObj != null) {
                                RESULT_MERGE_SERVICE.appendCurveGenerationOutput(
                                                dataObj, generatedMarketData, curveGenerationErrors);
                        }

                        List<Loader.ScenarioEntry> scenarioEntries = (scenarioDataList == null)
                                        ? Collections.emptyList()
                                        : scenarioDataList;
                        if (scenarioEntries.isEmpty()) {
                                phaseStart = System.nanoTime();
                                String output = baseJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                                logPerformance(scenarioProductCodes, 0, curveGenerationMs, baseCalcMs,
                                                0.0d, 0.0d, 0.0d, 0.0d, 0.0d,
                                                elapsedMs(phaseStart), elapsedMs(totalStart));
                                return output;
                        }

                        // 筛选支持场景复用的计算器
                        List<ProductCalculator> scenarioCalcs = new ArrayList<>(cachedCalcs);

                        // 解析基准结果并逐个场景追加结果（无场景数据时返回空 scenario_result）
                        if (dataObj == null) {
                                return baseResult;
                        }

                        long scenarioPrepareStart = System.nanoTime();
                        JSONArray baseTrades = SCENARIO_PNL_SERVICE.buildEffectiveBaseTrades(
                                        dataObj.getJSONArray("trade_data"));
                        dataObj.put("trade_data", baseTrades);
                        Map<String, JSONObject> baseTradeIndex = SCENARIO_PNL_SERVICE.buildTradeIndex(baseTrades);
                        Set<String> unsupportedScenarioProducts = SCENARIO_PNL_SERVICE.collectUnsupportedScenarioProducts(
                                        baseTrades, scenarioProductCodes);

                        RiskFactorMatcher.Index rfIndex = RiskFactorMatcher.buildIndex(this.marketData);
                        Map<String, Set<String>> perTradeImpactKeys = RiskFactorMatcher.buildPerTradeKeys(this.trades, rfIndex);
                        Map<String, Set<String>> factorToTradeIds = RiskFactorMatcher.buildFactorToTradeIndex(
                                        perTradeImpactKeys);
                        double scenarioPrepareMs = elapsedMs(scenarioPrepareStart);

                        JSONArray scenarioResults = new JSONArray();
                        long impactNanos = 0L;
                        long marketUpdateNanos = 0L;
                        long scenarioCalcNanos = 0L;
                        long pnlNanos = 0L;
                        long assembleNanos = 0L;
                        for (Loader.ScenarioEntry entry : scenarioEntries) {
                                long stepStart = System.nanoTime();
                                RiskFactorMatcher.ScenarioImpactResolution impactResolution =
                                                RiskFactorMatcher.resolveScenarioKeys(
                                                        entry == null ? null : entry.impactKeys, rfIndex);
                                // 逐笔交易判断是否受场景影响
                                Set<String> affectedIds = RiskFactorMatcher.resolveAffectedTradeIdsFast(
                                                factorToTradeIds, impactResolution);
                                if (affectedIds == null) {
                                        affectedIds = RiskFactorMatcher.resolveAffectedTradeIds(
                                                        perTradeImpactKeys, impactResolution);
                                }
                                impactNanos += System.nanoTime() - stepStart;

                                // 无任何交易受影响 → 整个场景 PnL=0
                                if (affectedIds != null && affectedIds.isEmpty()) {
                                        stepStart = System.nanoTime();
                                        scenarioResults.add(SCENARIO_PNL_RESULT_ASSEMBLER.assemble(
                                                        entry,
                                                        SCENARIO_PNL_SERVICE.buildZeroPnlResults(
                                                                        baseTrades, unsupportedScenarioProducts),
                                                        RESULT_KIND_SCENARIO));
                                        assembleNanos += System.nanoTime() - stepStart;
                                        continue;
                                }

                                stepStart = System.nanoTime();
                                MarketData scenMarket = MarketData.updateMarketData(this.marketData, entry.marketData);
                                marketUpdateNanos += System.nanoTime() - stepStart;
                                // 汇总当前场景下的交易结果（仅重估受影响的交易）
                                stepStart = System.nanoTime();
                                JSONArray scenTradeResults = new JSONArray();
                                for (ProductCalculator sc : scenarioCalcs) {
                                        scenTradeResults.addAll(sc.calcScenario(scenMarket, affectedIds));
                                }
                                scenarioCalcNanos += System.nanoTime() - stepStart;

                                // 按 INSTRUMENT_ID 对齐基准与场景结果并计算 PnL
                                stepStart = System.nanoTime();
                                JSONArray pnlResults = SCENARIO_PNL_SERVICE.buildPnlResults(
                                                baseTradeIndex, scenTradeResults, unsupportedScenarioProducts,
                                                affectedIds);
                                pnlNanos += System.nanoTime() - stepStart;

                                stepStart = System.nanoTime();
                                scenarioResults.add(SCENARIO_PNL_RESULT_ASSEMBLER.assemble(
                                                entry, pnlResults, RESULT_KIND_SCENARIO));
                                assembleNanos += System.nanoTime() - stepStart;
                        }

                        dataObj.put("scenario_result", scenarioResults);
                        phaseStart = System.nanoTime();
                        String output = baseJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                        logPerformance(scenarioProductCodes, scenarioEntries.size(), curveGenerationMs, baseCalcMs,
                                        scenarioPrepareMs, nanosToMs(impactNanos), nanosToMs(marketUpdateNanos),
                                        nanosToMs(scenarioCalcNanos), nanosToMs(pnlNanos + assembleNanos),
                                        elapsedMs(phaseStart), elapsedMs(totalStart));
                        return output;
                } finally {
                        OperModeControl.clear();
                        FrtbCalcControl.clear();
                }
        }

        private void logPerformance(Set<String> productCodes,
                                    int scenarioCount,
                                    double curveGenerationMs,
                                    double baseCalcMs,
                                    double scenarioPrepareMs,
                                    double impactResolveMs,
                                    double marketUpdateMs,
                                    double scenarioCalcMs,
                                    double pnlAssembleMs,
                                    double serializationMs,
                                    double totalMs) {
                log.info("Calc性能统计: batchId={}, seqNo={}, products={}, tradeCount={}, scenarioCount={}, "
                                + "curveGenerationMs={}, baseCalcMs={}, scenarioPrepareMs={}, impactResolveMs={}, "
                                + "marketUpdateMs={}, scenarioCalcMs={}, pnlAssembleMs={}, serializationMs={}, totalMs={}",
                                batchMetaValue("batch_id"), batchMetaValue("seq_no"), productCodes,
                                trades == null ? 0 : trades.size(), scenarioCount, curveGenerationMs, baseCalcMs,
                                scenarioPrepareMs, impactResolveMs, marketUpdateMs, scenarioCalcMs,
                                pnlAssembleMs, serializationMs, totalMs);
        }

        private Object batchMetaValue(String fieldName) {
                JSONObject payload = JSON.parseObject(rawJsonData);
                JSONObject batchMeta = payload == null ? null : payload.getJSONObject("batch_meta");
                return batchMeta == null ? null : batchMeta.get(fieldName);
        }

        private static double elapsedMs(long startNanos) {
                return nanosToMs(System.nanoTime() - startNanos);
        }

        private static double nanosToMs(long nanos) {
                return nanos / 1_000_000.0d;
        }

        private static boolean readFrtbDisabled(String jsonData) {
                if (jsonData == null) {
                        return false;
                }
                JSONObject payload = JSON.parseObject(jsonData);
                return payload != null && Boolean.TRUE.equals(payload.getBoolean("frtb_disable"));
        }

        /**
         * 在基准估值前执行曲线生成，并将生成结果合并到当前市场数据。
         * 即使没有 trade_data，只要传入 curve_generation 也会执行。
         */
        private void applyCurveGeneration() {
                generatedMarketData = new JSONArray();
                curveGenerationErrors = new ArrayList<>();
                if (curveGenerationInputs == null || curveGenerationInputs.isEmpty()) {
                        return;
                }

                CurveGeneration generation = new CurveGeneration();
                CurveGeneration.CurveResult result = generation.generate(curveGenerationInputs, this.calendar, this.marketData);
                generatedMarketData = CurveGenerationExport.toJsonArray(result);
                result.mergeInto(this.marketData);
                if (result.errors != null && !result.errors.isEmpty()) {
                        curveGenerationErrors.addAll(result.errors);
                }
        }

        /**
         * 产品计算器统一按 PRICING 执行主流程，
         * 避免修改各产品计算器的运行条件。
         */
        private static String normalizeOperCodeForExecution(String calcMode) {
                return OperModeControl.executionMode();
        }

        /**
         * 使用指定市场数据执行一次完整估值。
         * 会将当前 trades 按 PRODUCT_CODE 分组，分别调用对应计算器并合并结果。
         *
         * @param md                   用于估值的市场数据
         * @param cachedCalcs          非空时，用于收集计算器实例供场景复用
         * @param scenarioProductCodes 非空时，用于记录支持场景复用的产品类型
         * @return 估值结果 JSON 字符串
         */
        private String runWithMarketData(MarketData md, List<ProductCalculator> cachedCalcs,
                        Set<String> scenarioProductCodes) {
                JSONObject mergedData = new JSONObject();
                mergedData.put("trade_data", new JSONArray());
                if (this.trades == null || this.trades.isEmpty()) {
                        writeSystemValidationErrors();
                        JSONObject result = new JSONObject();
                        result.put("data", mergedData);
                        return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                }

                List<HashMap<String, Object>> calculableTrades = new ArrayList<HashMap<String, Object>>();
                for (HashMap<String, Object> tradeData : this.trades) {
                        String instrumentId = Objects.toString(tradeData.get("INSTRUMENT_ID"), "").trim();
                        if (instrumentId.isEmpty()) {
                                log.error("交易输入缺少INSTRUMENT_ID，无法生成交易结果: productCode={}",
                                                Objects.toString(tradeData.get("PRODUCT_CODE"), ""));
                                continue;
                        }
                        String inputError = trimToNull(Objects.toString(
                                        tradeData.get(EngineConstants.CONTROL_FIELD.INPUT_ERROR), null));
                        if (inputError != null) {
                                appendErrorTrade(mergedData, tradeData, inputError);
                        } else {
                                calculableTrades.add(tradeData);
                        }
                }

                // 按 PRODUCT_CODE 分组
                Map<String, List<HashMap<String, Object>>> grouped = calculableTrades.stream()
                                .collect(Collectors.groupingBy(
                                                t -> Objects.toString(t.get("PRODUCT_CODE"), ""),
                                                LinkedHashMap::new,
                                                Collectors.toList()));

                for (Map.Entry<String, List<HashMap<String, Object>>> entry : grouped.entrySet()) {
                        String productCode = entry.getKey();
                        List<HashMap<String, Object>> groupTrades = entry.getValue();

                        if (!ProductCalculatorRegistry.supports(productCode)) {
                                for (HashMap<String, Object> tradeData : groupTrades) {
                                        appendErrorTrade(mergedData, tradeData,
                                                        "不支持的产品类型: " + productCode);
                                }
                                continue;
                        }

                        // 创建并执行计算器
                        ProductCalculator calcInstance;
                        try {
                                calcInstance = ProductCalculatorRegistry.create(productCode,
                                                this.calcMode, this.dataDate, groupTrades, md, this.calendar,
                                                this.otherData);
                        } catch (Exception e) {
                                log.error("计算器初始化异常: productCode={}", productCode, e);
                                for (HashMap<String, Object> t : groupTrades) {
                                        appendErrorTrade(mergedData, t, "初始化异常: " + resolveErrorMessage(e));
                                }
                                continue;
                        }

                        // 缓存计算器实例，并记录支持场景复用的产品类型
                        if (cachedCalcs != null) {
                                cachedCalcs.add(calcInstance);
                                if (scenarioProductCodes != null) {
                                        scenarioProductCodes.add(productCode);
                                }
                        }

                        String groupResult;
                        try {
                                groupResult = calcInstance.calc();
                        } catch (Exception e) {
                                log.error("计算执行异常: productCode={}", productCode, e);
                                for (HashMap<String, Object> t : groupTrades) {
                                        appendErrorTrade(mergedData, t, resolveErrorMessage(e));
                                }
                                continue;
                        }

                        // 通用合并：遍历 data 下所有数组字段并追加
                        RESULT_MERGE_SERVICE.mergeData(mergedData, groupResult, productCode);
                }

                RESULT_MERGE_SERVICE.appendEmptyCalendarLogs(mergedData, this.trades);
                writeSystemValidationErrors();

                JSONObject result = new JSONObject();
                result.put("data", mergedData);
                return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        }

        private void appendErrorTrade(
                        JSONObject mergedData,
                        HashMap<String, Object> tradeData,
                        String message) {
                JSONArray results = RESULT_MERGE_SERVICE.getOrCreateArray(mergedData, "trade_data");
                results.add(AbstractCalc.buildErrorMeasure(
                                dataDate,
                                Objects.toString(tradeData.get("INSTRUMENT_ID"), ""),
                                Objects.toString(tradeData.get("PRODUCT_CODE"), ""),
                                new IllegalArgumentException(message)));
        }

        private void writeSystemValidationErrors() {
                if (validationErrors == null || validationErrors.isEmpty()) {
                        return;
                }
                for (Object error : validationErrors) {
                        log.error("输入数据校验异常: {}", error);
                }
        }

        private static String resolveErrorMessage(Exception error) {
                if (error == null) {
                        return "unknown";
                }
                String message = trimToNull(error.getMessage());
                if (message != null) {
                        return message;
                }
                return error.getClass().getSimpleName();
        }

        private static String trimToNull(String value) {
                if (value == null) {
                        return null;
                }
                String trimmed = value.trim();
                return trimmed.isEmpty() ? null : trimmed;
        }

}
