package com.zcyh.mr.var;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * VaR 单一维度计量结果。
 */
public class VarDimensionMeasureResult {
    private final String groupType;
    private final String groupValue;
    private final BigDecimal baseValuationCny;
    private final List<VarRiskClassMeasureResult> riskClassResults;

    public VarDimensionMeasureResult(String groupType,
                                     String groupValue,
                                     BigDecimal baseValuationCny,
                                     List<VarRiskClassMeasureResult> riskClassResults) {
        this.groupType = groupType;
        this.groupValue = groupValue;
        this.baseValuationCny = baseValuationCny == null ? BigDecimal.ZERO : baseValuationCny;
        this.riskClassResults = riskClassResults == null
                ? new ArrayList<VarRiskClassMeasureResult>()
                : new ArrayList<VarRiskClassMeasureResult>(riskClassResults);
    }

    public String getGroupType() {
        return groupType;
    }

    public String getGroupValue() {
        return groupValue;
    }

    public BigDecimal getBaseValuationCny() {
        return baseValuationCny;
    }

    public List<VarRiskClassMeasureResult> getRiskClassResults() {
        return Collections.unmodifiableList(riskClassResults);
    }
}
