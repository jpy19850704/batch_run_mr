package com.zcyh.mr.product.basic.willow;

import java.util.Arrays;

public final class WillowNodeDefinition {
    public static final int NODE_COUNT = 30;
    public static final double GAMMA = 2.0 / 3.0;
    public static final double EPSILON = 0.000282239;

    private static final double[] PROBABILITIES = {
            0.0057492854565020, 0.0119589956721773, 0.0168110126568610,
            0.0210383930995296, 0.0248757134376437, 0.0284364686040567,
            0.0317865046285582, 0.0349683154766573, 0.0380113376596426,
            0.0409370245014865, 0.0437616211493384, 0.0464978026174933,
            0.0491556992059825, 0.0517435691085176, 0.0542682567255533,
            0.0542682567255533, 0.0517435691085176, 0.0491556992059825,
            0.0464978026174933, 0.0437616211493384, 0.0409370245014865,
            0.0380113376596426, 0.0349683154766573, 0.0317865046285582,
            0.0284364686040567, 0.0248757134376437, 0.0210383930995296,
            0.0168110126568610, 0.0119589956721773, 0.0057492854565020
    };

    private static final double[] Z_VALUES = {
            -2.8965958228528800, -2.2814993083503300, -1.9476584976240900,
            -1.6985144656765300, -1.4931349651411600, -1.3142090307078100,
            -1.1526544100058900, -1.0030098522413400, -0.8616450010130760,
            -0.7259357815066490, -0.5938317572752580, -0.4636000955407840,
            -0.3336516625554380, -0.2024000587452200, -0.0681204322740508,
            0.0681204322740508, 0.2024000587452200, 0.3336516625554380,
            0.4636000955407840, 0.5938317572752580, 0.7259357815066490,
            0.8616450010130760, 1.0030098522413400, 1.1526544100058900,
            1.3142090307078100, 1.4931349651411600, 1.6985144656765300,
            1.9476584976240900, 2.2814993083503300, 2.8965958228528800
    };

    private WillowNodeDefinition() {
    }

    public static double[] probabilities() {
        return Arrays.copyOf(PROBABILITIES, PROBABILITIES.length);
    }

    public static double[] zValues() {
        return Arrays.copyOf(Z_VALUES, Z_VALUES.length);
    }

    public static double probability(int nodeIndex) {
        validateNodeIndex(nodeIndex);
        return PROBABILITIES[nodeIndex];
    }

    public static double zValue(int nodeIndex) {
        validateNodeIndex(nodeIndex);
        return Z_VALUES[nodeIndex];
    }

    static void validateNodeIndex(int nodeIndex) {
        if (nodeIndex < 0 || nodeIndex >= NODE_COUNT) {
            throw new IllegalArgumentException("Willow节点索引超出范围: " + nodeIndex);
        }
    }
}
