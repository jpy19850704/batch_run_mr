package com.zcyh.mr.springboot.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规则汇总内部请求。
 */
public class RuleSummaryRequest {
    private final String batchId;
    private final String dataDate;
    private final List<String> ruleIds;
    private final boolean persistResult;
    private final SummaryCleanupMode cleanupMode;

    public RuleSummaryRequest(String batchId,
                              String dataDate,
                              List<String> ruleIds,
                              boolean persistResult,
                              SummaryCleanupMode cleanupMode) {
        if (cleanupMode == null) {
            throw new IllegalArgumentException("cleanupMode 不能为空");
        }
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.ruleIds = Collections.unmodifiableList(new ArrayList<String>(ruleIds));
        this.persistResult = persistResult;
        this.cleanupMode = cleanupMode;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getDataDate() {
        return dataDate;
    }

    public List<String> getRuleIds() {
        return ruleIds;
    }

    public boolean isPersistResult() {
        return persistResult;
    }

    public SummaryCleanupMode getCleanupMode() {
        return cleanupMode;
    }
}
