package com.zcyh.mr.var;

/**
 * VaR 情景主键。
 */
public class VarScenarioKey {
    private final String scenarioId;
    private final String subScenarioId;
    private final String scenarioName;

    public VarScenarioKey(String scenarioId, String subScenarioId, String scenarioName) {
        this.scenarioId = scenarioId;
        this.subScenarioId = subScenarioId;
        this.scenarioName = scenarioName;
    }

    public static VarScenarioKey fromScenario(VarScenarioPnl scenario) {
        if (scenario == null) {
            return new VarScenarioKey(null, null, null);
        }
        return new VarScenarioKey(scenario.getScenarioId(), scenario.getSubScenarioId(), scenario.getScenarioName());
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VarScenarioKey)) {
            return false;
        }
        VarScenarioKey that = (VarScenarioKey) o;
        return nullSafe(scenarioId).equals(nullSafe(that.scenarioId))
                && nullSafe(subScenarioId).equals(nullSafe(that.subScenarioId))
                && nullSafe(scenarioName).equals(nullSafe(that.scenarioName));
    }

    @Override
    public int hashCode() {
        int result = nullSafe(scenarioId).hashCode();
        result = 31 * result + nullSafe(subScenarioId).hashCode();
        result = 31 * result + nullSafe(scenarioName).hashCode();
        return result;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
