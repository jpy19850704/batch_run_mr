package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.pojo.FRTBClassResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * FRTB 汇总任务。
 * 包含 SBA 与 DRC 两部分。
 */
@Component
public class BatchFrtbSummaryTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchFrtbSummaryTask.class);
    private static final String DEFAULT_RULE_ID = "BATCH_FRTB_DEFAULT";

    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final FrtbSbaResultPersistService frtbSbaResultPersistService;
    private final FrtbDrcDbRunnerService frtbDrcDbRunnerService;
    private final FrtbDrcResultPersistService frtbDrcResultPersistService;
    private final FrtbAggregator frtbAggregator;

    public BatchFrtbSummaryTask(
            FrtbSbaDbRunnerService frtbSbaDbRunnerService,
            FrtbSbaResultPersistService frtbSbaResultPersistService,
            FrtbDrcDbRunnerService frtbDrcDbRunnerService,
            FrtbDrcResultPersistService frtbDrcResultPersistService,
            FrtbAggregator frtbAggregator) {
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.frtbSbaResultPersistService = frtbSbaResultPersistService;
        this.frtbDrcDbRunnerService = frtbDrcDbRunnerService;
        this.frtbDrcResultPersistService = frtbDrcResultPersistService;
        this.frtbAggregator = frtbAggregator;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (context.isWhatifMode()) {
            return;
        }
        context.setSbaSummary(runSbaSummary(context.getBatchId(), context.getDataDate()));
        context.setDrcSummary(runDrcSummary(context.getBatchId(), context.getDataDate()));
    }

    @SuppressWarnings("unchecked")
    private Object runSbaSummary(String batchId, String dataDate) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);
        payload.put("rule_id", DEFAULT_RULE_ID);
        payload.put("need_decompose", true);

        String raw = frtbSbaDbRunnerService.calculateByRule(payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        Object parsed = JSON.parse(raw);

        try {
            Map<String, Map<String, Object>> batchResult = JSON.parseObject(raw, Map.class);
            if (batchResult != null && !batchResult.isEmpty()) {
                frtbSbaResultPersistService.deleteByBatch(batchId);
                for (Map.Entry<String, Map<String, Object>> entry : batchResult.entrySet()) {
                    String[] parts = entry.getKey().split("\\|", 2);
                    String treeId = parts.length > 0 ? parts[0] : null;
                    String groupValue = parts.length > 1 ? parts[1] : null;
                    String groupType = inferGroupType(groupValue);
                    Map<String, List<?>> pojoResult = frtbAggregator.buildResults(
                            entry.getValue(), treeId, groupType, groupValue);
                    List<?> classResults = pojoResult.get("classResults");
                    if (classResults != null) {
                        frtbSbaResultPersistService.persist(
                                (List<FRTBClassResult>) classResults, batchId, dataDate, DEFAULT_RULE_ID);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("FRTB SBA 结果落库异常，不影响批量返回: batchId={}, error={}", batchId, ex.getMessage());
        }

        return parsed;
    }

    private Object runDrcSummary(String batchId, String dataDate) {
        JSONObject payload = new JSONObject();
        payload.put("batch_id", batchId);
        payload.put("data_date", dataDate);

        String raw = frtbDrcDbRunnerService.calculateByBatch(
                payload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain));
        Object parsed = JSON.parse(raw);

        try {
            JSONObject resultJson = JSON.parseObject(raw);
            frtbDrcResultPersistService.persist(batchId, null, batchId, dataDate, resultJson);
        } catch (Exception ex) {
            log.warn("DRC 结果落库异常，不影响批量返回: batchId={}, error={}", batchId, ex.getMessage());
        }

        return parsed;
    }

    private static String inferGroupType(String groupValue) {
        if (groupValue == null || "TOTAL".equalsIgnoreCase(groupValue)
                || "__EMPTY_GROUP__".equals(groupValue)) {
            return "TOTAL";
        }
        return "PORTFOLIO";
    }
}
