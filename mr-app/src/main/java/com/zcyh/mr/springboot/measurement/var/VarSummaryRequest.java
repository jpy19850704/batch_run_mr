package com.zcyh.mr.springboot.measurement.var;

import com.zcyh.mr.springboot.measurement.SummaryCleanupMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * VaR 汇总内部请求。
 */
public class VarSummaryRequest {
    private final String batchId;
    private final String dataDate;
    private final List<VarCalculation> calculations;
    private final boolean persistResult;
    private final SummaryCleanupMode cleanupMode;

    public VarSummaryRequest(String batchId,
                             String dataDate,
                             List<VarCalculation> calculations,
                             boolean persistResult,
                             SummaryCleanupMode cleanupMode) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.calculations = Collections.unmodifiableList(new ArrayList<VarCalculation>(calculations));
        this.persistResult = persistResult;
        this.cleanupMode = cleanupMode;
    }

    public String getBatchId() {
        return batchId;
    }

    public String getDataDate() {
        return dataDate;
    }

    public List<VarCalculation> getCalculations() {
        return calculations;
    }

    public List<String> getRuleIds() {
        LinkedHashSet<String> ruleIds = new LinkedHashSet<String>();
        for (VarCalculation calculation : calculations) {
            ruleIds.add(calculation.getRuleId());
        }
        return new ArrayList<String>(ruleIds);
    }

    public boolean isPersistResult() {
        return persistResult;
    }

    public SummaryCleanupMode getCleanupMode() {
        return cleanupMode;
    }
}
