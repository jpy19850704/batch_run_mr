package com.zcyh.mr.product.basic.willow;

public class WillowStateGrid {
    private final double[][] values;

    public WillowStateGrid(double[][] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Willow状态格点不能为空");
        }
        for (double[] row : values) {
            if (row == null || row.length != WillowNodeDefinition.NODE_COUNT) {
                throw new IllegalArgumentException("Willow状态格点节点数必须为" + WillowNodeDefinition.NODE_COUNT);
            }
        }
        this.values = values;
    }

    public int timeCount() {
        return values.length;
    }

    public double value(int timeIndex, int nodeIndex) {
        WillowNodeDefinition.validateNodeIndex(nodeIndex);
        if (timeIndex < 0 || timeIndex >= values.length) {
            throw new IllegalArgumentException("Willow时间索引超出范围: " + timeIndex);
        }
        return values[timeIndex][nodeIndex];
    }

    public double[] timeSlice(int timeIndex) {
        if (timeIndex < 0 || timeIndex >= values.length) {
            throw new IllegalArgumentException("Willow时间索引超出范围: " + timeIndex);
        }
        return values[timeIndex].clone();
    }
}
