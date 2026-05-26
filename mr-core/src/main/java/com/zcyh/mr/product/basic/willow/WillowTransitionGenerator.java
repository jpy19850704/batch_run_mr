package com.zcyh.mr.product.basic.willow;

import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.LinearConstraint;
import org.apache.commons.math3.optim.linear.LinearConstraintSet;
import org.apache.commons.math3.optim.linear.LinearObjectiveFunction;
import org.apache.commons.math3.optim.linear.NonNegativeConstraint;
import org.apache.commons.math3.optim.linear.Relationship;
import org.apache.commons.math3.optim.linear.SimplexSolver;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WillowTransitionGenerator implements WillowTransitionProvider {
    private static final double SIMPLEX_EPSILON = 1e-10;
    private static final double PROBABILITY_EPSILON = 1e-12;
    private static final int MAX_ITERATIONS = 200000;

    private final int kCount;
    private final double alphaMax;
    private final Map<Integer, List<List<WillowTransition>>> cache = new ConcurrentHashMap<>();

    public WillowTransitionGenerator() {
        this(WillowTimeIndex.DEFAULT_K_COUNT, WillowTimeIndex.DEFAULT_ALPHA_MAX);
    }

    public WillowTransitionGenerator(int kCount, double alphaMax) {
        if (kCount <= 0) {
            throw new IllegalArgumentException("K网格数量必须大于0");
        }
        if (alphaMax <= 0.0 || !Double.isFinite(alphaMax)) {
            throw new IllegalArgumentException("alphaMax必须大于0");
        }
        this.kCount = kCount;
        this.alphaMax = alphaMax;
    }

    @Override
    public List<WillowTransition> getTransitions(int timeIndex, int originNode) {
        if (timeIndex <= 0 || timeIndex > kCount) {
            throw new IllegalArgumentException("Willow TIME_INDEX超出网格范围: " + timeIndex);
        }
        WillowNodeDefinition.validateNodeIndex(originNode);
        return cache.computeIfAbsent(timeIndex, this::generateByTimeIndex).get(originNode);
    }

    public List<WillowTransition> getTransitions(double currentBrownianTime,
            double nextBrownianDelta,
            int originNode) {
        double alpha = WillowTimeIndex.relativeAlpha(currentBrownianTime, nextBrownianDelta);
        return getTransitionsByK(WillowTimeIndex.kFromAlpha(alpha), originNode);
    }

    public List<WillowTransition> getTransitionsByK(double k, int originNode) {
        WillowNodeDefinition.validateNodeIndex(originNode);
        validateKForInterpolation(k);
        if (Math.abs(k - 1.0) < 1e-14) {
            return Collections.singletonList(new WillowTransition(kCount + 1, originNode, originNode, 1, 1.0));
        }

        int lowerIndex = lowerGridIndex(k);
        int upperIndex = lowerIndex + 1;
        double lowerK = kForZeroBasedIndex(lowerIndex);
        double upperK = kForZeroBasedIndex(upperIndex);
        double denominator = upperK - lowerK;
        if (denominator <= 0.0 || !Double.isFinite(denominator)) {
            throw new IllegalArgumentException("Willow K网格区间非法: lowerK=" + lowerK + ", upperK=" + upperK);
        }

        double upperWeight = (k - lowerK) / denominator;
        double lowerWeight = 1.0 - upperWeight;
        double[] row = new double[WillowNodeDefinition.NODE_COUNT];
        addWeightedTransitions(row, getGridOrIdentityTransitions(lowerIndex, originNode), lowerWeight);
        addWeightedTransitions(row, getGridOrIdentityTransitions(upperIndex, originNode), upperWeight);
        normalizeRow(row);
        return Collections.unmodifiableList(buildTransitions(lowerIndex + 1, originNode, row));
    }

    public double kForOneBasedIndex(int timeIndex) {
        if (timeIndex <= 0 || timeIndex > kCount) {
            throw new IllegalArgumentException("Willow TIME_INDEX超出网格范围: " + timeIndex);
        }
        return kForZeroBasedIndex(timeIndex - 1);
    }

    private List<List<WillowTransition>> generateByTimeIndex(int timeIndex) {
        double[][] matrix = solveTransitionMatrix(kForOneBasedIndex(timeIndex));
        List<List<WillowTransition>> rows = new ArrayList<>();
        for (int origin = 0; origin < WillowNodeDefinition.NODE_COUNT; origin++) {
            List<WillowTransition> transitions = buildTransitions(timeIndex, origin, matrix[origin]);
            rows.add(Collections.unmodifiableList(transitions));
        }
        return Collections.unmodifiableList(rows);
    }

    private double kForZeroBasedIndex(int zeroBasedIndex) {
        if (zeroBasedIndex < 0 || zeroBasedIndex > kCount) {
            throw new IllegalArgumentException("Willow K网格索引超出范围: " + zeroBasedIndex);
        }
        if (zeroBasedIndex == kCount) {
            return 1.0;
        }
        double k0 = WillowTimeIndex.kFromAlpha(alphaMax);
        return Math.cos((1.0 - (double) zeroBasedIndex / kCount) * Math.acos(k0));
    }

    private int lowerGridIndex(double k) {
        double k0 = WillowTimeIndex.kFromAlpha(alphaMax);
        double raw = kCount * (1.0 - Math.acos(k) / Math.acos(k0));
        int lowerIndex = (int) Math.floor(raw);
        if (lowerIndex < 0) {
            return 0;
        }
        if (lowerIndex >= kCount) {
            return kCount - 1;
        }
        return lowerIndex;
    }

    private void validateKForInterpolation(double k) {
        double k0 = WillowTimeIndex.kFromAlpha(alphaMax);
        if (k < k0 - 1e-14 || k > 1.0 + 1e-14 || !Double.isFinite(k)) {
            throw new IllegalArgumentException("Willow K超出插值网格范围: K=" + k + ", minK=" + k0);
        }
    }

    private List<WillowTransition> getGridOrIdentityTransitions(int zeroBasedIndex, int originNode) {
        if (zeroBasedIndex == kCount) {
            return Collections.singletonList(new WillowTransition(kCount + 1, originNode, originNode, 1, 1.0));
        }
        return getTransitions(zeroBasedIndex + 1, originNode);
    }

    private static void addWeightedTransitions(double[] row, List<WillowTransition> transitions, double weight) {
        if (Math.abs(weight) < PROBABILITY_EPSILON) {
            return;
        }
        for (WillowTransition transition : transitions) {
            row[transition.destNode] += weight * transition.probability;
        }
    }

    private double[][] solveTransitionMatrix(double k) {
        int nodeCount = WillowNodeDefinition.NODE_COUNT;
        int vars = nodeCount * nodeCount;
        double[] zValues = WillowNodeDefinition.zValues();
        double[] probabilities = WillowNodeDefinition.probabilities();

        double[] objective = new double[vars];
        for (int origin = 0; origin < nodeCount; origin++) {
            for (int dest = 0; dest < nodeCount; dest++) {
                double distance = zValues[dest] - k * zValues[origin];
                objective[index(origin, dest)] = distance * distance;
            }
        }

        List<LinearConstraint> constraints = new ArrayList<>();
        addRowSumConstraints(constraints, vars, nodeCount);
        addStationaryDistributionConstraints(constraints, vars, nodeCount, probabilities);
        addMomentConstraints(constraints, vars, nodeCount, zValues, k);

        PointValuePair result = new SimplexSolver(SIMPLEX_EPSILON).optimize(
                new MaxIter(MAX_ITERATIONS),
                new LinearObjectiveFunction(objective, 0.0),
                new LinearConstraintSet(constraints),
                GoalType.MINIMIZE,
                new NonNegativeConstraint(true));

        double[] point = result.getPoint();
        double[][] matrix = new double[nodeCount][nodeCount];
        for (int origin = 0; origin < nodeCount; origin++) {
            for (int dest = 0; dest < nodeCount; dest++) {
                double probability = point[index(origin, dest)];
                matrix[origin][dest] = probability < PROBABILITY_EPSILON ? 0.0 : probability;
            }
            normalizeRow(matrix[origin]);
        }
        return matrix;
    }

    private static void addRowSumConstraints(List<LinearConstraint> constraints, int vars, int nodeCount) {
        for (int origin = 0; origin < nodeCount; origin++) {
            double[] coefficients = new double[vars];
            for (int dest = 0; dest < nodeCount; dest++) {
                coefficients[index(origin, dest)] = 1.0;
            }
            constraints.add(new LinearConstraint(coefficients, Relationship.EQ, 1.0));
        }
    }

    private static void addStationaryDistributionConstraints(List<LinearConstraint> constraints,
            int vars,
            int nodeCount,
            double[] probabilities) {
        for (int dest = 0; dest < nodeCount; dest++) {
            double[] coefficients = new double[vars];
            for (int origin = 0; origin < nodeCount; origin++) {
                coefficients[index(origin, dest)] = probabilities[origin];
            }
            constraints.add(new LinearConstraint(coefficients, Relationship.EQ, probabilities[dest]));
        }
    }

    private static void addMomentConstraints(List<LinearConstraint> constraints,
            int vars,
            int nodeCount,
            double[] zValues,
            double k) {
        for (int origin = 0; origin < nodeCount; origin++) {
            double[] mean = new double[vars];
            double[] secondMoment = new double[vars];
            for (int dest = 0; dest < nodeCount; dest++) {
                mean[index(origin, dest)] = zValues[dest];
                secondMoment[index(origin, dest)] = zValues[dest] * zValues[dest];
            }
            constraints.add(new LinearConstraint(mean, Relationship.EQ, k * zValues[origin]));
            constraints.add(new LinearConstraint(secondMoment, Relationship.EQ,
                    k * k * zValues[origin] * zValues[origin] + (1.0 - k * k)));
        }
    }

    private static List<WillowTransition> buildTransitions(int timeIndex, int origin, double[] row) {
        int nonZeroCount = 0;
        for (double probability : row) {
            if (probability > 0.0) {
                nonZeroCount++;
            }
        }
        if (nonZeroCount == 0) {
            throw new IllegalArgumentException("Willow转移矩阵生成空行: TIME_INDEX=" + timeIndex
                    + ", originNode=" + origin);
        }
        List<WillowTransition> transitions = new ArrayList<>();
        for (int dest = 0; dest < row.length; dest++) {
            if (row[dest] > 0.0) {
                transitions.add(new WillowTransition(timeIndex, origin, dest, nonZeroCount, row[dest]));
            }
        }
        return transitions;
    }

    private static void normalizeRow(double[] row) {
        double sum = 0.0;
        int maxIndex = 0;
        for (int i = 0; i < row.length; i++) {
            if (row[i] > row[maxIndex]) {
                maxIndex = i;
            }
            sum += row[i];
        }
        if (sum <= 0.0 || !Double.isFinite(sum)) {
            throw new IllegalArgumentException("Willow转移矩阵行概率和非法: " + sum);
        }
        for (int i = 0; i < row.length; i++) {
            row[i] = row[i] / sum;
        }
        double normalizedSum = 0.0;
        for (double probability : row) {
            normalizedSum += probability;
        }
        row[maxIndex] += 1.0 - normalizedSum;
    }

    private static int index(int origin, int dest) {
        return origin * WillowNodeDefinition.NODE_COUNT + dest;
    }
}
