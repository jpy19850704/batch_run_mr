package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractCalc;
import com.zcyh.mr.calc.ProductCalculator;
import com.zcyh.mr.calc.ProductCalculatorRegistry;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.support.EngineConstants;
import com.zcyh.mr.support.JsonNumberUtils;
import com.zcyh.mr.support.TradeJsonUtil;
import com.zcyh.mr.loader.TradeValidator;
import com.zcyh.mr.marketdata.MarketData;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 组合产品计算器。
 * 组合产品本身不实现定价模型，只负责将组成项 DATA 交给现有产品计算器，再按 WEIGHT 聚合结果。
 */
public class CompositeCalc implements ProductCalculator {
    private static final String FIELD_COMPONENTS = "COMPONENTS";
    private static final String FIELD_COMPONENT_ID = "COMPONENT_ID";
    private static final String FIELD_WEIGHT = "WEIGHT";
    private static final String FIELD_DATA = "DATA";

    private static final String[] NUMERIC_FIELDS = {
            "VALUATION", "VALUATION_CNY", "PV01", "DELTA", "GAMMA", "VEGA", "THETA", "RHO"
    };

    private final List<HashMap<String, Object>> trades;
    private final MarketData marketData;
    private final LocalDate dataDate;
    private final String operCode;
    private final Calendar calendar;
    private final JSONObject otherData;

    private final JSONObject result = new JSONObject();
    private final JSONArray tradeResult = new JSONArray();
    private final Map<String, CompositeRuntime> compositeCache = new LinkedHashMap<>();

