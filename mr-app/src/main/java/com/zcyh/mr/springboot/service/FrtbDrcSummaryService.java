package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;

/**
 * FRTB DRC 汇总服务。
 * 从批次结果明细生成 DRC 汇总，并按需要写入结果表。
 */
@Service
public class FrtbDrcSummaryService {
    private final FrtbDrcDbRunnerService frtbDrcDbRunnerService;
    private final FrtbDrcResultPersistService frtbDrcResultPersistService;

    public FrtbDrcSummaryService(FrtbDrcDbRunnerService frtbDrcDbRunnerService,
                                 FrtbDrcResultPersistService frtbDrcResultPersistService) {
        this.frtbDrcDbRunnerService = frtbDrcDbRunnerService;
        this.frtbDrcResultPersistService = frtbDrcResultPersistService;
    }

    public JSONObject summarize(JSONObject request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        String batchId = readRequiredString(request, "batch_id");
        String dataDate = readRequiredString(request, "data_date");
        String requestId = readString(request, "request_id");
        String jobId = readString(request, "job_id");
        boolean persistResult = readBoolean(request, true, "persist_result");

        JSONObject summary = frtbDrcDbRunnerService.calculateByBatch(batchId, dataDate);
        if (persistResult) {
            frtbDrcResultPersistService.persist(requestId, jobId, batchId, dataDate, summary);
        }

        JSONObject response = new JSONObject();
        response.put("batch_id", batchId);
        response.put("data_date", dataDate);
        response.put("summary", summary);
        return response;
    }

    private static boolean readBoolean(JSONObject request, boolean defaultValue, String key) {
        if (key != null && request.containsKey(key)) {
            Boolean value = request.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static String readRequiredString(JSONObject request, String key) {
        String value = readString(request, key);
        if (value == null) {
            throw new IllegalArgumentException("参数缺失: " + key);
        }
        return value;
    }

    private static String readString(JSONObject request, String key) {
        if (request == null || key == null) {
            return null;
        }
        return trimToNull(request.getString(key));
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
