package com.zcyh.mr.springboot.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FRTB 临时规则试算请求。
 */
public class FrtbRuleTrialRequest {
    private final String batchId;
    private final String dataDate;
    private final List<AggregationRule> ruleDefinitions;
    private final boolean needDecompose;
    private final int threadCount;

    public FrtbRuleTrialRequest(String batchId,
                                String dataDate,
                                List<AggregationRule> ruleDefinitions,
                                boolean needDecompose,
                                int threadCount) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.ruleDefinitions = Collections.unmodifiableList(
                new ArrayList<AggregationRule>(ruleDefinitions));
        this.needDecompose = needDecompose;
        this.threadCount = threadCount;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getDataDate() {
        return dataDate;
    }

    public List<AggregationRule> getRuleDefinitions() {
        return ruleDefinitions;
    }

    public boolean isNeedDecompose() {
        return needDecompose;
    }

    public int getThreadCount() {
        return threadCount;
    }
}
