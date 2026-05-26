package com.zcyh.mr.var;

import java.math.BigDecimal;

/**
 * VaR 情景损益样本。
 */
public class VarScenarioPnl {
    private final String scenarioId;
    private final String subScenarioId;
    private final String scenarioName;
    private final BigDecimal pnl;

    public VarScenarioPnl(String scenarioId, String subScenarioId, String scenarioName, BigDecimal pnl) {
        this.scenarioId = scenarioId;
        this.subScenarioId = subScenarioId;
        this.scenarioName = scenarioName;
        this.pnl = pnl;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getSubScenarioId() {
        return subScenarioId;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public BigDecimal getPnl() {
        return pnl;
    }
}

