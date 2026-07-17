package com.zcyh.mr.springboot.batch;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.springboot.scenario.ScenarioExecutionAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 批量情景生成任务。
 */
@Component
public class BatchScenarioGenerateTask implements BatchRunTask {
    private final ScenarioExecutionAdapter scenarioExecutionAdapter;

    public BatchScenarioGenerateTask(ScenarioExecutionAdapter scenarioExecutionAdapter) {
        this.scenarioExecutionAdapter = scenarioExecutionAdapter;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isScenarioMode()) {
            return;
        }
        if (context.isLocalRerun()) {
            return;
        }
        String mergedScenarioIdList = mergeScenarioIdLists(
                context.getRegularScenarioIdList(),
                context.getVarScenarioIdList(),
                context.getNormalFullScenarioIdList(),
                context.getNormalReducedScenarioIdList(),
                context.getStressReducedScenarioIdList(),
                context.getNmrfScenarioIdList());
        if (mergedScenarioIdList == null) {
            throw new IllegalStateException("SCENARIO 模式缺少 regularScenarioIdList 或 varScenarioIdList");
        }
        JSONObject payload = new JSONObject();
        payload.put("mode", "service");
        payload.put("scenario_id_list", mergedScenarioIdList);
        payload.put("data_date", context.getDataDate());
        payload.put("user", context.getUser());
        payload.put("batch_id", context.getBatchId());
        if (context.getRequest().getPersistScenario() != null) {
            payload.put("persist_scenario", context.getRequest().getPersistScenario());
        }

        List<ScenarioGeneratedRecord> records = scenarioExecutionAdapter.generateRecords(payload);
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("情景生成结果为空，batchId=" + context.getBatchId()
                    + ", scenario_id_list=" + mergedScenarioIdList);
        }
        context.setScenarioRecords(records);
    }

    static String mergeScenarioIdLists(String... scenarioIdLists) {
        Set<String> merged = new LinkedHashSet<String>();
        if (scenarioIdLists != null) {
            for (String scenarioIdList : scenarioIdLists) {
                String safe = trimToNull(scenarioIdList);
                if (safe == null) {
                    continue;
                }
                for (String part : safe.split(",")) {
                    String scenarioId = trimToNull(part);
                    if (scenarioId != null) {
                        merged.add(scenarioId);
                    }
                }
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        return String.join(",", merged);
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
