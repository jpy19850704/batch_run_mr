package com.zcyh.mr.springboot.output.db;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * MR_CALC 单任务落库上下文。
 */
final class CalcPersistContext {
    String requestId;
    String jobId;
    String batchId;
    Long seqNo;
    LocalDate dataDate;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    JSONObject payload;
    JSONObject resultData;
    JSONObject tradeDimension;
    JSONObject tradeRrao;

    JSONArray effectiveBaseTrades;
    JSONArray scenarioResults;
    JSONArray imaModellableScenarioResults;

    Map<String, JSONObject> inputTradeIndex;
    Map<String, JSONObject> baseTradeIndex;
}
