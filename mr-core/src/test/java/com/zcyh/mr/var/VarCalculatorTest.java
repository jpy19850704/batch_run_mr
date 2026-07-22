package com.zcyh.mr.var;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VarCalculatorTest {

    @Test
    public void calculate_when250ScenariosAnd99PercentAverage_shouldInterpolateSecondAndThirdWorst() {
        List<VarScenarioPnl> rows = new ArrayList<VarScenarioPnl>();
        for (int i = 1; i <= 250; i++) {
            rows.add(new VarScenarioPnl("S", "SS" + i, "N" + i, BigDecimal.valueOf(-i)));
        }

        VarQuantileResult result = new VarCalculator().calculate(
                rows,
                Collections.singletonList(new BigDecimal("0.99")),
                VarPickMethod.AVERAGE).get(0);

        Assertions.assertEquals(2, result.getRankOut());
        Assertions.assertEquals(3, result.getRankIn());
        Assertions.assertEquals(new BigDecimal("-249"), result.getPnlOut());
        Assertions.assertEquals(new BigDecimal("-248"), result.getPnlIn());
        Assertions.assertEquals(new BigDecimal("-248.5000000000"), result.getSelectedPnl());
    }

    @Test
    public void calculate_when251ScenariosAnd99PercentAverage_shouldUseLinearInterpolation() {
        List<VarScenarioPnl> rows = new ArrayList<VarScenarioPnl>();
        for (int i = 1; i <= 251; i++) {
            rows.add(new VarScenarioPnl("S", "SS" + i, "N" + i, BigDecimal.valueOf(-i)));
        }

        VarQuantileResult result = new VarCalculator().calculate(
                rows,
                Collections.singletonList(new BigDecimal("0.99")),
                VarPickMethod.AVERAGE).get(0);

        Assertions.assertEquals(2, result.getRankOut());
        Assertions.assertEquals(3, result.getRankIn());
        Assertions.assertEquals(new BigDecimal("0.51"), result.getInterpolationWeightIn());
        Assertions.assertEquals(new BigDecimal("-249.4900000000"), result.getSelectedPnl());
    }

    @Test
    public void calculateEsByOut_when250ScenariosAnd99Percent_shouldAverageWorstTwo() {
        List<VarScenarioPnl> rows = new ArrayList<VarScenarioPnl>();
        for (int i = 1; i <= 250; i++) {
            rows.add(new VarScenarioPnl("S", "SS" + i, "N" + i, BigDecimal.valueOf(-i)));
        }

        BigDecimal es = new VarCalculator().calculateEsByOut(rows, new BigDecimal("0.99"));

        Assertions.assertEquals(new BigDecimal("-249.5000000000"), es);
    }
}
