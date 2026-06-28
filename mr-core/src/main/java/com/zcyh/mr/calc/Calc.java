package com.zcyh.mr.calc;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.result.CalcResultProcessService;
import com.zcyh.mr.calc.scenario.CalcScenarioProcessService;
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
                this.rawJsonData = jsonData;
                this.trades = loader.getTrades();
                this.marketData = loader.getMarketData();
                this.dataDate = loader.getDataDate();
                this.rawCalcMode = loader.getCalcMode();
                this.calcMode = Constants.CALC_MODE.PRICING;
                this.frtbDisabled = readFrtbDisabled(jsonData);
                this.calendar = loader.getCalendar();
                this.otherData = loader.getOtherData();
                this.validationErrors = loader.getValidationErrors();
                this.curveGenerationInputs = loader.getCurveGenerationInputs();

                this.scenarioDataList = CalcScenarioProcessService.resolveScenarioData(jsonData, loader);
        }

        /**
         * 执行估值主流程，按产品分组分发并合并结果。
         * 传入场景数据时生成并附加 scenario_result。
         * 当 calc_mode=CURVE_GENERATION 时，仅生成曲线并返回 generated_market_data。
         *
         * @return 包含基准估值和场景估值的 JSON 字符串
         */
        public String run() {
                OperModeControl.init(this.rawCalcMode);
                FrtbCalcControl.init(this.frtbDisabled);
                try {
                        applyCurveGeneration();
                        if (OperModeControl.isCurveGenerationOnly()) {
                                return CalcResultProcessService.buildCurveGenerationOnlyResult(
                                                generatedMarketData, curveGenerationErrors);
                        }

                        // 先执行基准估值，并收集计算器实例与可场景复用的产品类型
                        List<Runnable> cachedCalcs = new ArrayList<>();
                        Set<String> scenarioProductCodes = new LinkedHashSet<>();
                        String baseResult = runWithMarketData(this.marketData, cachedCalcs, scenarioProductCodes);
                        JSONObject baseJson = JSON.parseObject(baseResult);
                        JSONObject dataObj = baseJson.getJSONObject("data");
                        if (dataObj != null) {
                                CalcResultProcessService.appendCurveGenerationOutput(
                                                dataObj, generatedMarketData, curveGenerationErrors);
                        }

                        List<Loader.ScenarioEntry> scenarioEntries = (scenarioDataList == null)
                                        ? Collections.emptyList()
                                        : scenarioDataList;
                        if (scenarioEntries.isEmpty()) {
                                return baseJson.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
                        }

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

                        JSONArray baseTrades = CalcResultProcessService.buildEffectiveBaseTrades(
                                        dataObj.getJSONArray("trade_data"),
                                        dataObj.getJSONArray("log_data"),
                                        this.trades);
                        dataObj.put("trade_data", baseTrades);
                        Map<String, JSONObject> baseTradeIndex = CalcResultProcessService.buildTradeIndex(baseTrades);
                        Set<String> unsupportedScenarioProducts = CalcResultProcessService.collectUnsupportedScenarioProducts(
                                        baseTrades, scenarioProductCodes);
                        for (String productCode : unsupportedScenarioProducts) {
                                CalcResultProcessService.addLog(dataObj, productCode, null,
                                                "场景估值跳过该产品：对应计算器未实现 ScenarioCapable");
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
                                        scenarioResults.add(CalcResultProcessService.buildScenarioItem(
                                                        entry,
                                                        CalcResultProcessService.buildZeroPnlResults(baseTrades),
                                                        RESULT_KIND_SCENARIO));
                                        continue;
                                }

                                MarketData scenMarket = MarketData.updateMarketData(this.marketData, entry.marketData);
                                // 汇总当前场景下的交易结果（仅重估受影响的交易）
                                JSONArray scenTradeResults = new JSONArray();
                                for (ScenarioCapable sc : scenarioCalcs) {
                                        scenTradeResults.addAll(sc.calcScenario(scenMarket, affectedIds));
                                }

                                // 按 INSTRUMENT_ID 对齐基准与场景结果并计算 PnL
                                JSONArray pnlResults = CalcResultProcessService.buildPnlResults(
                                                baseTradeIndex, scenTradeResults);

                                scenarioResults.add(CalcResultProcessService.buildScenarioItem(
                                                entry, pnlResults, RESULT_KIND_SCENARIO));
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
                CalcResultProcessService.appendEmptyCalendarLogs(mergedData, this.trades);

                for (Map.Entry<String, List<HashMap<String, Object>>> entry : grouped.entrySet()) {
                        String productCode = entry.getKey();
                        List<HashMap<String, Object>> groupTrades = entry.getValue();

                        CalcFactory factory = REGISTRY.get(productCode);
                        if (factory == null) {
                                CalcResultProcessService.addLog(mergedData, productCode, null,
                                                "不支持的产品类型: " + productCode);
                                continue;
                        }

                        // 创建并执行计算器
                        Runnable calcInstance;
                        try {
                                calcInstance = factory.create(
                                                this.calcMode, this.dataDate, groupTrades, md, this.calendar,
                                                this.otherData);
                        } catch (Exception e) {
                                log.error("计算器初始化异常: productCode={}", productCode, e);
                                for (HashMap<String, Object> t : groupTrades) {
                                        CalcResultProcessService.addLog(mergedData, productCode,
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
                                        CalcResultProcessService.addLog(mergedData, productCode,
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
                        CalcResultProcessService.mergeData(mergedData, groupResult, productCode);
                }

                // 合并输入校验错误到 log_data
                if (this.validationErrors != null && !this.validationErrors.isEmpty()) {
                        CalcResultProcessService.getOrCreateArray(mergedData, "log_data").addAll(this.validationErrors);
                }

                JSONObject result = new JSONObject();
                result.put("data", mergedData);
                return result.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain);
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

}
