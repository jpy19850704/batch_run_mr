package com.zcyh.mr.calc;


import com.zcyh.mr.scenario.ScenarioCache;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.SystemCalendarCache;
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
        private static final String RESULT_KIND_RISK_CLASS_DECOMP = "RISK_CLASS_DECOMP";
        private static final Set<String> TRADE_CALENDAR_FIELDS = new LinkedHashSet<>(Arrays.asList(
                        "SETTLE_CALENDAR",
                        "FIXING_CALENDAR",
                        "PAY_SETTLE_CALENDAR",
                        "PAY_FIXING_CALENDAR",
                        "REC_SETTLE_CALENDAR",
                        "REC_FIXING_CALENDAR",
                        "UNDERLYING_SETTLE_CALENDAR"));

        List<HashMap<String, Object>> trades;
        MarketData marketData;
        LocalDate dataDate;
        String operCode;
        String rawOperCode;
        boolean frtbDisabled;
        Calendar calendar;
        JSONObject otherData;
        JSONArray validationErrors;
        List<Loader.ScenarioEntry> scenarioDataList;
        List<Loader.ScenarioEntry> decompScenarioDataList;
        List<CurveGeneration.CurveInput> curveGenerationInputs;
        JSONArray generatedMarketData = new JSONArray();
        List<String> curveGenerationErrors = new ArrayList<>();

        /**
         * 场景复用接口。
         * 实现该接口的计算器可在压力场景中复用基准阶段的状态，只用场景市场数据重算结果。
         */
        interface ScenarioCapable {
                JSONArray calcScenario(MarketData scenarioMd);
                /**
                 * 场景估值（仅重估受影响的交易）。
                 * @param scenarioMd    场景市场数据
                 * @param affectedIds   受影响的交易 INSTRUMENT_ID 集合，null 表示全量重估
                 * @return 场景估值结果的 trade_data 数组
                 */
                default JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
                        return calcScenario(scenarioMd);
                }
        }

        /**
         * 计算器工厂接口：按输入参数创建对应的计算器实例。
         */
        @FunctionalInterface
        interface CalcFactory {
                Runnable create(String operCode, LocalDate dataDate,
                                List<HashMap<String, Object>> trades,
                                MarketData md, Calendar calendar, JSONObject otherData);
        }

        /**
         * 产品类型到计算器工厂的注册表。
         */
        private static final Map<String, CalcFactory> REGISTRY = new LinkedHashMap<>();

        static {
                REGISTRY.put(Constants.PRODUCT_CODE.COMMFWD,
                                (op, dt, tr, md, cal, oth) -> new CommFwdCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMMSWAP,
                                (op, dt, tr, md, cal, oth) -> new CommSwapCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.BOND,
                                (op, dt, tr, md, cal, oth) -> new BondCalc(op, dt, tr, md, cal));
                REGISTRY.put(Constants.PRODUCT_CODE.WILLOW_BOND,
                                (op, dt, tr, md, cal, oth) -> new WillowBondCalc(op, dt, tr, md, cal));
                REGISTRY.put(Constants.PRODUCT_CODE.FXFWD, (op, dt, tr, md, cal, oth) -> new FxFwdCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FXSWAP,
                                (op, dt, tr, md, cal, oth) -> new FxSwapCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMMOPT,
                                (op, dt, tr, md, cal, oth) -> new CommOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IRSCCS,
                                (op, dt, tr, md, cal, oth) -> new IrsCcsCalc(op, dt, tr, md, cal));
                REGISTRY.put(Constants.PRODUCT_CODE.CAPFLOOR,
                                (op, dt, tr, md, cal, oth) -> new CapFloorCalc(op, dt, tr, md, cal));
                REGISTRY.put(Constants.PRODUCT_CODE.FXOPT,
                                (op, dt, tr, md, cal, oth) -> new FxVanillaOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_ASIAN,
                                (op, dt, tr, md, cal, oth) -> new FxAsianCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_ASIAN,
                                (op, dt, tr, md, cal, oth) -> new EqAsianCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_ASIAN,
                                (op, dt, tr, md, cal, oth) -> new CommAsianCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.AUTO_CALL,
                                (op, dt, tr, md, cal, oth) -> new GenericMcCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMPOSITE,
                                (op, dt, tr, md, cal, oth) -> new CompositeCalc(op, dt, tr, md, cal, oth));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_SPREADOPT,
                                (op, dt, tr, md, cal, oth) -> new FxSpreadOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_SPREADOPT,
                                (op, dt, tr, md, cal, oth) -> new EqSpreadOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_SPREADOPT,
                                (op, dt, tr, md, cal, oth) -> new CommSpreadOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_SPREADOPT,
                                (op, dt, tr, md, cal, oth) -> new IrSpreadOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_BARRIER,
                                (op, dt, tr, md, cal, oth) -> new IrBarOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_BARRIER,
                                (op, dt, tr, md, cal, oth) -> new EqBarOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_BARRIER,
                                (op, dt, tr, md, cal, oth) -> new FxBarOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_BARRIER,
                                (op, dt, tr, md, cal, oth) -> new CommBarOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_DIGITAL,
                                (op, dt, tr, md, cal, oth) -> new IrDigOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_DIGITAL,
                                (op, dt, tr, md, cal, oth) -> new EqDigOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_DIGITAL,
                                (op, dt, tr, md, cal, oth) -> new FxDigOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_DIGITAL,
                                (op, dt, tr, md, cal, oth) -> new CommDigOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_SHARKFIN,
                                (op, dt, tr, md, cal, oth) -> new FxSharkFinCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_WEDDING_CAKE,
                                (op, dt, tr, md, cal, oth) -> new FxWeddingCakeCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_WEDDING_CAKE,
                                (op, dt, tr, md, cal, oth) -> new EqWeddingCakeCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_WEDDING_CAKE,
                                (op, dt, tr, md, cal, oth) -> new CommWeddingCakeCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_WEDDING_CAKE,
                                (op, dt, tr, md, cal, oth) -> new IrWeddingCakeCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_SHARKFIN,
                                (op, dt, tr, md, cal, oth) -> new EqSharkFinCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_SHARKFIN,
                                (op, dt, tr, md, cal, oth) -> new CommSharkFinCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_SHARKFIN,
                                (op, dt, tr, md, cal, oth) -> new IrSharkFinCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.SWAPTION,
                                (op, dt, tr, md, cal, oth) -> new SwaptionCalc(op, dt, tr, md, cal));
                REGISTRY.put(Constants.PRODUCT_CODE.BOND_FUTURE,
                                (op, dt, tr, md, cal, oth) -> new BondFutureCalc(op, dt, tr, md, cal, oth));
                REGISTRY.put(Constants.PRODUCT_CODE.CDS,
                                (op, dt, tr, md, cal, oth) -> new CdsCalc(op, dt, tr, md, cal, oth));
                REGISTRY.put(Constants.PRODUCT_CODE.TRS,
                                (op, dt, tr, md, cal, oth) -> new TrsCalc(op, dt, tr, md, cal, oth));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_RA,
                                (op, dt, tr, md, cal, oth) -> new IrRangeAccureOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.IR_STEP_UP,
                                (op, dt, tr, md, cal, oth) -> new IrStepUpOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_RA,
                                (op, dt, tr, md, cal, oth) -> new EqRangeAccureOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.EQ_STEP_UP,
                                (op, dt, tr, md, cal, oth) -> new EqStepUpOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_RA,
                                (op, dt, tr, md, cal, oth) -> new CommRangeAccureOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.COMM_STEP_UP,
                                (op, dt, tr, md, cal, oth) -> new CommStepUpOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_RA,
                                (op, dt, tr, md, cal, oth) -> new FxRangeAccureOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.FX_STEP_UP,
                                (op, dt, tr, md, cal, oth) -> new FxStepUpOptCalc(op, dt, tr, md));
                REGISTRY.put(Constants.PRODUCT_CODE.STD_IRS,
                                (op, dt, tr, md, cal, oth) -> new StdIrsCalc(op, dt, tr, md, cal));
        }

        public Calc(String jsonData, Calendar calendar) {
                Loader loader = new Loader(jsonData, SystemCalendarCache.resolve(calendar));
                this.trades = loader.getTrades();
                this.marketData = loader.getMarketData();
                this.dataDate = loader.getDataDate();
                this.rawOperCode = loader.getOperCode();
                this.operCode = normalizeOperCodeForExecution(this.rawOperCode);
                this.frtbDisabled = readFrtbDisabled(jsonData);
                this.calendar = loader.getCalendar();
                this.otherData = loader.getOtherData();
                this.validationErrors = loader.getValidationErrors();
                this.curveGenerationInputs = loader.getCurveGenerationInputs();

                this.scenarioDataList = resolveScenarioData(jsonData, loader);
                this.decompScenarioDataList = resolveDecompScenarioData(jsonData);
        }

        /**
         * 解析场景数据来源：缓存引用和内联数据是两种正式协议，但同一请求不能共存。
         */
        private static List<Loader.ScenarioEntry> resolveScenarioData(String jsonData, Loader loader) {
                JSONObject jo = JSON.parseObject(jsonData);
                if (jo == null) {
                        return loader.getScenarioDataList();
                }
                JSONArray inlineScenarioData = jo.getJSONArray("scenario_data");
                JSONObject ref = jo.getJSONObject("scenario_ref");
                String cacheKey = ref == null ? null : ref.getString("cache_key");
                boolean hasInline = inlineScenarioData != null && !inlineScenarioData.isEmpty();
                boolean hasCache = cacheKey != null && !cacheKey.trim().isEmpty();
                if (hasInline && hasCache) {
                        throw new IllegalArgumentException("scenario_data 与 scenario_ref.cache_key 不能同时传入");
                }
                if (hasCache) {
                        List<Loader.ScenarioEntry> cached = ScenarioCache.get(cacheKey.trim());
                        if (cached == null) {
                                throw new IllegalArgumentException("ScenarioCache 未找到场景数据: cache_key=" + cacheKey.trim());
                        }
                        return cached;
                }
                return loader.getScenarioDataList();
        }

        /**
         * 解析 RISK_CLASS decomp 场景数据：从缓存中读取 decomp_cache_key。
         */
        private static List<Loader.ScenarioEntry> resolveDecompScenarioData(String jsonData) {
                try {
                        JSONObject jo = JSON.parseObject(jsonData);
                        if (jo != null) {
                                JSONObject ref = jo.getJSONObject("scenario_ref");
                                if (ref != null) {
                                        String decompKey = ref.getString("decomp_cache_key");
                                        if (decompKey != null && !decompKey.trim().isEmpty()) {
                                                List<Loader.ScenarioEntry> cached = ScenarioCache.get(decompKey.trim());
                                                if (cached != null) {
                                                        return cached;
                                                }
                                        }
                                }
                        }
                } catch (Exception ignored) {
                }
                return Collections.emptyList();
        }

        /**
         * 执行估值主流程，按产品分组分发并合并结果。
         * 当 oper_code=SCENARIO 时，会生成并附加 scenario_result。
         * 当 oper_code=CURVE_GENERATION 时，仅生成曲线并返回 generated_market_data。
         *
         * @return 包含基准估值和场景估值的 JSON 字符串
         */
        public String run() {
                OperModeControl.init(this.rawOperCode);
                FrtbCalcControl.init(this.frtbDisabled);
                try {
                        applyCurveGeneration();
                        if (OperModeControl.isCurveGenerationOnly()) {
                                return buildCurveGenerationOnlyResult();
                        }

                        // 先执行基准估值，并收集计算器实例与可场景复用的产品类型
                        List<Runnable> cachedCalcs = new ArrayList<>();
                        Set<String> scenarioProductCodes = new LinkedHashSet<>();
                        String baseResult = runWithMarketData(this.marketData, cachedCalcs, scenarioProductCodes);
                        JSONObject baseJson = JSON.parseObject(baseResult);
                        JSONObject dataObj = baseJson.getJSONObject("data");
                        if (dataObj != null) {
                                appendCurveGenerationOutput(dataObj);
                        }

                        // 仅由 oper_code 控制是否进入情景流程；其他模式只返回基准估值
                        if (!OperModeControl.isScenarioEnabled()) {
                                return baseJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                        }
                        List<Loader.ScenarioEntry> scenarioEntries = (scenarioDataList == null)
                                        ? Collections.emptyList()
                                        : scenarioDataList;

                        // 筛选支持场景复用的计算器
                        List<ScenarioCapable> scenarioCalcs = new ArrayList<>();
                        for (Runnable calc : cachedCalcs) {
                                if (calc instanceof ScenarioCapable) {
                                        scenarioCalcs.add((ScenarioCapable) calc);
                                }
                        }

                        // 解析基准结果并逐个场景追加结果（无场景数据时返回空 scenario_result）
                        if (dataObj == null) {
                                return baseResult;
                        }

                        JSONArray baseTrades = buildEffectiveBaseTrades(
                                        dataObj.getJSONArray("trade_data"),
                                        dataObj.getJSONArray("log_data"),
                                        this.trades);
                        dataObj.put("trade_data", baseTrades);
                        Map<String, JSONObject> baseTradeIndex = buildTradeIndex(baseTrades);
                        Set<String> unsupportedScenarioProducts = collectUnsupportedScenarioProducts(
                                        baseTrades, scenarioProductCodes);
                        for (String productCode : unsupportedScenarioProducts) {
                                addLog(dataObj, productCode, null,
                                                "oper_code=SCENARIO 跳过该产品的场景估值：对应计算器未实现 ScenarioCapable");
                        }

                        RiskFactorMatcher.Index rfIndex = RiskFactorMatcher.buildIndex(this.marketData);
                        Map<String, Set<String>> perTradeImpactKeys = RiskFactorMatcher.buildPerTradeKeys(this.trades, rfIndex);
                        Map<String, Set<String>> factorToTradeIds = RiskFactorMatcher.buildFactorToTradeIndex(
                                        perTradeImpactKeys);

                        JSONArray scenarioResults = new JSONArray();
                        for (Loader.ScenarioEntry entry : scenarioEntries) {
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

                                // 无任何交易受影响 → 整个场景 PnL=0
                                if (affectedIds != null && affectedIds.isEmpty()) {
                                        scenarioResults.add(buildScenarioItem(
                                                        entry, buildZeroPnlResults(baseTrades), RESULT_KIND_SCENARIO));
                                        continue;
                                }

                                MarketData scenMarket = MarketData.updateMarketData(this.marketData, entry.marketData);

                                // 汇总当前场景下的交易结果（仅重估受影响的交易）
                                JSONArray scenTradeResults = new JSONArray();
                                for (ScenarioCapable sc : scenarioCalcs) {
                                        scenTradeResults.addAll(sc.calcScenario(scenMarket, affectedIds));
                                }

                                // 按 INSTRUMENT_ID 对齐基准与场景结果并计算 PnL
                                JSONArray pnlResults = buildPnlResults(baseTradeIndex, scenTradeResults);

                                scenarioResults.add(buildScenarioItem(entry, pnlResults, RESULT_KIND_SCENARIO));
                        }

                        // RISK_CLASS decomp 场景：每个 entry × 5 groups 动态过滤
                        String[] riskGroups = {"IR", "FX", "EQ", "COMM", "ALL"};
                        List<Loader.ScenarioEntry> decompEntries = (decompScenarioDataList == null)
                                        ? Collections.emptyList()
                                        : decompScenarioDataList;
                        for (Loader.ScenarioEntry entry : decompEntries) {
                                if (entry == null) continue;
                                JSONArray decompTradeData = buildDecompScenarioTradeData(
                                                entry, riskGroups, baseTrades, baseTradeIndex, scenarioCalcs,
                                                rfIndex, perTradeImpactKeys, factorToTradeIds);
                                scenarioResults.add(buildScenarioItem(entry, decompTradeData, RESULT_KIND_RISK_CLASS_DECOMP));
                        }

                        dataObj.put("scenario_result", scenarioResults);
                        return baseJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                } finally {
                        OperModeControl.clear();
                        FrtbCalcControl.clear();
                }
        }

        private static boolean readFrtbDisabled(String jsonData) {
                if (jsonData == null) {
                        return false;
                }
                JSONObject payload = JSON.parseObject(jsonData);
                return payload != null && Boolean.TRUE.equals(payload.getBoolean("frtb_disable"));
        }

        /**
         * 构造仅曲线生成模式的统一返回结构。
         */
        private String buildCurveGenerationOnlyResult() {
                JSONObject mergedData = new JSONObject();
                mergedData.put("trade_data", new JSONArray());
                mergedData.put("log_data", new JSONArray());
                appendCurveGenerationOutput(mergedData);

                JSONObject result = new JSONObject();
                result.put("data", mergedData);
                return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
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
         * 将曲线生成输出和日志追加到统一返回结构。
         */
        private void appendCurveGenerationOutput(JSONObject dataObj) {
                if (dataObj == null) {
                        return;
                }
                dataObj.put("generated_market_data",
                                generatedMarketData == null ? new JSONArray() : generatedMarketData);
                if (curveGenerationErrors == null || curveGenerationErrors.isEmpty()) {
                        return;
                }
                JSONArray logData = getOrCreateArray(dataObj, "log_data");
                for (String error : curveGenerationErrors) {
                        JSONObject logItem = new JSONObject();
                        logItem.put("PRODUCT_CODE", "CURVE_GENERATION");
                        logItem.put("info", "曲线生成失败: " + error);
                        logData.add(logItem);
                }
        }

        /**
         * 统一将 PRICING / FRTB / SCENARIO 映射为 PRICING 执行主流程，
         * 避免修改各产品计算器的运行条件。
         */
        private static String normalizeOperCodeForExecution(String operCode) {
                return OperModeControl.normalizeForExecution(operCode);
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
        private String runWithMarketData(MarketData md, List<Runnable> cachedCalcs,
                        Set<String> scenarioProductCodes) {
                if (this.trades == null || this.trades.isEmpty()) {
                        JSONObject mergedData = new JSONObject();
                        mergedData.put("trade_data", new JSONArray());
                        if (this.validationErrors != null && !this.validationErrors.isEmpty()) {
                                mergedData.put("log_data", new JSONArray(this.validationErrors));
                        } else {
                                mergedData.put("log_data", new JSONArray());
                        }
                        JSONObject result = new JSONObject();
                        result.put("data", mergedData);
                        return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                }

                // 按 PRODUCT_CODE 分组
                Map<String, List<HashMap<String, Object>>> grouped = this.trades.stream()
                                .collect(Collectors.groupingBy(
                                                t -> Objects.toString(t.get("PRODUCT_CODE"), ""),
                                                LinkedHashMap::new,
                                                Collectors.toList()));

                JSONObject mergedData = new JSONObject();
                appendEmptyCalendarLogs(mergedData);

                for (Map.Entry<String, List<HashMap<String, Object>>> entry : grouped.entrySet()) {
                        String productCode = entry.getKey();
                        List<HashMap<String, Object>> groupTrades = entry.getValue();

                        CalcFactory factory = REGISTRY.get(productCode);
                        if (factory == null) {
                                addLog(mergedData, productCode, null, "不支持的产品类型: " + productCode);
                                continue;
                        }

                        // 创建并执行计算器
                        Runnable calcInstance;
                        try {
                                calcInstance = factory.create(
                                                this.operCode, this.dataDate, groupTrades, md, this.calendar,
                                                this.otherData);
                        } catch (Exception e) {
                                log.error("计算器初始化异常: productCode={}", productCode, e);
                                for (HashMap<String, Object> t : groupTrades) {
                                        addLog(mergedData, productCode,
                                                        Objects.toString(t.get("INSTRUMENT_ID"), ""),
                                                        "初始化异常: " + e.getMessage()
                                                                        + (e.getCause() != null
                                                                                        ? " - " + e.getCause()
                                                                                                        .getMessage()
                                                                                        : ""));
                                }
                                continue;
                        }

                        // 缓存计算器实例，并记录支持场景复用的产品类型
                        if (cachedCalcs != null) {
                                cachedCalcs.add(calcInstance);
                                if (calcInstance instanceof ScenarioCapable && scenarioProductCodes != null) {
                                        scenarioProductCodes.add(productCode);
                                }
                        }

                        String groupResult;
                        try {
                                groupResult = (String) calcInstance.getClass().getMethod("calc").invoke(calcInstance);
                        } catch (Exception e) {
                                log.error("计算执行异常: productCode={}", productCode, e);
                                for (HashMap<String, Object> t : groupTrades) {
                                        addLog(mergedData, productCode,
                                                        Objects.toString(t.get("INSTRUMENT_ID"), ""),
                                                        "计算异常: " + e.getMessage()
                                                                        + (e.getCause() != null
                                                                                        ? " - " + e.getCause()
                                                                                                        .getMessage()
                                                                                        : ""));
                                }
                                continue;
                        }

                        // 通用合并：遍历 data 下所有数组字段并追加
                        mergeData(mergedData, groupResult, productCode);
                }

                // 合并输入校验错误到 log_data
                if (this.validationErrors != null && !this.validationErrors.isEmpty()) {
                        getOrCreateArray(mergedData, "log_data").addAll(this.validationErrors);
                }

                JSONObject result = new JSONObject();
                result.put("data", mergedData);
                return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
        }

        // ===== 工具方法 =====

        /**
         * 获取或创建 JSONObject 中指定 key 的 JSONArray。
         */
        private static JSONArray getOrCreateArray(JSONObject obj, String key) {
                JSONArray arr = obj.getJSONArray(key);
                if (arr == null) {
                        arr = new JSONArray();
                        obj.put(key, arr);
                }
                return arr;
        }

        /**
         * 将日志项追加到 mergedData 的 log_data 数组。
         */
        private static void addLog(JSONObject mergedData, String productCode,
                        String instrumentId, String info) {
                JSONObject logItem = new JSONObject();
                if (instrumentId != null)
                        logItem.put("INSTRUMENT_ID", instrumentId);
                if (productCode != null)
                        logItem.put("PRODUCT_CODE", productCode);
                logItem.put("info", info);
                getOrCreateArray(mergedData, "log_data").add(logItem);
        }

        private void appendEmptyCalendarLogs(JSONObject mergedData) {
                if (this.trades == null || this.trades.isEmpty()) {
                        return;
                }
                for (HashMap<String, Object> tradeData : this.trades) {
                        if (tradeData == null || tradeData.isEmpty()) {
                                continue;
                        }
                        String productCode = Objects.toString(tradeData.get("PRODUCT_CODE"), "");
                        String instrumentId = Objects.toString(tradeData.get("INSTRUMENT_ID"), "");
                        for (String field : TRADE_CALENDAR_FIELDS) {
                                if (!tradeData.containsKey(field)) {
                                        continue;
                                }
                                Object value = tradeData.get(field);
                                if (value == null || value.toString().trim().isEmpty()) {
                                        addLog(mergedData, productCode, instrumentId,
                                                        "交易指定的日历为空: " + field);
                                }
                        }
                }
        }

        /**
         * 从计算结果 JSON 中提取 trade_data 并追加到目标数组。
         */
        private static void collectTradeData(String jsonResult, JSONArray target) {
                JSONObject json = JSON.parseObject(jsonResult);
                JSONObject data = json.getJSONObject("data");
                if (data != null) {
                        JSONArray trades = data.getJSONArray("trade_data");
                        if (trades != null) {
                                target.addAll(trades);
                        }
                }
        }

        /**
         * 将单个计算器结果合并到 mergedData，数组字段采用追加策略。
         */
        private static void mergeData(JSONObject mergedData, String groupResult, String defaultProductCode) {
                JSONObject groupJson = JSON.parseObject(groupResult);
                JSONObject groupData = groupJson.getJSONObject("data");
                if (groupData != null) {
                        for (String key : groupData.keySet()) {
                                Object value = groupData.get(key);
                                if (value instanceof JSONArray) {
                                        if ("trade_data".equals(key)) {
                                                JSONArray normalized = new JSONArray();
                                                for (Object item : (JSONArray) value) {
                                                        if (item instanceof JSONObject) {
                                                                JSONObject trade = (JSONObject) item;
                                                                String productCode = trade.getString("PRODUCT_CODE");
                                                                if (productCode == null || productCode.trim().isEmpty()) {
                                                                        trade.put("PRODUCT_CODE", defaultProductCode);
                                                                }
                                                        }
                                                        normalized.add(item);
                                                }
                                                getOrCreateArray(mergedData, key).addAll(normalized);
                                        } else {
                                                getOrCreateArray(mergedData, key).addAll((JSONArray) value);
                                        }
                                }
                        }
                }
        }

        /**
         * 按 INSTRUMENT_ID 匹配基准与场景估值并计算 PnL。
         */
        private static JSONArray buildPnlResults(Map<String, JSONObject> baseTradeIndex, JSONArray scenTradeResults) {
                JSONArray pnlResults = new JSONArray();
                if (baseTradeIndex == null || baseTradeIndex.isEmpty()) {
                        return pnlResults;
                }

                Map<String, JSONObject> scenTradeIndex = buildTradeIndex(scenTradeResults);
                for (Map.Entry<String, JSONObject> entry : baseTradeIndex.entrySet()) {
                        String id = entry.getKey();
                        JSONObject baseTrade = entry.getValue();
                        if (!hasText(id) || baseTrade == null) {
                                continue;
                        }

                        if (isErrorTrade(baseTrade)) {
                                pnlResults.add(buildErrorPnlRow(baseTrade, baseTrade));
                                continue;
                        }

                        JSONObject scenTrade = scenTradeIndex.get(id);
                        if (scenTrade == null) {
                                pnlResults.add(buildZeroPnlRow(baseTrade));
                                continue;
                        }

                        if (isErrorTrade(scenTrade)) {
                                pnlResults.add(buildErrorPnlRow(baseTrade, scenTrade));
                                continue;
                        }

                        double baseValCny = baseTrade.getDoubleValue("VALUATION_CNY");
                        double scenValCny = scenTrade.getDoubleValue("VALUATION_CNY");

                        JSONObject pnlResult = new JSONObject();
                        pnlResult.put("INSTRUMENT_ID", id);
                        pnlResult.put("BASE_VALUATION_CNY", baseValCny);
                        pnlResult.put("SCENARIO_VALUATION_CNY", scenValCny);
                        pnlResult.put("PNL", scenValCny - baseValCny);
                        pnlResults.add(pnlResult);
                }
                return pnlResults;
        }

        private static JSONObject buildZeroPnlRow(JSONObject baseTrade) {
                JSONObject pnlResult = new JSONObject();
                double baseValuationCny = baseTrade == null ? 0.0 : baseTrade.getDoubleValue("VALUATION_CNY");
                pnlResult.put("INSTRUMENT_ID", baseTrade == null ? null : baseTrade.getString("INSTRUMENT_ID"));
                pnlResult.put("BASE_VALUATION_CNY", baseValuationCny);
                pnlResult.put("SCENARIO_VALUATION_CNY", baseValuationCny);
                pnlResult.put("PNL", 0.0);
                return pnlResult;
        }

        private static JSONObject buildErrorPnlRow(JSONObject baseTrade, JSONObject errorSource) {
                JSONObject pnlResult = buildZeroPnlRow(baseTrade);
                pnlResult.put("STATUS", "ERROR");
                Object logs = errorSource == null ? null : errorSource.get("LOGS");
                if (logs != null) {
                        pnlResult.put("LOGS", logs);
                }
                Object detail = errorSource == null ? null : errorSource.get("DETAIL");
                if (detail != null) {
                        pnlResult.put("DETAIL", detail);
                }
                return pnlResult;
        }

        private static boolean isErrorTrade(JSONObject trade) {
                return trade != null && "ERROR".equalsIgnoreCase(Objects.toString(trade.get("STATUS"), ""));
        }

        /**
         * 构建基准交易索引，避免每个情景都重复遍历基准结果。
         */
        private static Map<String, JSONObject> buildTradeIndex(JSONArray trades) {
                Map<String, JSONObject> tradeIndex = new LinkedHashMap<>();
                if (trades == null) {
                        return tradeIndex;
                }
                for (int i = 0; i < trades.size(); i++) {
                        JSONObject trade = trades.getJSONObject(i);
                        if (trade == null) {
                                continue;
                        }
                        String instrumentId = trade.getString("INSTRUMENT_ID");
                        if (instrumentId != null) {
                                tradeIndex.put(instrumentId, trade);
                        }
                }
                return tradeIndex;
        }

        private static JSONArray buildEffectiveBaseTrades(
                        JSONArray baseTrades,
                        JSONArray logData,
                        List<HashMap<String, Object>> inputTrades) {
                JSONArray result = new JSONArray();
                Set<String> existedInstrumentIds = new LinkedHashSet<>();
                if (baseTrades != null) {
                        for (int i = 0; i < baseTrades.size(); i++) {
                                JSONObject trade = baseTrades.getJSONObject(i);
                                if (trade == null) {
                                        continue;
                                }
                                result.add(trade);
                                String instrumentId = trade.getString("INSTRUMENT_ID");
                                if (hasText(instrumentId)) {
                                        existedInstrumentIds.add(instrumentId.trim());
                                }
                        }
                }

                if (logData == null || logData.isEmpty()) {
                        return result;
                }

                Map<String, HashMap<String, Object>> inputTradeIndex = buildInputTradeIndex(inputTrades);
                LinkedHashMap<String, JSONObject> missingErrors = new LinkedHashMap<>();
                for (int i = 0; i < logData.size(); i++) {
                        JSONObject logItem = logData.getJSONObject(i);
                        if (logItem == null) {
                                continue;
                        }
                        String instrumentId = trimToNull(logItem.getString("INSTRUMENT_ID"));
                        if (instrumentId == null || existedInstrumentIds.contains(instrumentId)) {
                                continue;
                        }
                        JSONObject errorTrade = missingErrors.get(instrumentId);
                        if (errorTrade == null) {
                                errorTrade = buildBaseErrorTrade(instrumentId, logItem, inputTradeIndex.get(instrumentId));
                                missingErrors.put(instrumentId, errorTrade);
                        }
                        appendErrorLog(errorTrade, resolveLogMessage(logItem));
                }
                result.addAll(missingErrors.values());
                return result;
        }

        private static Map<String, HashMap<String, Object>> buildInputTradeIndex(List<HashMap<String, Object>> inputTrades) {
                Map<String, HashMap<String, Object>> index = new LinkedHashMap<>();
                if (inputTrades == null) {
                        return index;
                }
                for (HashMap<String, Object> trade : inputTrades) {
                        if (trade == null) {
                                continue;
                        }
                        String instrumentId = trimToNull(Objects.toString(trade.get("INSTRUMENT_ID"), null));
                        if (instrumentId != null) {
                                index.put(instrumentId, trade);
                        }
                }
                return index;
        }

        private static JSONObject buildBaseErrorTrade(
                        String instrumentId,
                        JSONObject logItem,
                        HashMap<String, Object> inputTrade) {
                JSONObject trade = new JSONObject();
                trade.put("INSTRUMENT_ID", instrumentId);
                String productCode = trimToNull(logItem == null ? null : logItem.getString("PRODUCT_CODE"));
                if (productCode == null && inputTrade != null) {
                        productCode = trimToNull(Objects.toString(inputTrade.get("PRODUCT_CODE"), null));
                }
                trade.put("PRODUCT_CODE", productCode);
                trade.put("STATUS", "ERROR");
                trade.put("VALUATION_CNY", 0.0);
                trade.put("VALUATION", 0.0);
                trade.put("LOGS", new JSONArray());
                return trade;
        }

        private static void appendErrorLog(JSONObject trade, String message) {
                String safeMessage = trimToNull(message);
                if (trade == null || safeMessage == null) {
                        return;
                }
                JSONArray logs = trade.getJSONArray("LOGS");
                if (logs == null) {
                        logs = new JSONArray();
                        trade.put("LOGS", logs);
                }
                JSONObject logItem = new JSONObject();
                logItem.put("level", "ERROR");
                logItem.put("message", safeMessage);
                logs.add(logItem);
                if (trade.get("DETAIL") == null) {
                        trade.put("DETAIL", safeMessage);
                }
        }

        static Runnable createRegisteredCalc(String productCode, String operCode, LocalDate dataDate,
                        List<HashMap<String, Object>> trades, MarketData md, Calendar calendar, JSONObject otherData) {
                CalcFactory factory = REGISTRY.get(productCode);
                if (factory == null) {
                        throw new IllegalArgumentException("不支持的产品类型: " + productCode);
                }
                return factory.create(operCode, dataDate, trades, md, calendar, otherData);
        }

        static String invokeCalc(Runnable calcInstance) throws Exception {
                return (String) calcInstance.getClass().getMethod("calc").invoke(calcInstance);
        }

        private static String resolveLogMessage(JSONObject logItem) {
                if (logItem == null) {
                        return null;
                }
                String message = trimToNull(logItem.getString("info"));
                if (message != null) {
                        return message;
                }
                message = trimToNull(logItem.getString("ERROR"));
                if (message != null) {
                        return message;
                }
                return trimToNull(logItem.getString("message"));
        }

        /**
         * 从基准估值结果中识别“不支持场景复用”的产品类型。
         */
        private static Set<String> collectUnsupportedScenarioProducts(JSONArray baseTrades, Set<String> scenarioProductCodes) {
                Set<String> unsupported = new LinkedHashSet<>();
                if (baseTrades == null || baseTrades.isEmpty()) {
                        return unsupported;
                }

                Set<String> supported = scenarioProductCodes == null
                                ? Collections.emptySet()
                                : scenarioProductCodes;
                for (int i = 0; i < baseTrades.size(); i++) {
                        JSONObject trade = baseTrades.getJSONObject(i);
                        if (trade == null) {
                                continue;
                        }
                        String productCode = Objects.toString(trade.get("PRODUCT_CODE"), "").trim();
                        if (productCode.isEmpty()) {
                                continue;
                        }
                        if (!supported.contains(productCode)) {
                                unsupported.add(productCode);
                        }
                }
                return unsupported;
        }

        /**
         * fast-skip 时直接返回零损益结果，避免构造场景市场并重复估值。
         */
        private static JSONArray buildZeroPnlResults(JSONArray baseTrades) {
                JSONArray pnlResults = new JSONArray();
                if (baseTrades == null) {
                        return pnlResults;
                }
                for (int i = 0; i < baseTrades.size(); i++) {
                        JSONObject baseTrade = baseTrades.getJSONObject(i);
                        if (baseTrade == null) {
                                continue;
                        }
                        if (isErrorTrade(baseTrade)) {
                                pnlResults.add(buildErrorPnlRow(baseTrade, baseTrade));
                        } else {
                                pnlResults.add(buildZeroPnlRow(baseTrade));
                        }
                }
                return pnlResults;
        }

        /**
         * 将 RISK_CLASS decomp 的五组结果聚合为一条 scenario_result。
         * 每笔交易同时携带 IR/FX/EQ/COMM/ALL 五组 valuation 与 pnl。
         */
        private JSONArray buildDecompScenarioTradeData(
                        Loader.ScenarioEntry entry,
                        String[] riskGroups,
                        JSONArray baseTrades,
                        Map<String, JSONObject> baseTradeIndex,
                        List<ScenarioCapable> scenarioCalcs,
                        RiskFactorMatcher.Index rfIndex,
                        Map<String, Set<String>> perTradeImpactKeys,
                        Map<String, Set<String>> factorToTradeIds) {
                LinkedHashMap<String, JSONObject> mergedTrades = initializeDecompTradeRows(baseTrades, riskGroups);
                if (entry == null || riskGroups == null || riskGroups.length == 0) {
                        return new JSONArray(mergedTrades.values());
                }

                for (String group : riskGroups) {
                        if (!hasText(group)) {
                                continue;
                        }
                        MarketData slicedMd = ScenarioCache.sliceByGroup(entry.marketData, group);
                        Set<String> slicedKeys = ScenarioCache.deriveKeysFromSlice(slicedMd, group);

                        RiskFactorMatcher.ScenarioImpactResolution impactResolution =
                                        RiskFactorMatcher.resolveScenarioKeys(slicedKeys, rfIndex);

                        Set<String> affectedIds = RiskFactorMatcher.resolveAffectedTradeIdsFast(
                                        factorToTradeIds, impactResolution);
                        if (affectedIds == null) {
                                affectedIds = RiskFactorMatcher.resolveAffectedTradeIds(
                                                perTradeImpactKeys, impactResolution);
                        }

                        if (affectedIds != null && affectedIds.isEmpty()) {
                                continue;
                        }

                        // 基于基准市场叠加当前 risk group 的情景切片
                        MarketData scenMarket = MarketData.updateMarketData(this.marketData, slicedMd);
                        JSONArray scenTradeResults = new JSONArray();
                        for (ScenarioCapable sc : scenarioCalcs) {
                                scenTradeResults.addAll(sc.calcScenario(scenMarket, affectedIds));
                        }

                        JSONArray pnlResults = buildPnlResults(baseTradeIndex, scenTradeResults);
                        mergeDecompGroupResults(mergedTrades, group, pnlResults);
                }
                return new JSONArray(mergedTrades.values());
        }

        /**
         * 初始化 decomp 聚合结果，默认所有风险组 valuation 使用基准估值，pnl=0。
         */
        private static LinkedHashMap<String, JSONObject> initializeDecompTradeRows(JSONArray baseTrades, String[] riskGroups) {
                LinkedHashMap<String, JSONObject> rows = new LinkedHashMap<>();
                if (baseTrades == null) {
                        return rows;
                }
                for (int i = 0; i < baseTrades.size(); i++) {
                        JSONObject baseTrade = baseTrades.getJSONObject(i);
                        if (baseTrade == null) {
                                continue;
                        }
                        String instrumentId = baseTrade.getString("INSTRUMENT_ID");
                        if (!hasText(instrumentId)) {
                                continue;
                        }
                        double baseValuationCny = baseTrade.getDoubleValue("VALUATION_CNY");
                        JSONObject row = new JSONObject();
                        row.put("INSTRUMENT_ID", instrumentId);
                        row.put("PRODUCT_CODE", baseTrade.getString("PRODUCT_CODE"));
                        row.put("BASE_VALUATION_CNY", baseValuationCny);
                        if (riskGroups != null) {
                                for (String group : riskGroups) {
                                        if (!hasText(group)) {
                                                continue;
                                        }
                                        row.put(group + "_VALUATION", baseValuationCny);
                                        row.put(group + "_PNL", 0.0);
                                }
                        }
                        rows.put(instrumentId, row);
                }
                return rows;
        }

        /**
         * 将单个风险组的 PnL 结果写回聚合结果。
         */
        private static void mergeDecompGroupResults(
                        Map<String, JSONObject> mergedTrades,
                        String group,
                        JSONArray pnlResults) {
                if (mergedTrades == null || mergedTrades.isEmpty() || !hasText(group) || pnlResults == null) {
                        return;
                }
                String valuationKey = group + "_VALUATION";
                String pnlKey = group + "_PNL";
                for (int i = 0; i < pnlResults.size(); i++) {
                        JSONObject pnlRow = pnlResults.getJSONObject(i);
                        if (pnlRow == null) {
                                continue;
                        }
                        String instrumentId = pnlRow.getString("INSTRUMENT_ID");
                        if (!hasText(instrumentId)) {
                                continue;
                        }
                        JSONObject mergedRow = mergedTrades.get(instrumentId);
                        if (mergedRow == null) {
                                continue;
                        }
                        if (isErrorTrade(pnlRow)) {
                                appendDecompLog(mergedRow, group, pnlRow.getJSONArray("LOGS"));
                                continue;
                        }
                        mergedRow.put(valuationKey, pnlRow.getDoubleValue("SCENARIO_VALUATION_CNY"));
                        mergedRow.put(pnlKey, pnlRow.getDoubleValue("PNL"));
                }
        }

        private static void appendDecompLog(JSONObject row, String group, JSONArray sourceLogs) {
                if (row == null) {
                        return;
                }
                String safeGroup = hasText(group) ? group.trim() : "UNKNOWN";
                row.put("STATUS", "ERROR");
                JSONArray logs = row.getJSONArray("LOGS");
                if (logs == null) {
                        logs = new JSONArray();
                        row.put("LOGS", logs);
                }
                if (sourceLogs == null || sourceLogs.isEmpty()) {
                        JSONObject log = new JSONObject();
                        log.put("level", "ERROR");
                        log.put("message", safeGroup + ": 风险类别分解计算异常");
                        logs.add(log);
                        return;
                }
                for (int i = 0; i < sourceLogs.size(); i++) {
                        JSONObject source = sourceLogs.getJSONObject(i);
                        if (source == null) {
                                continue;
                        }
                        JSONObject log = new JSONObject();
                        log.put("level", Objects.toString(source.get("level"), "ERROR"));
                        log.put("message", safeGroup + ": " + Objects.toString(source.get("message"), ""));
                        logs.add(log);
                }
        }

        /**
         * 统一封装场景输出条目，补齐场景标识。
         */
        private static JSONObject buildScenarioItem(Loader.ScenarioEntry entry, JSONArray tradeData, String resultKind) {
                JSONObject item = new JSONObject();
                if (entry != null) {
                        if (hasText(entry.scenarioId)) {
                                item.put("SCENARIO_ID", entry.scenarioId);
                        }
                        if (hasText(entry.subScenarioId)) {
                                item.put("SUBSCENARIO_ID", entry.subScenarioId);
                        }
                        item.put("SCENARIO_NAME", entry.scenarioName);
                        if (hasText(entry.scenarioType)) {
                                item.put("SCENARIO_TYPE", entry.scenarioType);
                        }
                } else {
                        item.put("SCENARIO_NAME", null);
                }
                if (hasText(resultKind)) {
                        item.put("RESULT_KIND", resultKind);
                }
                item.put("trade_data", tradeData == null ? new JSONArray() : tradeData);
                return item;
        }

        private static boolean hasText(String s) {
                return s != null && !s.trim().isEmpty();
        }

        private static String trimToNull(String text) {
                if (text == null) {
                        return null;
                }
                String value = text.trim();
                return value.isEmpty() ? null : value;
        }

}
