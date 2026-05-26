package com.zcyh.mr.math;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

/**
 * 矩阵运算工具类
 */
public class MatrixUtil {
    private static final Logger log = LoggerFactory.getLogger(MatrixUtil.class);

    /**
     * 实现np.sqrt(dt)
     * @param matrix
     * @return
     */
    public static RealMatrix sqrt(RealMatrix matrix){

        // 获取矩阵的行数和列数
        int rows = matrix.getRowDimension();
        int cols = matrix.getColumnDimension();

        // 创建一个新的矩阵来存储结果
        RealMatrix resultMatrix = new Array2DRowRealMatrix(rows, cols);

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                // 对矩阵中的每个元素进行平方根运算
                double value = matrix.getEntry(i, j);
                if (value < 0) {
                    log.warn("矩阵开方遇到负值: row={}, col={}, value={}", i, j, value);
                    continue;
                }
                double sqrtValue = Math.sqrt(value);
                resultMatrix.setEntry(i, j, sqrtValue);
            }
        }
        return resultMatrix;
    }

    /**
     * 计算二维数组的累积和，可根据指定的轴（axis）进行计算，axis为0表示按列累积，axis为1表示按行累积
     *
     * @param matrix  输入的二维数组
     * @param axis 轴的标识，0或1
     * @return 累积和结果二维数组
     */
    public static double[][] cumsum(double[][] matrix, int axis) {
        if (axis!= 0 && axis!= 1) {
            throw new IllegalArgumentException("axis 取值无效，仅支持 0 或 1。");
        }

        int rows = matrix.length;
        if (rows == 0) {
            return matrix;
        }
        int cols = matrix[0].length;

        if (axis == 0) {
            // 按列计算累积和
            for (int j = 0; j < cols; j++) {
                double sum = 0.0;
                for (int i = 0; i < rows; i++) {
                    sum += matrix[i][j];
                    matrix[i][j] = sum;
                }
            }
        } else {
            // 按行计算累积和
            for (int i = 0; i < rows; i++) {
                double sum = 0.0;
                for (int j = 0; j < cols; j++) {
                    sum += matrix[i][j];
                    matrix[i][j] = sum;
                }
            }
        }

        return matrix;
    }


    /**
     * @param vector 计算累积和
     * @return
     */
    public static RealVector partialSum(RealVector vector) {
        int size = vector.getDimension();
        double[] result = new double[size];
//         计算累积和
         result[0] = vector.getEntry(0);
         for (int i = 1; i < size; i++) {
         result[i] = result[i - 1] + vector.getEntry(i);
         }
//         返回包含部分累积和的新 RealVector
         return new ArrayRealVector(result);
    }

    /**
     * 矩阵指数运算
     * @param matrix
     * @return
     */
    public static RealMatrix exp(RealMatrix matrix){

        // 获取矩阵的行数和列数
        double rows = matrix.getRowDimension();
        double cols = matrix.getColumnDimension();

        // 对矩阵中的每个元素进行指数运算
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double value = matrix.getEntry(i, j);
                double expValue = Math.exp(value);
                matrix.setEntry(i, j, expValue);
            }
        }

        return matrix;
    }

    /**
     * 矩阵指数运算
     * @param matrix
     * @return
     */
    public static double[][] exp(double[][] matrix){

        // 获取矩阵的行数和列数
        int rows = matrix.length;
        int cols = matrix[0].length;

        // 对矩阵中的每个元素进行指数运算
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double value = matrix[i][j];
                double expValue = Math.exp(value);
                matrix[i][j] = expValue;
            }
        }

        return matrix;
    }

    /**
     * 矩阵平方运算
     * @param matrix
     * @return
     */
    public static RealMatrix pow(RealMatrix matrix){

        // 获取矩阵的行数和列数
        double rows = matrix.getRowDimension();
        double cols = matrix.getColumnDimension();

        // 对矩阵中的每个元素进行平方运算
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double value = matrix.getEntry(i, j);
                double expValue =  Math.pow(value, 2);
                matrix.setEntry(i, j, expValue);
            }
        }

        return matrix;
    }

    /**
     * 矩阵每个数 * s
     * @param matrix
     * @return
     */
    public static double[][] scalarMultiply(double[][] matrix, double s) {
        // 获取矩阵的行数和列数
        int rows = matrix.length;
        int cols = matrix[0].length;

        // 对矩阵中的每个元素进行指数运算
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                double value = matrix[i][j];
                double expValue = value * s;
                matrix[i][j] = expValue;
            }
        }

        return matrix;
    }

        public static void main(String[] args) {

        double[][] a = {{1,2,3},{4,5,6},{7,8,9}};
        Array2DRowRealMatrix array2DRowRealMatrix = new Array2DRowRealMatrix(a);
        RealMatrix cumsum = MatrixUtil.pow(array2DRowRealMatrix);
        log.info("{}", cumsum);

        }


}
