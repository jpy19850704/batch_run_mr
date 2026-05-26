package com.zcyh.mr.core;

/**
 * Sobol 正态随机矩阵统一入口。
 */
public final class SobolRandomEngine {

    private static final int DEFAULT_SKIP = Math.max(0, Integer.getInteger("mr.sobol.skip", 1));

    private SobolRandomEngine() {
    }

    public static double[][] generateNormalMatrix(int steps, int paths) {
        return RandomMatrix.generateRandomMatrixFromExist(steps, paths);
    }

    public static double[][] generateNormalMatrix(int steps, int paths, int skip) {
        if (skip != DEFAULT_SKIP) {
            throw new IllegalArgumentException("当前 Sobol 缓存仅支持系统参数 mr.sobol.skip=" + DEFAULT_SKIP);
        }
        return generateNormalMatrix(steps, paths);
    }
}
