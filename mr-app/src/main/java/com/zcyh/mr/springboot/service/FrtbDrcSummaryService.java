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
        String batchId = readRequiredString(request, "batch_id", "batchId");
        String dataDate = readRequiredString(request, "data_date", "dataDate");
        String requestId = readString(request, "request_id", "requestId");
        String jobId = readString(request, "job_id", "jobId");
        boolean persistResult = readBoolean(request, true, "persist_result", "persistResult");

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

    private static boolean readBoolean(JSONObject request, boolean defaultValue, String... keys) {
        for (String key : keys) {
            if (key == null || !request.containsKey(key)) {
                continue;
            }
            Boolean value = request.getBoolean(key);
            if (value != null) {
                return value;
            }
        }
        return defaultValue;
    }

    private static String readRequiredString(JSONObject request, String... keys) {
        String value = readString(request, keys);
        if (value == null) {
            throw new IllegalArgumentException("参数缺失: " + keys[0]);
        }
        return value;
    }

    private static String readString(JSONObject request, String... keys) {
        if (request == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = trimToNull(request.getString(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
