package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.engine.ScenarioEngineAdapter;
import org.springframework.stereotype.Component;

/**
 * 批量情景生成任务。
 */
@Component
public class BatchScenarioGenerateTask implements BatchRunTask {
    private final ScenarioEngineAdapter scenarioEngineAdapter;

    public BatchScenarioGenerateTask(ScenarioEngineAdapter scenarioEngineAdapter) {
        this.scenarioEngineAdapter = scenarioEngineAdapter;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isScenarioMode()) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("mode", "service");
        payload.put("scenario_id_list", context.getScenarioIdList());
        payload.put("data_date", context.getDataDate());
        payload.put("user", context.getUser());
        payload.put("batch_id", context.getBatchId());
        if (context.getRequest().getPersistScenario() != null) {
            payload.put("persist_scenario", context.getRequest().getPersistScenario());
        }

        String raw = scenarioEngineAdapter.calculate(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        JSONArray data = JSON.parseArray(raw);
        if (data == null || data.isEmpty()) {
            throw new IllegalStateException("情景生成结果为空，batchId=" + context.getBatchId()
                    + ", scenario_id_list=" + context.getScenarioIdList());
        }
        context.setScenarioJson(raw);
        context.setScenarioData(data);
    }
}
