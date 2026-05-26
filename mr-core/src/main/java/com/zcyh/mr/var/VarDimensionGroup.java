package com.zcyh.mr.var;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VaR 单一维度分组下的情景损益集合。
 */
public class VarDimensionGroup {
    private final String groupType;
    private final String groupValue;
    private final Map<VarScenarioKey, VarScenarioPnlAggregate> scenarioPnls =
            new LinkedHashMap<VarScenarioKey, VarScenarioPnlAggregate>();
    private BigDecimal baseValuationCny;

    public VarDimensionGroup(String groupType, String groupValue) {
        this.groupType = groupType;
        this.groupValue = groupValue;
    }

    public void accumulate(VarScenarioKey scenarioKey,
                           BigDecimal allPnl,
                           BigDecimal irPnl,
                           BigDecimal fxPnl,
                           BigDecimal eqPnl,
                           BigDecimal commPnl,
                           BigDecimal baseValuationCny) {
        VarScenarioPnlAggregate aggregate = scenarioPnls.get(scenarioKey);
        if (aggregate == null) {
            aggregate = new VarScenarioPnlAggregate();
            scenarioPnls.put(scenarioKey, aggregate);
        }
        aggregate.add(allPnl, irPnl, fxPnl, eqPnl, commPnl);
        if (this.baseValuationCny == null && baseValuationCny != null) {
            this.baseValuationCny = baseValuationCny;
        }
    }

    public String getGroupType() {
        return groupType;
    }

    public String getGroupValue() {
        return groupValue;
    }

    public Map<VarScenarioKey, VarScenarioPnlAggregate> getScenarioPnls() {
        return scenarioPnls;
    }

    public BigDecimal getBaseValuationCny() {
        return baseValuationCny;
    }
}
