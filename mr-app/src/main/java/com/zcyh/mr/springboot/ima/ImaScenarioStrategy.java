package com.zcyh.mr.springboot.ima;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.scenario.strategy.HistoricalScenarioStrategy;
import com.zcyh.mr.scenario.strategy.ScenarioStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * IMA 情景策略。
 */
public class ImaScenarioStrategy implements ScenarioStrategy {
    private final HistoricalScenarioStrategy historicalScenarioStrategy;
    private final ImaRfetScenarioAnnotator rfetScenarioAnnotator;

    public ImaScenarioStrategy(com.zcyh.mr.core.Calendar holidayCalendar,
                               ImaRfetScenarioAnnotator rfetScenarioAnnotator) {
        this.historicalScenarioStrategy = new HistoricalScenarioStrategy(holidayCalendar);
        this.rfetScenarioAnnotator = rfetScenarioAnnotator;
    }

    @Override
    public List<ScenarioGeneratedRecord> generate(ScenarioTaskRequest task, String user) {
        List<ScenarioGeneratedRecord> records = historicalScenarioStrategy.generate(task, user);
        rfetScenarioAnnotator.annotate(task == null ? null : task.getImaRfetResults(), records);
        if (isImaNmrf(task == null ? null : task.getScenarioType())) {
            return ImaNmrfScenarioTransformService.transform(filterNmrfRecords(records));
        }
        return filterModellableRecords(task == null ? null : task.getScenarioType(), records);
    }

    private static boolean isImaNmrf(String scenarioType) {
        String safe = scenarioType == null ? null : scenarioType.trim();
        return safe != null && "IMA_NMRF".equals(safe.toUpperCase(Locale.ROOT));
    }

    private static List<ScenarioGeneratedRecord> filterModellableRecords(
            String scenarioType,
            List<ScenarioGeneratedRecord> records) {
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        if (records != null) {
            for (ScenarioGeneratedRecord record : records) {
                if (record == null) {
                    continue;
                }
                Boolean modellable = record.getRfetModellable();
                if (modellable == null) {
                    throw new IllegalStateException("IMA 可建模情景记录缺少 RFET 可建模标记，scenario_id="
                            + record.getScenarioId() + ", sub_scenario_id=" + record.getSubScenarioId());
                }
                if (!Boolean.TRUE.equals(modellable)) {
                    continue;
                }
                if (Boolean.TRUE.equals(record.getReducedSetFlag())
                        && !Boolean.TRUE.equals(record.getRfetReducedSet())) {
                    continue;
                }
                result.add(record);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("IMA 可建模情景过滤后为空，scenarioType=" + scenarioType);
        }
        return result;
    }

    private static List<ScenarioGeneratedRecord> filterNmrfRecords(List<ScenarioGeneratedRecord> records) {
        List<ScenarioGeneratedRecord> result = new ArrayList<ScenarioGeneratedRecord>();
        if (records != null) {
            for (ScenarioGeneratedRecord record : records) {
                if (record == null) {
                    continue;
                }
                Boolean modellable = record.getRfetModellable();
                if (modellable == null) {
                    throw new IllegalStateException("IMA_NMRF 情景记录缺少 RFET 可建模标记，scenario_id="
                            + record.getScenarioId() + ", sub_scenario_id=" + record.getSubScenarioId());
                }
                if (Boolean.FALSE.equals(modellable)) {
                    result.add(record);
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("IMA_NMRF 情景过滤后无不可建模风险因子记录");
        }
        return result;
    }
}
