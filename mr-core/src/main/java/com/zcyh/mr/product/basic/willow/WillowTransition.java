package com.zcyh.mr.product.basic.willow;

public class WillowTransition {
    public final int timeIndex;
    public final int originNode;
    public final int destNode;
    public final int nonZeroProbCount;
    public final double probability;

    public WillowTransition(int timeIndex, int originNode, int destNode, int nonZeroProbCount, double probability) {
        if (timeIndex < 0) {
            throw new IllegalArgumentException("TIME_INDEX不能为负数: " + timeIndex);
        }
        WillowNodeDefinition.validateNodeIndex(originNode);
        WillowNodeDefinition.validateNodeIndex(destNode);
        if (nonZeroProbCount <= 0) {
            throw new IllegalArgumentException("非零转移数量必须大于0");
        }
        if (probability < 0.0 || !Double.isFinite(probability)) {
            throw new IllegalArgumentException("转移概率非法: " + probability);
        }
        this.timeIndex = timeIndex;
        this.originNode = originNode;
        this.destNode = destNode;
        this.nonZeroProbCount = nonZeroProbCount;
        this.probability = probability;
    }
}
