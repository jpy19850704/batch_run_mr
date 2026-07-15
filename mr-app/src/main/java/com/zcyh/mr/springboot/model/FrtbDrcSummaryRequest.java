package com.zcyh.mr.springboot.model;

import java.util.List;

/**
 * FRTB DRC 汇总内部请求。
 */
public class FrtbDrcSummaryRequest extends RuleSummaryRequest {
    private final String requestId;
    private final String jobId;

    public FrtbDrcSummaryRequest(String batchId, String dataDate, List<String> ruleIds,
                                 boolean persistResult, SummaryCleanupMode cleanupMode,
                                 String requestId, String jobId) {
        super(batchId, dataDate, ruleIds, persistResult, cleanupMode);
        this.requestId = requestId;
        this.jobId = jobId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getJobId() {
        return jobId;
    }
}
