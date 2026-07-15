package com.zcyh.mr.springboot.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VaR 汇总内部请求。
 */
public class VarSummaryRequest extends RuleSummaryRequest {
    private final List<BigDecimal> quantiles;

    public VarSummaryRequest(String batchId, String dataDate, List<String> ruleIds,
                             boolean persistResult, SummaryCleanupMode cleanupMode,
                             List<BigDecimal> quantiles) {
        super(batchId, dataDate, ruleIds, persistResult, cleanupMode);
        this.quantiles = Collections.unmodifiableList(new ArrayList<BigDecimal>(quantiles));
    }

    public List<BigDecimal> getQuantiles() {
        return quantiles;
    }
}
