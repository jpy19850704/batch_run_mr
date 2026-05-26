package com.zcyh.mr.product.basic.willow;

import java.util.List;

public final class WillowBackwardPricer {
    private WillowBackwardPricer() {
    }

    public static double step(double cashflow,
            double[] nextValues,
            double nodeDiscountFactor,
            List<WillowTransition> transitions) {
        if (nextValues == null || nextValues.length != WillowNodeDefinition.NODE_COUNT) {
            throw new IllegalArgumentException("下一期价值数组节点数必须为" + WillowNodeDefinition.NODE_COUNT);
        }
        if (nodeDiscountFactor < 0.0 || !Double.isFinite(nodeDiscountFactor)) {
            throw new IllegalArgumentException("节点折现因子非法: " + nodeDiscountFactor);
        }
        if (transitions == null || transitions.isEmpty()) {
            throw new IllegalArgumentException("节点转移概率不能为空");
        }
        double expectedValue = 0.0;
        for (WillowTransition transition : transitions) {
            expectedValue += transition.probability * nextValues[transition.destNode];
        }
        return cashflow + nodeDiscountFactor * expectedValue;
    }
}
