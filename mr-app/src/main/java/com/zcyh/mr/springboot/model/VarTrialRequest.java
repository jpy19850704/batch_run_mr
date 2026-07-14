package com.zcyh.mr.springboot.model;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.var.VarMeasure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VaR 临时规则试算请求。
 */
public class VarTrialRequest {
    private final String batchId;
    private final String dataDate;
    private final List<JSONObject> ruleDefinitions;
    private final List<BigDecimal> quantiles;
    private final List<VarMeasure> measures;
    private final boolean includeDetail;
    private final String requestId;

    public VarTrialRequest(String batchId,
                           String dataDate,
                           List<JSONObject> ruleDefinitions,
                           List<BigDecimal> quantiles,
                           List<VarMeasure> measures,
                           boolean includeDetail,
                           String requestId) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.ruleDefinitions = Collections.unmodifiableList(new ArrayList<JSONObject>(ruleDefinitions));
        this.quantiles = Collections.unmodifiableList(new ArrayList<BigDecimal>(quantiles));
        this.measures = Collections.unmodifiableList(new ArrayList<VarMeasure>(measures));
        this.includeDetail = includeDetail;
        this.requestId = requestId;
    }

    public String getBatchId() { return batchId; }
    public String getDataDate() { return dataDate; }
    public List<JSONObject> getRuleDefinitions() { return ruleDefinitions; }
    public List<BigDecimal> getQuantiles() { return quantiles; }
    public List<VarMeasure> getMeasures() { return measures; }
    public boolean isIncludeDetail() { return includeDetail; }
    public String getRequestId() { return requestId; }
}
