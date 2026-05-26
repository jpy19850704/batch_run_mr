package com.zcyh.mr.math;

/**
 * @author lsd
 * @version 1.0
 * @date 2024/8/15 16:40
 */

public class Newton {
    private static final int MAX_FUNCTION_EVALUATIONS = 100;
    private double root;

    public double solve(Ops.DoubleOp func, double accuracy, final double guess, final double xMin, final double xMax) {
        root = guess;
        double result = solveImpl(func,accuracy);
        if (result < xMin){
            return xMin;
        }else return Math.min(result, xMax);
    }

    protected double solveImpl(Ops.DoubleOp f, double accuracy){
        double x, epsilon, delta_x;
        epsilon = accuracy;
        delta_x = 0.000001;
        int k = 0;

        do {
            x = root;
            root = x - f.op(x) / ((f.op(x + delta_x) - f.op(x - delta_x)) / (2 * delta_x));
            k++;
            if (k > MAX_FUNCTION_EVALUATIONS) {
                break;
            }
        } while (Math.abs(root - x) >= epsilon);
        return root;
    }
}
