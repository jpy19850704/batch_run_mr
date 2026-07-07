package com.zcyh.mr.springboot.out.db;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * MR_CALC 单任务落库上下文。
 */
final class CalcPersistContext {
    String requestId;
    String jobId;
    String batchId;
    Long seqNo;
    String dataDate;
    String createdAt;
    String updatedAt;

    JSONObject payload;
    JSONObject resultData;
    JSONObject tradeDimension;
    JSONObject tradeRrao;

    JSONArray inputMarketData;
    JSONArray generatedMarketData;
    JSONArray effectiveBaseTrades;
    JSONArray scenarioResults;
    JSONArray imaModellableScenarioResults;

    Map<String, JSONObject> inputTradeIndex;
    Map<String, JSONObject> baseTradeIndex;
}
