package com.zcyh.mr.springboot.measurement.var;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VaR 临时规则试算请求。
 */
public class VarTrialRequest {
    private final String batchId;
    private final String dataDate;
    private final List<VarCalculation> calculations;
    private final boolean includeDetail;
    private final String requestId;

    public VarTrialRequest(String batchId,
                           String dataDate,
                           List<VarCalculation> calculations,
                           boolean includeDetail,
                           String requestId) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.calculations = Collections.unmodifiableList(new ArrayList<VarCalculation>(calculations));
        this.includeDetail = includeDetail;
        this.requestId = requestId;
    }

    public String getBatchId() { return batchId; }
    public String getDataDate() { return dataDate; }
    public List<VarCalculation> getCalculations() { return calculations; }
    public boolean isIncludeDetail() { return includeDetail; }
    public String getRequestId() { return requestId; }
}
