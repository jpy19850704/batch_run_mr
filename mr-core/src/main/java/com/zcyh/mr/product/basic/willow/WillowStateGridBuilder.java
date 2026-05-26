package com.zcyh.mr.product.basic.willow;

public final class WillowStateGridBuilder {
    private WillowStateGridBuilder() {
    }

    public static WillowStateGrid buildNormal(double[] center, double[] sqrtVariance) {
        validateInputs(center, sqrtVariance);
        double[][] grid = new double[center.length][WillowNodeDefinition.NODE_COUNT];
        for (int t = 0; t < center.length; t++) {
            for (int i = 0; i < WillowNodeDefinition.NODE_COUNT; i++) {
                grid[t][i] = center[t] + sqrtVariance[t] * WillowNodeDefinition.zValue(i);
            }
        }
        return new WillowStateGrid(grid);
    }

    public static WillowStateGrid buildLogNormal(double[] forward, double[] variance) {
        validateInputs(forward, variance);
        double[][] grid = new double[forward.length][WillowNodeDefinition.NODE_COUNT];
        for (int t = 0; t < forward.length; t++) {
            if (forward[t] <= 0.0) {
                throw new IllegalArgumentException("LOG_NORMAL的forward必须大于0: index=" + t);
            }
            if (variance[t] < 0.0) {
                throw new IllegalArgumentException("LOG_NORMAL的variance不能为负: index=" + t);
            }
            double sqrtVariance = Math.sqrt(variance[t]);
            for (int i = 0; i < WillowNodeDefinition.NODE_COUNT; i++) {
                grid[t][i] = forward[t] * Math.exp(-0.5 * variance[t]
                        + sqrtVariance * WillowNodeDefinition.zValue(i));
            }
        }
        return new WillowStateGrid(grid);
    }

    public static WillowStateGrid buildIrNormal(double[] alpha, double[] sqrtVariance) {
        return buildNormal(alpha, sqrtVariance);
    }

    public static WillowStateGrid buildIrLogNormal(double[] alpha, double[] sqrtVariance) {
        validateInputs(alpha, sqrtVariance);
        double[][] grid = new double[alpha.length][WillowNodeDefinition.NODE_COUNT];
        for (int t = 0; t < alpha.length; t++) {
            for (int i = 0; i < WillowNodeDefinition.NODE_COUNT; i++) {
                grid[t][i] = Math.exp(alpha[t] + sqrtVariance[t] * WillowNodeDefinition.zValue(i));
            }
        }
        return new WillowStateGrid(grid);
    }

    private static void validateInputs(double[] first, double[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) {
            throw new IllegalArgumentException("Willow状态格点输入数组长度不一致");
        }
        for (int i = 0; i < first.length; i++) {
            if (!Double.isFinite(first[i]) || !Double.isFinite(second[i])) {
                throw new IllegalArgumentException("Willow状态格点输入包含非法数值: index=" + i);
            }
        }
    }
}