    public CompositeCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar, JSONObject otherData) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades;
        this.marketData = marketData;
        this.calendar = calendar;
        this.otherData = otherData;
    }

    public String calc() {
        calculateTrades();
        this.result.put("data", new JSONObject());
        ((JSONObject) this.result.get("data")).put("trade_data", tradeResult);
        JsonNumberUtils.normalizeNumbersInPlace(this.result);
        return JSON.toJSONString(this.result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    private void calculateTrades() {
        if (!EngineConstants.CALC_MODE.PRICING.equalsIgnoreCase(operCode)) {
            return;
        }
        for (HashMap<String, Object> trade : trades) {
            try {
                calcTrade(trade);
            } catch (Exception e) {
                AbstractCalc.appendErrorResult(tradeResult, dataDate, trade, unwrap(e));
            }
        }
    }

    private void calcTrade(HashMap<String, Object> compositeTrade) throws Exception {
        String compositeId = requiredText(compositeTrade.get("INSTRUMENT_ID"), "INSTRUMENT_ID");
        List<ComponentSpec> specs = parseComponents(compositeId, compositeTrade);
        ComponentRun run = runComponents(specs, marketData, true);
        JSONObject measure = aggregateComposite(compositeTrade, specs, run.componentMeasures, run.componentLogs);
        tradeResult.add(measure);
        compositeCache.put(compositeId, new CompositeRuntime(compositeId, specs, run.scenarioCalcs,
                run.unsupportedScenarioProducts));
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioResult = new JSONArray();
        for (CompositeRuntime runtime : compositeCache.values()) {
            if (affectedIds != null && !affectedIds.contains(runtime.compositeId)) {
                continue;
            }
            try {
                if (!runtime.unsupportedScenarioProducts.isEmpty()) {
                    throw new IllegalArgumentException("组合产品组成项不支持场景复用: "
                            + String.join(",", runtime.unsupportedScenarioProducts));
                }
                ComponentRun run = runScenarioComponents(runtime, scenarioMd);
                HashMap<String, Object> baseTrade = findCompositeTrade(runtime.compositeId);
                scenarioResult.add(aggregateComposite(baseTrade, runtime.components,
                        run.componentMeasures, run.componentLogs));
            } catch (Exception e) {
                scenarioResult.add(buildErrorMeasure(runtime.compositeId, unwrap(e)));
            }
        }
        return scenarioResult;
    }

    private List<ComponentSpec> parseComponents(String compositeId, HashMap<String, Object> compositeTrade) {
        Object rawComponents = compositeTrade.get(FIELD_COMPONENTS);
        if (!(rawComponents instanceof JSONArray)) {
            throw new IllegalArgumentException("COMPOSITE 缺少数组字段: " + FIELD_COMPONENTS);
        }
        JSONArray components = (JSONArray) rawComponents;
        if (components.isEmpty()) {
            throw new IllegalArgumentException("COMPOSITE COMPONENTS 不能为空");
        }

        List<ComponentSpec> specs = new ArrayList<>();
        Set<String> componentIds = new LinkedHashSet<>();
        Set<String> instrumentIds = new LinkedHashSet<>();
        for (int i = 0; i < components.size(); i++) {
            Object raw = components.get(i);
            if (!(raw instanceof JSONObject)) {
                throw new IllegalArgumentException("COMPOSITE COMPONENTS[" + i + "] 必须为对象");
            }
            JSONObject component = (JSONObject) raw;
            String componentId = requiredText(component.get(FIELD_COMPONENT_ID),
                    "COMPONENTS[" + i + "]." + FIELD_COMPONENT_ID);
            if (!componentIds.add(componentId)) {
                throw new IllegalArgumentException("COMPOSITE COMPONENT_ID 重复: " + componentId);
            }
            double weight = requiredNumber(component.get(FIELD_WEIGHT),
                    "COMPONENTS[" + i + "]." + FIELD_WEIGHT);
            Object rawData = component.get(FIELD_DATA);
            if (!(rawData instanceof JSONObject)) {
                throw new IllegalArgumentException("COMPOSITE COMPONENTS[" + i + "].DATA 必须为对象");
            }
            JSONObject data = copyJson((JSONObject) rawData);
            String productCode = requiredText(data.get("PRODUCT_CODE"),
                    "COMPONENTS[" + i + "].DATA.PRODUCT_CODE");
            if (EngineConstants.PRODUCT_CODE.COMPOSITE.equals(productCode)) {
                throw new IllegalArgumentException("COMPOSITE 组成项 DATA.PRODUCT_CODE 不能为 COMPOSITE");
            }
            String instrumentId = requiredText(data.get("INSTRUMENT_ID"),
                    "COMPONENTS[" + i + "].DATA.INSTRUMENT_ID");
            if (!instrumentIds.add(instrumentId)) {
                throw new IllegalArgumentException("COMPOSITE 组成项 DATA.INSTRUMENT_ID 重复: " + instrumentId);
            }
            List<String> errors = TradeValidator.validate(data, productCode, "TRADE");
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("COMPOSITE 组成项数据校验失败 componentId=" + componentId
                        + ": " + String.join("; ", errors));
            }
            JSONObject normalizedData = TradeJsonUtil.mergeTrade(data, productCode, "TRADE");
            specs.add(new ComponentSpec(componentId, weight, productCode, instrumentId, toMap(normalizedData)));
        }
        return specs;
    }

    private ComponentRun runComponents(List<ComponentSpec> specs, MarketData md, boolean cacheScenarioCalcs)
            throws Exception {
        Map<String, List<HashMap<String, Object>>> grouped = new LinkedHashMap<>();
        for (ComponentSpec spec : specs) {
            grouped.computeIfAbsent(spec.productCode, ignored -> new ArrayList<>()).add(spec.tradeData);
        }

        ComponentRun run = new ComponentRun();
        for (Map.Entry<String, List<HashMap<String, Object>>> entry : grouped.entrySet()) {
            String productCode = entry.getKey();
            ProductCalculator calc = ProductCalculatorRegistry.create(productCode, operCode, dataDate,
                    entry.getValue(), md,
                    calendar, otherData);
            String json = calc.calc();
            collectComponentOutput(json, run);
            if (cacheScenarioCalcs) {
                run.scenarioCalcs.add(calc);
            }
        }
        ensureAllComponentMeasures(specs, run.componentMeasures);
        return run;
    }

    private ComponentRun runScenarioComponents(CompositeRuntime runtime, MarketData scenarioMd) {
        ComponentRun run = new ComponentRun();
        for (ProductCalculator calc : runtime.scenarioCalcs) {
            JSONArray scenarioMeasures = calc.calcScenario(scenarioMd, null);
            collectMeasures(scenarioMeasures, run.componentMeasures);
        }
        ensureAllComponentMeasures(runtime.components, run.componentMeasures);
        return run;
    }

    private JSONObject aggregateComposite(HashMap<String, Object> compositeTrade, List<ComponentSpec> specs,
            Map<String, JSONObject> componentMeasures, JSONArray componentLogs) {
        String compositeId = Objects.toString(compositeTrade.get("INSTRUMENT_ID"), "");
        JSONObject measure = new JSONObject();
        measure.put("INSTRUMENT_ID", compositeId);
        measure.put("PRODUCT_CODE", EngineConstants.PRODUCT_CODE.COMPOSITE);
        measure.put("DATA_DATE", dataDate);

        Object ccy = compositeTrade.get("VALUATION_CCY");
        if (ccy != null && !ccy.toString().trim().isEmpty()) {
            measure.put("VALUATION_CCY", ccy.toString().trim());
        }
        if (isNumberLike(compositeTrade.get("POSITION"))) {
            double position = requiredNumber(compositeTrade.get("POSITION"), "POSITION");
            measure.put("POSITION", position);
        }

        Map<String, Double> totals = new LinkedHashMap<>();
        for (String field : NUMERIC_FIELDS) {
            totals.put(field, 0.0);
        }

        JSONArray detailComponents = new JSONArray();
        JSONArray logs = new JSONArray();
        boolean success = true;
        for (ComponentSpec spec : specs) {
            JSONObject componentMeasure = componentMeasures.get(spec.instrumentId);
            if (componentMeasure == null) {
                success = false;
                appendLog(logs, "ERROR", "组成项缺少计量结果: " + spec.componentId);
                continue;
            }
            for (String field : NUMERIC_FIELDS) {
                totals.put(field, totals.get(field) + spec.weight * numberValue(componentMeasure.get(field)));
            }
            if (!"SUCCESS".equals(componentMeasure.getString("STATUS"))) {
                success = false;
                appendLog(logs, "ERROR", "组成项计量失败: " + spec.componentId);
            }
            JSONArray componentMeasureLogs = componentMeasure.getJSONArray("LOGS_JSON");
            if (componentMeasureLogs != null) {
                logs.addAll(componentMeasureLogs);
            }

            JSONObject detail = new JSONObject();
            detail.put("componentId", spec.componentId);
            detail.put("weight", spec.weight);
            detail.put("productCode", spec.productCode);
            detail.put("instrumentId", spec.instrumentId);
            detail.put("measure", componentMeasure);
            detailComponents.add(detail);
        }

        for (Map.Entry<String, Double> entry : totals.entrySet()) {
            measure.put(entry.getKey(), entry.getValue());
        }
        if (measure.containsKey("POSITION")) {
            double position = measure.getDoubleValue("POSITION");
            measure.put("VALUATION_UNIT", position == 0.0 ? 0.0 : measure.getDoubleValue("VALUATION") / position);
        }
        measure.put("STATUS", success ? "SUCCESS" : "ERROR");
        if (componentLogs != null && !componentLogs.isEmpty()) {
            logs.addAll(componentLogs);
        }
        measure.put("LOGS_JSON", logs);
        measure.put("FRTB_SENSITIVITY", aggregateSensitivity(compositeId, specs, componentMeasures));

        JSONObject detail = new JSONObject();
        detail.put("components", detailComponents);
        measure.put("DETAIL", detail);
        return measure;
    }

    private JSONArray aggregateSensitivity(String compositeId, List<ComponentSpec> specs,
            Map<String, JSONObject> componentMeasures) {
        Map<String, JSONObject> aggregated = new LinkedHashMap<>();
        for (ComponentSpec spec : specs) {
            JSONObject componentMeasure = componentMeasures.get(spec.instrumentId);
            if (componentMeasure == null) {
                continue;
            }
            JSONArray sensitivityList = componentMeasure.getJSONArray("FRTB_SENSITIVITY");
            if (sensitivityList == null || sensitivityList.isEmpty()) {
                continue;
            }
            for (int i = 0; i < sensitivityList.size(); i++) {
                JSONObject src = sensitivityList.getJSONObject(i);
                if (src == null) {
                    continue;
                }
                String key = sensitivityKey(src);
                JSONObject target = aggregated.get(key);
                if (target == null) {
                    target = copyJson(src);
                    target.put("INSTRUMENT_ID", compositeId);
                    target.put("SENSITIVITY_VAL_INST_CURR", 0.0);
                    target.put("SENSITIVITY_VAL_INST_CURR_CNY", 0.0);
                    target.remove("DETAIL");
                    aggregated.put(key, target);
                }
                target.put("SENSITIVITY_VAL_INST_CURR",
                        target.getDoubleValue("SENSITIVITY_VAL_INST_CURR")
                                + spec.weight * numberValue(src.get("SENSITIVITY_VAL_INST_CURR")));
                target.put("SENSITIVITY_VAL_INST_CURR_CNY",
                        target.getDoubleValue("SENSITIVITY_VAL_INST_CURR_CNY")
                                + spec.weight * numberValue(src.get("SENSITIVITY_VAL_INST_CURR_CNY")));
            }
        }
        JSONArray result = new JSONArray();
        result.addAll(aggregated.values());
        return result;
    }

    private void collectComponentOutput(String jsonResult, ComponentRun run) {
        JSONObject json = JSON.parseObject(jsonResult);
        JSONObject data = json == null ? null : json.getJSONObject("data");
        if (data == null) {
            return;
        }
        collectMeasures(data.getJSONArray("trade_data"), run.componentMeasures);
    }

    private void collectMeasures(JSONArray measures, Map<String, JSONObject> target) {
        if (measures == null || measures.isEmpty()) {
            return;
        }
        for (int i = 0; i < measures.size(); i++) {
            JSONObject measure = measures.getJSONObject(i);
            if (measure == null) {
                continue;
            }
            String instrumentId = measure.getString("INSTRUMENT_ID");
            if (instrumentId == null || instrumentId.trim().isEmpty()) {
                continue;
            }
            if (target.put(instrumentId, measure) != null) {
                throw new IllegalArgumentException("COMPOSITE 组成项计量结果 INSTRUMENT_ID 重复: " + instrumentId);
            }
        }
    }

    private void ensureAllComponentMeasures(List<ComponentSpec> specs, Map<String, JSONObject> measures) {
        for (ComponentSpec spec : specs) {
            if (!measures.containsKey(spec.instrumentId)) {
                throw new IllegalArgumentException("COMPOSITE 组成项未返回计量结果: " + spec.instrumentId);
            }
        }
    }

    private HashMap<String, Object> findCompositeTrade(String compositeId) {
        for (HashMap<String, Object> trade : trades) {
            if (Objects.equals(compositeId, Objects.toString(trade.get("INSTRUMENT_ID"), ""))) {
                return trade;
            }
        }
        HashMap<String, Object> result = new HashMap<>();
        result.put("INSTRUMENT_ID", compositeId);
        return result;
    }

    private JSONObject buildErrorMeasure(String compositeId, Exception e) {
        JSONObject measure = new JSONObject();
        measure.put("INSTRUMENT_ID", compositeId);
        measure.put("PRODUCT_CODE", EngineConstants.PRODUCT_CODE.COMPOSITE);
        measure.put("DATA_DATE", dataDate);
        measure.put("STATUS", "ERROR");
        JSONArray logs = new JSONArray();
        appendLog(logs, "ERROR", "计算异常: " + resolveErrorMessage(e));
        measure.put("LOGS_JSON", logs);
        return measure;
    }

    private static JSONObject copyJson(JSONObject src) {
        JSONObject dst = new JSONObject();
        if (src != null) {
            dst.putAll(src);
        }
        return dst;
    }

    private static HashMap<String, Object> toMap(JSONObject data) {
        HashMap<String, Object> map = new HashMap<>();
        map.putAll(data);
        return map;
    }

    private static String requiredText(Object value, String field) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("缺少必填字段: " + field);
        }
        return text;
    }

    private static double requiredNumber(Object value, String field) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("缺少必填字段: " + field);
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必须为数字: " + text);
        }
    }

    private static boolean isNumberLike(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (value == null) {
            return false;
        }
        try {
            Double.parseDouble(value.toString().trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static double numberValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static void appendLog(JSONArray logs, String level, String message) {
        JSONObject log = new JSONObject();
        log.put("level", level);
        log.put("message", message);
        logs.add(log);
    }

    private static String sensitivityKey(JSONObject sensitivity) {
        return String.join("|",
                Objects.toString(sensitivity.get("RISK_FACTOR_ID"), ""),
                Objects.toString(sensitivity.get("RISK_FACTOR_VERTEX_1"), ""),
                Objects.toString(sensitivity.get("RISK_FACTOR_VERTEX_2"), ""),
                Objects.toString(sensitivity.get("RISK_FACTOR_CLASS"), ""),
                Objects.toString(sensitivity.get("RISK_FACTOR_BUCKET"), ""),
                Objects.toString(sensitivity.get("RISK_FACTOR_TYPE"), ""),
                Objects.toString(sensitivity.get("SENSITIVITY_TYPE"), ""),
                Objects.toString(sensitivity.get("INSTRUMENT_CURRENCY"), ""));
    }

    private static Exception unwrap(Exception e) {
        if (e instanceof InvocationTargetException && ((InvocationTargetException) e).getTargetException() != null) {
            Throwable target = ((InvocationTargetException) e).getTargetException();
            return target instanceof Exception ? (Exception) target : new RuntimeException(target);
        }
        return e;
    }

    private static String resolveErrorMessage(Exception e) {
        if (e == null) {
            return "unknown";
        }
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static final class ComponentSpec {
        final String componentId;
        final double weight;
        final String productCode;
        final String instrumentId;
        final HashMap<String, Object> tradeData;

        ComponentSpec(String componentId, double weight, String productCode, String instrumentId,
                HashMap<String, Object> tradeData) {
            this.componentId = componentId;
            this.weight = weight;
            this.productCode = productCode;
            this.instrumentId = instrumentId;
            this.tradeData = tradeData;
        }
    }

    private static final class ComponentRun {
        final Map<String, JSONObject> componentMeasures = new LinkedHashMap<>();
        final JSONArray componentLogs = new JSONArray();
        final List<ProductCalculator> scenarioCalcs = new ArrayList<>();
        final Set<String> unsupportedScenarioProducts = new LinkedHashSet<>();
    }

    private static final class CompositeRuntime {
        final String compositeId;
        final List<ComponentSpec> components;
        final List<ProductCalculator> scenarioCalcs;
        final Set<String> unsupportedScenarioProducts;

        CompositeRuntime(String compositeId, List<ComponentSpec> components,
                List<ProductCalculator> scenarioCalcs, Set<String> unsupportedScenarioProducts) {
            this.compositeId = compositeId;
            this.components = components;
            this.scenarioCalcs = scenarioCalcs;
            this.unsupportedScenarioProducts = unsupportedScenarioProducts;
        }
    }
}
