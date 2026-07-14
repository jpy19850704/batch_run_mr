package com.zcyh.mr.springboot.model;

import java.util.List;

/**
 * FRTB SBA 汇总内部请求。
 */
public class FrtbSbaSummaryRequest extends RuleSummaryRequest {
    private final boolean needDecompose;
    private final int threadCount;

    public FrtbSbaSummaryRequest(String batchId, String dataDate, List<String> ruleIds,
                                 boolean persistResult, boolean needDecompose, int threadCount) {
        super(batchId, dataDate, ruleIds, persistResult);
        this.needDecompose = needDecompose;
        this.threadCount = threadCount;
    }

    public boolean isNeedDecompose() {
        return needDecompose;
    }

    public int getThreadCount() {
        return threadCount;
    }
}
