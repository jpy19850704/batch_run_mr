package com.zcyh.mr.product.basic.willow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WillowTransitionSet implements WillowTransitionProvider {
    private static final double PROB_SUM_TOLERANCE = 1e-10;

    private final Map<Integer, Map<Integer, List<WillowTransition>>> transitions;

    public WillowTransitionSet(List<WillowTransition> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Willow转移概率不能为空");
        }
        Map<Integer, Map<Integer, List<WillowTransition>>> grouped = new LinkedHashMap<>();
        for (WillowTransition row : rows) {
            grouped.computeIfAbsent(row.timeIndex, key -> new LinkedHashMap<>())
                    .computeIfAbsent(row.originNode, key -> new ArrayList<>())
                    .add(row);
        }
        validate(grouped);
        this.transitions = freeze(grouped);
    }

    @Override
    public List<WillowTransition> getTransitions(int timeIndex, int originNode) {
        Map<Integer, List<WillowTransition>> byOrigin = transitions.get(timeIndex);
        if (byOrigin == null) {
            throw new IllegalArgumentException("未找到Willow转移矩阵: TIME_INDEX=" + timeIndex);
        }
        List<WillowTransition> list = byOrigin.get(originNode);
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("未找到Willow节点转移: TIME_INDEX=" + timeIndex
                    + ", originNode=" + originNode);
        }
        return list;
    }

    public Map<Integer, Map<Integer, List<WillowTransition>>> asMap() {
        return transitions;
    }

    private static void validate(Map<Integer, Map<Integer, List<WillowTransition>>> grouped) {
        for (Map.Entry<Integer, Map<Integer, List<WillowTransition>>> timeEntry : grouped.entrySet()) {
            for (Map.Entry<Integer, List<WillowTransition>> originEntry : timeEntry.getValue().entrySet()) {
                List<WillowTransition> list = originEntry.getValue();
                int declaredCount = list.get(0).nonZeroProbCount;
                double sum = 0.0;
                for (WillowTransition transition : list) {
                    if (transition.nonZeroProbCount != declaredCount) {
                        throw new IllegalArgumentException("同一origin节点的非零转移数量不一致: TIME_INDEX="
                                + timeEntry.getKey() + ", originNode=" + originEntry.getKey());
                    }
                    sum += transition.probability;
                }
                if (declaredCount != list.size()) {
                    throw new IllegalArgumentException("非零转移数量与实际行数不一致: TIME_INDEX="
                            + timeEntry.getKey() + ", originNode=" + originEntry.getKey());
                }
                if (Math.abs(sum - 1.0) > PROB_SUM_TOLERANCE) {
                    throw new IllegalArgumentException("转移概率和不为1: TIME_INDEX="
                            + timeEntry.getKey() + ", originNode=" + originEntry.getKey() + ", sum=" + sum);
                }
            }
        }
    }

    private static Map<Integer, Map<Integer, List<WillowTransition>>> freeze(
            Map<Integer, Map<Integer, List<WillowTransition>>> source) {
        Map<Integer, Map<Integer, List<WillowTransition>>> frozen = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<Integer, List<WillowTransition>>> timeEntry : source.entrySet()) {
            Map<Integer, List<WillowTransition>> byOrigin = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<WillowTransition>> originEntry : timeEntry.getValue().entrySet()) {
                byOrigin.put(originEntry.getKey(), Collections.unmodifiableList(new ArrayList<>(originEntry.getValue())));
            }
            frozen.put(timeEntry.getKey(), Collections.unmodifiableMap(byOrigin));
        }
        return Collections.unmodifiableMap(frozen);
    }
}
