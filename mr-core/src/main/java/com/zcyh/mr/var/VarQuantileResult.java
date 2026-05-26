package com.zcyh.mr.var;

import java.math.BigDecimal;

/**
 * VaR 单一分位点计算结果。
 */
public class VarQuantileResult {
    private final BigDecimal quantile;
    private final int rankIn;
    private final int rankOut;
    private final VarScenarioPnl inScenario;
    private final VarScenarioPnl outScenario;
    private final BigDecimal pnlIn;
    private final BigDecimal varIn;
    private final BigDecimal pnlOut;
    private final BigDecimal varOut;
    private final VarPickMethod selectedMethod;
    private final VarScenarioPnl selectedScenario;
    private final BigDecimal selectedPnl;
    private final BigDecimal selectedVar;
    private final boolean singleSample;

    public VarQuantileResult(BigDecimal quantile,
                             int rankIn,
                             int rankOut,
                             VarScenarioPnl inScenario,
                             VarScenarioPnl outScenario,
                             BigDecimal pnlIn,
                             BigDecimal varIn,
                             BigDecimal pnlOut,
                             BigDecimal varOut,
                             VarPickMethod selectedMethod,
                             VarScenarioPnl selectedScenario,
                             BigDecimal selectedPnl,
                             BigDecimal selectedVar,
                             boolean singleSample) {
        this.quantile = quantile;
        this.rankIn = rankIn;
        this.rankOut = rankOut;
        this.inScenario = inScenario;
        this.outScenario = outScenario;
        this.pnlIn = pnlIn;
        this.varIn = varIn;
        this.pnlOut = pnlOut;
        this.varOut = varOut;
        this.selectedMethod = selectedMethod;
        this.selectedScenario = selectedScenario;
        this.selectedPnl = selectedPnl;
        this.selectedVar = selectedVar;
        this.singleSample = singleSample;
    }

    public BigDecimal getQuantile() {
        return quantile;
    }

    public int getRankIn() {
        return rankIn;
    }

    public int getRankOut() {
        return rankOut;
    }

    public VarScenarioPnl getInScenario() {
        return inScenario;
    }

    public VarScenarioPnl getOutScenario() {
        return outScenario;
    }

    public BigDecimal getPnlIn() {
        return pnlIn;
    }

    public BigDecimal getVarIn() {
        return varIn;
    }

    public BigDecimal getPnlOut() {
        return pnlOut;
    }

    public BigDecimal getVarOut() {
        return varOut;
    }

    public VarPickMethod getSelectedMethod() {
        return selectedMethod;
    }

    public VarScenarioPnl getSelectedScenario() {
        return selectedScenario;
    }

    public BigDecimal getSelectedPnl() {
        return selectedPnl;
    }

    public BigDecimal getSelectedVar() {
        return selectedVar;
    }

    public boolean isSingleSample() {
        return singleSample;
    }
}

