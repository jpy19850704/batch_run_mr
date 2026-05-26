package com.zcyh.mr.var;

import java.math.BigDecimal;

/**
 * VaR 单一风险大类计量结果。
 */
public class VarRiskClassMeasureResult {
    private String riskClass;
    private int rankIn;
    private int rankOut;
    private String subScenarioIdIn;
    private String subScenarioIdOut;
    private BigDecimal pnlIn = BigDecimal.ZERO;
    private BigDecimal pnlOut = BigDecimal.ZERO;
    private BigDecimal varIn = BigDecimal.ZERO;
    private BigDecimal varOut = BigDecimal.ZERO;
    private String sortPnlField;
    private boolean includeSelectedScenarioId;
    private String selectedScenarioId;
    private BigDecimal var = BigDecimal.ZERO;
    private BigDecimal es = BigDecimal.ZERO;
    private BigDecimal componentVar = BigDecimal.ZERO;
    private BigDecimal marginalVar = BigDecimal.ZERO;
    private BigDecimal incrementalVar = BigDecimal.ZERO;

    public String getRiskClass() {
        return riskClass;
    }

    public void setRiskClass(String riskClass) {
        this.riskClass = riskClass;
    }

    public int getRankIn() {
        return rankIn;
    }

    public void setRankIn(int rankIn) {
        this.rankIn = rankIn;
    }

    public int getRankOut() {
        return rankOut;
    }

    public void setRankOut(int rankOut) {
        this.rankOut = rankOut;
    }

    public String getSubScenarioIdIn() {
        return subScenarioIdIn;
    }

    public void setSubScenarioIdIn(String subScenarioIdIn) {
        this.subScenarioIdIn = subScenarioIdIn;
    }

    public String getSubScenarioIdOut() {
        return subScenarioIdOut;
    }

    public void setSubScenarioIdOut(String subScenarioIdOut) {
        this.subScenarioIdOut = subScenarioIdOut;
    }

    public BigDecimal getPnlIn() {
        return pnlIn;
    }

    public void setPnlIn(BigDecimal pnlIn) {
        this.pnlIn = safe(pnlIn);
    }

    public BigDecimal getPnlOut() {
        return pnlOut;
    }

    public void setPnlOut(BigDecimal pnlOut) {
        this.pnlOut = safe(pnlOut);
    }

    public BigDecimal getVarIn() {
        return varIn;
    }

    public void setVarIn(BigDecimal varIn) {
        this.varIn = safe(varIn);
    }

    public BigDecimal getVarOut() {
        return varOut;
    }

    public void setVarOut(BigDecimal varOut) {
        this.varOut = safe(varOut);
    }

    public String getSortPnlField() {
        return sortPnlField;
    }

    public void setSortPnlField(String sortPnlField) {
        this.sortPnlField = sortPnlField;
    }

    public boolean isIncludeSelectedScenarioId() {
        return includeSelectedScenarioId;
    }

    public void setIncludeSelectedScenarioId(boolean includeSelectedScenarioId) {
        this.includeSelectedScenarioId = includeSelectedScenarioId;
    }

    public String getSelectedScenarioId() {
        return selectedScenarioId;
    }

    public void setSelectedScenarioId(String selectedScenarioId) {
        this.selectedScenarioId = selectedScenarioId;
    }

    public BigDecimal getVar() {
        return var;
    }

    public void setVar(BigDecimal var) {
        this.var = safe(var);
    }

    public BigDecimal getEs() {
        return es;
    }

    public void setEs(BigDecimal es) {
        this.es = safe(es);
    }

    public BigDecimal getComponentVar() {
        return componentVar;
    }

    public void setComponentVar(BigDecimal componentVar) {
        this.componentVar = safe(componentVar);
    }

    public BigDecimal getMarginalVar() {
        return marginalVar;
    }

    public void setMarginalVar(BigDecimal marginalVar) {
        this.marginalVar = safe(marginalVar);
    }

    public BigDecimal getIncrementalVar() {
        return incrementalVar;
    }

    public void setIncrementalVar(BigDecimal incrementalVar) {
        this.incrementalVar = safe(incrementalVar);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
