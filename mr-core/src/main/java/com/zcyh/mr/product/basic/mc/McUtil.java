package com.zcyh.mr.product.basic.mc;

import com.zcyh.mr.core.SobolRandomEngine;

public class McUtil {

    public static double[][] createPath(double s, double[] dt1, double[] dt2, double[] rdList, double[] rfList, double[] sigmaList, int m, int i) {
        double[][] randomMatrix = SobolRandomEngine.generateNormalMatrix(m, i);
        return createPathWithRandom(s, dt1, dt2, rdList, rfList, sigmaList, randomMatrix);
    }

    public static double[][] createPathWithRandom(double s, double[] dt1, double[] dt2, double[] rdList,
            double[] rfList, double[] sigmaList, double[][] randomMatrix) {
        return scalePath(createGrowthWithRandom(dt1, dt2, rdList, rfList, sigmaList, randomMatrix), s);
    }

    /**
     * 使用固定随机矩阵构建增长路径 exp(logS/S0)。
     * 形状：[observationCount][simulationCount]
     */
    public static double[][] createGrowthWithRandom(double[] dt1, double[] dt2, double[] rdList, double[] rfList,
            double[] sigmaList, double[][] randomMatrix) {
        validateInputs(dt1, dt2, rdList, rfList, sigmaList, randomMatrix);

        int observationCount = dt1.length;
        int simulationCount = randomMatrix[0].length;
        double[][] growth = new double[observationCount][simulationCount];

        double[] drift = new double[observationCount];
        double[] volStep = new double[observationCount];
        for (int obs = 0; obs < observationCount; obs++) {
            double sigma = sigmaList[obs];
            drift[obs] = (rdList[obs] - rfList[obs] - 0.5 * sigma * sigma) * dt1[obs];
            volStep[obs] = sigma * Math.sqrt(dt2[obs]);
        }

        for (int sim = 0; sim < simulationCount; sim++) {
            double cumDiffusion = 0.0;
            for (int obs = 0; obs < observationCount; obs++) {
                cumDiffusion += volStep[obs] * randomMatrix[obs][sim];
                growth[obs][sim] = Math.exp(drift[obs] + cumDiffusion);
            }
        }
        return growth;
    }

    public static double[][] scalePath(double[][] growth, double spot) {
        int rows = growth.length;
        if (rows == 0) {
            return new double[0][0];
        }
        int cols = growth[0].length;
        double[][] path = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                path[i][j] = growth[i][j] * spot;
            }
        }
        return path;
    }

    private static void validateInputs(double[] dt1, double[] dt2, double[] rdList, double[] rfList,
            double[] sigmaList, double[][] randomMatrix) {
        int n = dt1.length;
        if (dt2.length != n || rdList.length != n || rfList.length != n || sigmaList.length != n) {
            throw new IllegalArgumentException("AutoCall 路径输入长度不一致。");
        }
        if (randomMatrix.length != n) {
            throw new IllegalArgumentException("随机矩阵行数必须与观察次数一致。");
        }
        if (n > 0 && randomMatrix[0].length == 0) {
            throw new IllegalArgumentException("随机矩阵至少需要一条模拟路径。");
        }
        int cols = n == 0 ? 0 : randomMatrix[0].length;
        for (int i = 1; i < n; i++) {
            if (randomMatrix[i].length != cols) {
                throw new IllegalArgumentException("随机矩阵每行列数必须一致。");
            }
        }
    }
}
