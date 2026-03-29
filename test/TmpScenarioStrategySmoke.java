import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.scenario.model.ScenarioDefinition;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioMarketSeries;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 场景策略轻量冒烟测试。
 */
public class TmpScenarioStrategySmoke {

    public static void main(String[] args) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ScenarioGenerationEngine engine = new ScenarioGenerationEngine(executor, new Calendar());
            runCustom(engine);
            runKeyRate(engine);
            runMc(engine);
            runMcInconsistentShouldFail(engine);
            runVar(engine);
            runVarMixedShock(engine);
        } finally {
            executor.shutdown();
        }
    }

    private static void runCustom(ScenarioGenerationEngine engine) {
        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId("SMOKE_CUSTOM");
        task.setScenarioType("CUSTOM");
        task.setValuationDate(LocalDate.of(2024, 12, 31));
        task.setDefinitions(Arrays.asList(
                buildDefinition("SMOKE_CUSTOM", "SMOKE_CUSTOM", "自定义冒烟", "CUSTOM",
                        "IR_SPOT", "IR_CURVE_CNY", null, 365, "0.001", "ABSOLUTE", "ABSOLUTE"),
                buildDefinition("SMOKE_CUSTOM", "SMOKE_CUSTOM", "自定义冒烟", "CUSTOM",
                        "IR_SPOT", "IR_CURVE_CNY", null, 1095, "0.003", "ABSOLUTE", "ABSOLUTE")));
        task.setCurrentMarketData(singleCurve("IR_SPOT",
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.02"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.025"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "3Y", 1095, null, "0.03")));

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList("SMOKE_CUSTOM");
        request.setValuationDate(task.getValuationDate());
        request.setUser("smoke");
        request.setSource("tmp");
        request.setTasks(Arrays.asList(task));

        List<ScenarioGeneratedRecord> records = engine.generate(request);
        System.out.println("CUSTOM_ROWS=" + records.size());
        if (!records.isEmpty()) {
            ScenarioGeneratedRecord mid = records.stream()
                    .filter(r -> "2Y".equals(r.getTermCode()))
                    .findFirst()
                    .orElse(records.get(0));
            System.out.println("CUSTOM_MID="
                    + mid.getCurveCode()
                    + ",TERM=" + mid.getTermCode()
                    + ",ORI=" + mid.getOriginalValue()
                    + ",NEW=" + mid.getChangedValue()
                    + ",SHIFT=" + mid.getShiftValue());
        }
    }

    private static void runKeyRate(ScenarioGenerationEngine engine) {
        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId("SMOKE_KEYRATE");
        task.setScenarioType("KEY_RATE");
        task.setValuationDate(LocalDate.of(2024, 12, 31));
        task.setDefinitions(Arrays.asList(
                buildDefinition("SMOKE_KEYRATE", "SMOKE_KEYRATE", "关键期限冒烟", "KEY_RATE",
                        "IR_SPOT", "IR_CURVE_CNY", null, 365, "0.001", "ABSOLUTE", "ABSOLUTE"),
                buildDefinition("SMOKE_KEYRATE", "SMOKE_KEYRATE", "关键期限冒烟", "KEY_RATE",
                        "IR_SPOT", "IR_CURVE_CNY", null, 1095, "0.003", "ABSOLUTE", "ABSOLUTE")));
        task.setCurrentMarketData(singleCurve("IR_SPOT",
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.02"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.025"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "3Y", 1095, null, "0.03")));

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList("SMOKE_KEYRATE");
        request.setValuationDate(task.getValuationDate());
        request.setUser("smoke");
        request.setSource("tmp");
        request.setTasks(Arrays.asList(task));

        List<ScenarioGeneratedRecord> records = engine.generate(request);
        System.out.println("KEYRATE_ROWS=" + records.size());
        if (!records.isEmpty()) {
            ScenarioGeneratedRecord sample = records.stream()
                    .filter(r -> r.getSubScenarioId() != null && r.getSubScenarioId().endsWith("-365"))
                    .filter(r -> "1Y".equals(r.getTermCode()))
                    .findFirst()
                    .orElse(records.get(0));
            System.out.println("KEYRATE_SAMPLE="
                    + sample.getSubScenarioId()
                    + ",TERM=" + sample.getTermCode()
                    + ",ORI=" + sample.getOriginalValue()
                    + ",NEW=" + sample.getChangedValue()
                    + ",SHIFT=" + sample.getShiftValue());
        }
    }

    private static void runMc(ScenarioGenerationEngine engine) {
        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId("SMOKE_MC");
        task.setScenarioType("MC");
        task.setValuationDate(LocalDate.of(2024, 12, 31));
        ScenarioDefinition definition = buildDefinition("SMOKE_MC", "SMOKE_MC", "蒙特卡洛冒烟", "MC",
                "IR_SPOT", "IR_CURVE_CNY", "1Y", 365, "0", "ABSOLUTE", "ABSOLUTE");
        definition.setScenarioNo(3);
        definition.setIncreaseDays(1);
        definition.setJumpDayNo(1);
        task.setDefinitions(Arrays.asList(definition));
        task.setCurrentMarketData(singleCurve("IR_SPOT",
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.02"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.025")));
        task.setHistoricalMarketData(buildHistory("IR_SPOT", 40,
                historySeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.015", "0.0001"),
                historySeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.021", "0.00015")));

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList("SMOKE_MC");
        request.setValuationDate(task.getValuationDate());
        request.setUser("smoke");
        request.setSource("tmp");
        request.setTasks(Arrays.asList(task));

        List<ScenarioGeneratedRecord> records = engine.generate(request);
        System.out.println("MC_ROWS=" + records.size());
        if (!records.isEmpty()) {
            ScenarioGeneratedRecord sample = records.get(0);
            System.out.println("MC_SAMPLE="
                    + sample.getSubScenarioId()
                    + ",TERM=" + sample.getTermCode()
                    + ",ORI=" + sample.getOriginalValue()
                    + ",NEW=" + sample.getChangedValue()
                    + ",SHIFT=" + sample.getShiftValue());
        }
    }

    private static void runMcInconsistentShouldFail(ScenarioGenerationEngine engine) {
        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId("SMOKE_MC_BAD");
        task.setScenarioType("MC");
        task.setValuationDate(LocalDate.of(2024, 12, 31));

        ScenarioDefinition left = buildDefinition("SMOKE_MC_BAD", "SMOKE_MC_BAD", "蒙特卡洛不一致冒烟", "MC",
                "IR_SPOT", "IR_CURVE_CNY", "1Y", 365, "0", "ABSOLUTE", "ABSOLUTE");
        left.setScenarioNo(3);
        left.setIncreaseDays(1);
        left.setJumpDayNo(1);

        ScenarioDefinition right = buildDefinition("SMOKE_MC_BAD", "SMOKE_MC_BAD", "蒙特卡洛不一致冒烟", "MC",
                "IR_SPOT", "IR_CURVE_CNY", "2Y", 730, "0", "ABSOLUTE", "ABSOLUTE");
        right.setScenarioNo(3);
        right.setIncreaseDays(2);
        right.setJumpDayNo(1);

        task.setDefinitions(Arrays.asList(left, right));
        task.setCurrentMarketData(singleCurve("IR_SPOT",
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.02"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.025")));
        task.setHistoricalMarketData(buildHistory("IR_SPOT", 40,
                historySeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.015", "0.0001"),
                historySeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.021", "0.00015")));

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList("SMOKE_MC_BAD");
        request.setValuationDate(task.getValuationDate());
        request.setUser("smoke");
        request.setSource("tmp");
        request.setTasks(Arrays.asList(task));

        List<ScenarioGeneratedRecord> records = engine.generate(request);
        System.out.println("MC_INCONSISTENT_OK=" + records.isEmpty());
    }

    private static void runVar(ScenarioGenerationEngine engine) {
        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId("SMOKE_VAR");
        task.setScenarioType("VAR");
        task.setValuationDate(LocalDate.of(2024, 12, 31));
        ScenarioDefinition definition = buildDefinition("SMOKE_VAR", "SMOKE_VAR", "历史冒烟", "VAR",
                "IR_SPOT", "IR_CURVE_CNY", "1Y", 365, "0", "ABSOLUTE", "ABSOLUTE");
        definition.setScenarioNo(3);
        definition.setIncreaseDays(1);
        definition.setJumpDayNo(1);
        task.setDefinitions(Arrays.asList(definition));
        task.setCurrentMarketData(singleCurve("IR_SPOT",
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.02"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.025")));
        task.setHistoricalMarketData(buildHistory("IR_SPOT", 45,
                historySeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.015", "0.0001"),
                historySeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.021", "0.00015")));

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList("SMOKE_VAR");
        request.setValuationDate(task.getValuationDate());
        request.setUser("smoke");
        request.setSource("tmp");
        request.setTasks(Arrays.asList(task));

        List<ScenarioGeneratedRecord> records = engine.generate(request);
        System.out.println("VAR_ROWS=" + records.size());
        if (!records.isEmpty()) {
            ScenarioGeneratedRecord sample = records.get(0);
            System.out.println("VAR_SAMPLE="
                    + sample.getSubScenarioId()
                    + ",TERM=" + sample.getTermCode()
                    + ",ORI=" + sample.getOriginalValue()
                    + ",NEW=" + sample.getChangedValue()
                    + ",SHIFT=" + sample.getShiftValue());
        }
    }

    private static void runVarMixedShock(ScenarioGenerationEngine engine) {
        ScenarioTaskRequest task = new ScenarioTaskRequest();
        task.setScenarioId("SMOKE_VAR_MIXED");
        task.setScenarioType("VAR");
        task.setValuationDate(LocalDate.of(2024, 12, 31));

        ScenarioDefinition absDefinition = buildDefinition("SMOKE_VAR_MIXED", "SMOKE_VAR_MIXED", "历史混合冲击冒烟", "VAR",
                "IR_SPOT", "IR_CURVE_CNY", "1Y", 365, "0", "ABSOLUTE", "ABSOLUTE");
        absDefinition.setScenarioNo(3);
        absDefinition.setIncreaseDays(1);
        absDefinition.setJumpDayNo(1);

        ScenarioDefinition relDefinition = buildDefinition("SMOKE_VAR_MIXED", "SMOKE_VAR_MIXED", "历史混合冲击冒烟", "VAR",
                "IR_SPOT", "IR_CURVE_CNY", "2Y", 730, "0", "RELATIVE", "RELATIVE");
        relDefinition.setScenarioNo(3);
        relDefinition.setIncreaseDays(1);
        relDefinition.setJumpDayNo(1);

        task.setDefinitions(Arrays.asList(absDefinition, relDefinition));
        task.setCurrentMarketData(singleCurve("IR_SPOT",
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.02"),
                buildSeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.025")));
        task.setHistoricalMarketData(buildHistory("IR_SPOT", 45,
                historySeries("IR_SPOT", "IR_CURVE_CNY", "1Y", 365, null, "0.015", "0.0001"),
                historySeries("IR_SPOT", "IR_CURVE_CNY", "2Y", 730, null, "0.021", "0.00015")));

        ScenarioGenerationRequest request = new ScenarioGenerationRequest();
        request.setScenarioIdList("SMOKE_VAR_MIXED");
        request.setValuationDate(task.getValuationDate());
        request.setUser("smoke");
        request.setSource("tmp");
        request.setTasks(Arrays.asList(task));

        List<ScenarioGeneratedRecord> records = engine.generate(request);
        System.out.println("VAR_MIXED_ROWS=" + records.size());
        ScenarioGeneratedRecord absSample = records.stream()
                .filter(r -> "1Y".equals(r.getTermCode()))
                .findFirst()
                .orElse(null);
        ScenarioGeneratedRecord relSample = records.stream()
                .filter(r -> "2Y".equals(r.getTermCode()))
                .findFirst()
                .orElse(null);
        if (absSample != null) {
            System.out.println("VAR_MIXED_ABS="
                    + absSample.getShiftRule()
                    + ",TERM=" + absSample.getTermCode()
                    + ",SHIFT=" + absSample.getShiftValue()
                    + ",NEW=" + absSample.getChangedValue());
        }
        if (relSample != null) {
            System.out.println("VAR_MIXED_REL="
                    + relSample.getShiftRule()
                    + ",TERM=" + relSample.getTermCode()
                    + ",SHIFT=" + relSample.getShiftValue()
                    + ",NEW=" + relSample.getChangedValue());
        }
    }

    private static Map<String, List<ScenarioMarketSeries>> singleCurve(String curveType, ScenarioMarketSeries... series) {
        Map<String, List<ScenarioMarketSeries>> result = new LinkedHashMap<String, List<ScenarioMarketSeries>>();
        result.put(curveType, new ArrayList<ScenarioMarketSeries>(Arrays.asList(series)));
        return result;
    }

    private static Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> buildHistory(
            String curveType,
            int dayCount,
            ScenarioHistorySeed... seeds) {
        Map<String, Map<LocalDate, List<ScenarioMarketSeries>>> result = new LinkedHashMap<String, Map<LocalDate, List<ScenarioMarketSeries>>>();
        Map<LocalDate, List<ScenarioMarketSeries>> byDate = new LinkedHashMap<LocalDate, List<ScenarioMarketSeries>>();
        LocalDate endDate = LocalDate.of(2024, 12, 31);
        for (int i = dayCount; i >= 1; i--) {
            LocalDate date = endDate.minusDays(i);
            List<ScenarioMarketSeries> dayList = new ArrayList<ScenarioMarketSeries>();
            for (ScenarioHistorySeed seed : seeds) {
                BigDecimal value = seed.baseValue.add(seed.step.multiply(BigDecimal.valueOf(dayCount - i)));
                dayList.add(buildSeries(seed.curveType, seed.curveCode, seed.termCode, seed.termDays, seed.dimension2, value.toPlainString()));
            }
            byDate.put(date, dayList);
        }
        result.put(curveType, byDate);
        return result;
    }

    private static ScenarioHistorySeed historySeries(
            String curveType,
            String curveCode,
            String termCode,
            int termDays,
            String dimension2,
            String baseValue,
            String step) {
        return new ScenarioHistorySeed(curveType, curveCode, termCode, termDays, dimension2,
                new BigDecimal(baseValue), new BigDecimal(step));
    }

    private static ScenarioDefinition buildDefinition(
            String scenarioId,
            String scenarioCode,
            String scenarioName,
            String scenarioType,
            String curveType,
            String curveCode,
            String termCode,
            int termDays,
            String shockValue,
            String shockType,
            String shockRule) {
        ScenarioDefinition definition = new ScenarioDefinition();
        definition.setScenarioId(scenarioId);
        definition.setScenarioCode(scenarioCode);
        definition.setScenarioName(scenarioName);
        definition.setScenarioType(scenarioType);
        definition.setCurveType(curveType);
        definition.setCurveCode(curveCode);
        definition.setRiskGroupId("RG1");
        definition.setTermCode(termCode);
        definition.setTermDays(termDays);
        definition.setShockValue(new BigDecimal(shockValue));
        definition.setScenarioShiftRule(shockRule);
        return definition;
    }

    private static ScenarioMarketSeries buildSeries(
            String curveType,
            String curveCode,
            String termCode,
            int termDays,
            String dimension2,
            String value) {
        ScenarioMarketSeries series = new ScenarioMarketSeries();
        series.setCurveType(curveType);
        series.setCurveCode(curveCode);
        series.setTermCode(termCode);
        series.setTermDays(termDays);
        series.setDimension2(dimension2);
        series.setValue(new BigDecimal(value));
        return series;
    }

    private static class ScenarioHistorySeed {
        private final String curveType;
        private final String curveCode;
        private final String termCode;
        private final int termDays;
        private final String dimension2;
        private final BigDecimal baseValue;
        private final BigDecimal step;

        ScenarioHistorySeed(
                String curveType,
                String curveCode,
                String termCode,
                int termDays,
                String dimension2,
                BigDecimal baseValue,
                BigDecimal step) {
            this.curveType = curveType;
            this.curveCode = curveCode;
            this.termCode = termCode;
            this.termDays = termDays;
            this.dimension2 = dimension2;
            this.baseValue = baseValue;
            this.step = step;
        }
    }
}
