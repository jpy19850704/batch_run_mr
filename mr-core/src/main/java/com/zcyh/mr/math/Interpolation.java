package com.zcyh.mr.math;

import com.zcyh.mr.support.Series;
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * 插值方法公共函数类
 *
 * @author lsd
 * @version 1.0
 * @date 2024/7/10 14:00
 */
public class Interpolation {

    public enum Type {
        LINEAR,
        LINERVAR,
        CUBICSPLINE,
        FORWARD,
        LOG
    }

    private static boolean isType(String type) {
        for (Type t : Type.values()) {
            if (t.name().equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSupportedType(String type) {
        return isType(type);
    }

    public static PreparedInterpolator prepare(Series<Integer, Double> data, String type) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        return new PreparedInterpolator(data, resolveType(type));
    }

    private static Type resolveType(String type) {
        return isType(type) ? Type.valueOf(type.toUpperCase()) : Type.LINEAR;
    }

    public static final class PreparedInterpolator implements Serializable {
        private final double[] x;
        private final double[] y;
        private final double[] variance;
        private final Type type;
        private transient volatile PolynomialSplineFunction splineFunction;

        private PreparedInterpolator(Series<Integer, Double> data, Type type) {
            this.x = new double[data.size()];
            this.y = new double[data.size()];
            int index = 0;
            for (Map.Entry<Integer, Double> entry : data.entrySet()) {
                x[index] = entry.getKey();
                y[index] = entry.getValue();
                index++;
            }
            this.type = type;
            if (type == Type.LINERVAR) {
                this.variance = new double[y.length];
                for (int i = 0; i < y.length; i++) {
                    variance[i] = y[i] * y[i];
                }
            } else {
                this.variance = null;
            }
            if (type == Type.CUBICSPLINE) {
                this.splineFunction = buildSpline();
            }
        }

        public double interpolate(int point) {
            switch (type) {
                case LINERVAR:
                    return Math.sqrt(linear(point, variance));
                case CUBICSPLINE:
                    return cubicSpline(point);
                case FORWARD:
                    return forward(point);
                case LOG:
                    return log(point);
                default:
                    return linear(point, y);
            }
        }

        private double linear(double point, double[] values) {
            if (point <= x[0]) {
                return values[0];
            }
            int last = x.length - 1;
            if (point >= x[last]) {
                return values[last];
            }
            int left = floorIndex(point);
            return interpolation(x[left], values[left], x[left + 1], values[left + 1], point);
        }

        private double forward(double point) {
            if (point <= x[0]) {
                return y[0];
            }
            int last = x.length - 1;
            if (point >= x[last]) {
                return y[last];
            }
            return y[floorIndex(point)];
        }

        private double log(double point) {
            if (point <= x[0]) {
                return y[0];
            }
            int last = x.length - 1;
            if (point >= x[last]) {
                return y[last];
            }
            int left = floorIndex(point);
            double yLeft = y[left];
            double yRight = y[left + 1];
            if (yLeft <= 0.0 || yRight <= 0.0) {
                return interpolation(x[left], yLeft, x[left + 1], yRight, point);
            }
            double ratio = (point - x[left]) / (x[left + 1] - x[left]);
            return Math.exp(Math.log(yLeft) + ratio * (Math.log(yRight) - Math.log(yLeft)));
        }

        private double cubicSpline(double point) {
            if (x.length < 3 || point <= x[0] || point >= x[x.length - 1]) {
                return linear(point, y);
            }
            PolynomialSplineFunction spline = splineFunction;
            if (spline == null) {
                synchronized (this) {
                    if (splineFunction == null) {
                        splineFunction = buildSpline();
                    }
                    spline = splineFunction;
                }
            }
            if (spline == null) {
                return linear(point, y);
            }
            try {
                double value = spline.value(point);
                return Double.isFinite(value) ? value : linear(point, y);
            } catch (RuntimeException e) {
                return linear(point, y);
            }
        }

        private PolynomialSplineFunction buildSpline() {
            if (x.length < 3) {
                return null;
            }
            try {
                return new SplineInterpolator().interpolate(x, y);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private int floorIndex(double point) {
            int left = 0;
            int right = x.length - 1;
            while (left <= right) {
                int mid = left + (right - left) / 2;
                if (x[mid] == point) {
                    return mid;
                }
                if (x[mid] < point) {
                    left = mid + 1;
                } else {
                    right = mid - 1;
                }
            }
            return right;
        }
    }

    /**
     * 根据插值类型返回对应的插值结果
     * 
     * @param data 曲线
     * @param dx   插值点
     * @param type 插值类型
     * @return 插值结果
     */
    public static double interpolate(Series<Integer, Double> data, Integer dx, String type) {
        /* 传入字符串是为了方便不同类型的曲线类中参数获取, 先判断是否在类型枚举列表中, 若不在列表中默认线性插值 */
        Type t = resolveType(type);
        switch (t) {
            case LINERVAR: {
                Double[] xArr = data.keySet().stream()
                        .map(Double::valueOf).toArray(Double[]::new);
                Double[] yArr = data.values().toArray(new Double[0]);
                return linearVarianceInterpolation(xArr, yArr, Double.valueOf(dx));
            }
            case CUBICSPLINE: {
                Double[] xArr = data.keySet().stream()
                        .map(Double::valueOf).toArray(Double[]::new);
                Double[] yArr = data.values().toArray(new Double[0]);
                return cubicSplineInterpolation(xArr, yArr, Double.valueOf(dx));
            }
            case FORWARD:
                return forwardInterpolate(data, dx);
            case LOG: {
                Double[] xArr = data.keySet().stream()
                        .map(Double::valueOf).toArray(Double[]::new);
                Double[] yArr = data.values().toArray(new Double[0]);
                return logInterpolation(xArr, yArr, Double.valueOf(dx));
            }
            default:
                return linearInterpolate(data, dx);
        }
    }

    /**
     * 日期序列插值重载：内部转换为 epochDay 后复用整数插值逻辑。
     */
    public static double interpolate(Series<LocalDate, Double> data, LocalDate dx, String type) {
        Series<Integer, Double> dataByEpochDay = new Series<>(Integer.class, Double.class);
        for (LocalDate date : data.keySet()) {
            dataByEpochDay.put(Math.toIntExact(date.toEpochDay()), data.get(date));
        }
        return interpolate(dataByEpochDay, Math.toIntExact(dx.toEpochDay()), type);
    }

    /**
     * 数组序列插值重载：根据插值类型返回对应结果。
     */
    public static double interpolate(Double[] x1, Double[] y1, Double x, String type) {
        Type t = resolveType(type);
        switch (t) {
            case LINERVAR:
                return linearVarianceInterpolation(x1, y1, x);
            case CUBICSPLINE:
                return cubicSplineInterpolation(x1, y1, x);
            case FORWARD:
                return forwardInterpolation(x1, y1, x);
            case LOG:
                return logInterpolation(x1, y1, x);
            default:
                return linearInterpolation(x1, y1, x);
        }
    }

    /**
     * 线性插值方法，传入有序序列，以及对应的插值点
     *
     * @param data: 曲线的期限点信息
     * @param dx:   需要估值的期限点
     * @return double
     * @author lsd
     * @date 2024/7/10 15:04
     */
    private static double linearInterpolate(Series<Integer, Double> data, Integer dx) {
        int[] vx = data.keySet()
                .stream()
                .mapToInt(i -> i)
                .toArray();

        if (dx <= vx[0]) {
            return data.get(vx[0]);
        }
        if (dx >= vx[vx.length - 1]) {
            return data.get(vx[vx.length - 1]);
        }

        int l = binarySearch(vx, dx);

        int vxl = vx[l];
        int vxr = vx[l + 1];
        double vyl = data.get(vxl);
        double vyr = data.get(vxr);
        return vyl + (vyr - vyl) / (vxr - vxl) * (dx - vxl);
    }

    /**
     * 向前取值（Forward Fill）：返回目标点左侧最近已知点的值。
     */
    private static double forwardInterpolate(Series<Integer, Double> data, Integer dx) {
        int[] vx = data.keySet()
                .stream()
                .mapToInt(i -> i)
                .toArray();

        if (dx <= vx[0]) {
            return data.get(vx[0]);
        }
        if (dx >= vx[vx.length - 1]) {
            return data.get(vx[vx.length - 1]);
        }

        int l = binarySearch(vx, dx);
        return data.get(vx[l]);
    }

    /**
     * 二分搜索函数，返回对应目标点的下边界索引，若处于区间则返回前一个元素的节点（left）
     *
     * @param nums:   有序的整形数值
     * @param target: 待搜索的点
     * @return int
     * @author lsd
     * @date 2024/7/10 15:07
     */
    public static int binarySearch(int[] nums, int target) {
        int l = 0;
        int r = nums.length - 1;

        int m = 0;
        while (l <= r) {
            m = l + (r - l) / 2;
            if (nums[m] == target) {
                break;
            } else if (nums[m] < target) {
                l = m + 1;
            } else {
                r = m - 1;
            }
        }
        if (l > r) {
            m = r;
        }
        return m;
    }

    /**
     * 线性插值保证有序
     * 
     * @param x1
     * @param y1
     * @param x
     * @return
     */
    public static double linearInterpolation(Double[] x1, Double[] y1, Double x) {
        double y = y1[0];
        if (x1[x1.length - 1] < x) {
            y = y1[y1.length - 1];
        }
        // 二分查找
        int left = 0;
        int right = x1.length - 1;
        while (left <= right) {
            int mid = (left + right + 1) / 2;
            if (x1[mid].equals(x)) {
                y = y1[mid];
                break;
            }
            if (mid + 1 < x1.length && x1[mid] < x && x1[mid + 1] > x) {
                y = interpolation(x1[mid], y1[mid], x1[mid + 1], y1[mid + 1], x);
                break;
            }
            if (x1[mid] > x)
                right = mid - 1;
            else
                left = mid + 1;
        }
        return y;
    }

    /**
     * 数组版本向前取值：返回目标点左侧最近已知点的值。
     */
    private static double forwardInterpolation(Double[] x1, Double[] y1, Double x) {
        double y = y1[0];
        if (x1[x1.length - 1] < x) {
            return y1[y1.length - 1];
        }
        int left = 0;
        int right = x1.length - 1;
        while (left <= right) {
            int mid = (left + right + 1) / 2;
            if (x1[mid].equals(x)) {
                y = y1[mid];
                break;
            }
            if (mid + 1 < x1.length && x1[mid] < x && x1[mid + 1] > x) {
                y = y1[mid];
                break;
            }
            if (x1[mid] > x)
                right = mid - 1;
            else
                left = mid + 1;
        }
        return y;
    }

    public static double interpolation(double x1, double y1, double x2, double y2, double x) {
        return (y2 - y1) / (x2 - x1) * (x - x1) + y1;
    }

    /**
     * 三次样条插值保证有序
     * 
     * @param x1
     * @param y1
     * @param x
     * @return
     */
    public static double cubicSplineInterpolation(Double[] x1, Double[] y1, Double x) {
        if (x1 == null || y1 == null || x1.length == 0 || x1.length != y1.length) {
            return 0.0;
        }
        if (x1.length == 1) {
            return y1[0];
        }
        if (x1.length == 2) {
            return linearInterpolation(x1, y1, x);
        }

        // 对 x 升序排序并去重，重复点保留最后一个值，避免样条因重复节点报错。
        TreeMap<Double, Double> sorted = new TreeMap<>();
        for (int i = 0; i < x1.length; i++) {
            if (x1[i] == null || y1[i] == null) {
                continue;
            }
            sorted.put(x1[i], y1[i]);
        }
        if (sorted.isEmpty()) {
            return 0.0;
        }
        if (sorted.size() == 1) {
            return sorted.firstEntry().getValue();
        }

        Double[] xSorted = new Double[sorted.size()];
        Double[] ySorted = new Double[sorted.size()];
        int idx = 0;
        for (Map.Entry<Double, Double> entry : sorted.entrySet()) {
            xSorted[idx] = entry.getKey();
            ySorted[idx] = entry.getValue();
            idx++;
        }

        if (xSorted.length == 2) {
            return linearInterpolation(xSorted, ySorted, x);
        }
        if (x <= xSorted[0]) {
            return ySorted[0];
        }
        if (x >= xSorted[xSorted.length - 1]) {
            return ySorted[ySorted.length - 1];
        }

        double[] x2 = IntStream.range(0, xSorted.length)
                .mapToDouble(i -> xSorted[i])
                .toArray();

        double[] y2 = IntStream.range(0, ySorted.length)
                .mapToDouble(i -> ySorted[i])
                .toArray();

        try {
            // 创建样条插值器
            SplineInterpolator interpolator = new SplineInterpolator();
            // 生成多项式样条函数
            PolynomialSplineFunction splineFunction = interpolator.interpolate(x2, y2);
            // 区间内进行样条插值，异常时回退线性插值。
            double value = splineFunction.value(x);
            return Double.isFinite(value) ? value : linearInterpolation(xSorted, ySorted, x);
        } catch (Exception e) {
            return linearInterpolation(xSorted, ySorted, x);
        }
    }

    /**
     * 线性方差插值保证有序
     * 
     * @param x1
     * @param y1
     * @param x
     * @return
     */
    public static double linearVarianceInterpolation(Double[] x1, Double[] y1, Double x) {
        // 拷贝后平方，避免修改原始数组
        Double[] y1Sq = new Double[y1.length];
        for (int i = 0; i < y1.length; i++) {
            y1Sq[i] = y1[i] * y1[i];
        }

        return Math.sqrt(Interpolation.linearInterpolation(x1, y1Sq, x));
    }

    /**
     * LOG 线性插值：对 y 做 log 变换后线性插值，再指数还原。
     * 当端点值 <= 0 时退化为普通线性插值。
     */
    public static double logInterpolation(Double[] x1, Double[] y1, Double x) {
        if (x1 == null || y1 == null || x1.length == 0 || x1.length != y1.length) {
            return 0.0;
        }
        if (x1.length == 1) {
            return y1[0];
        }

        if (x <= x1[0]) {
            return y1[0];
        }
        if (x >= x1[x1.length - 1]) {
            return y1[y1.length - 1];
        }

        int left = 0;
        int right = x1.length - 1;
        while (left <= right) {
            int mid = (left + right + 1) / 2;
            if (x1[mid].equals(x)) {
                return y1[mid];
            }
            if (mid + 1 < x1.length && x1[mid] < x && x1[mid + 1] > x) {
                double yL = y1[mid];
                double yR = y1[mid + 1];
                if (yL <= 0 || yR <= 0) {
                    return interpolation(x1[mid], yL, x1[mid + 1], yR, x);
                }
                double ratio = (x - x1[mid]) / (x1[mid + 1] - x1[mid]);
                return Math.exp(Math.log(yL) + ratio * (Math.log(yR) - Math.log(yL)));
            }
            if (x1[mid] > x) {
                right = mid - 1;
            } else {
                left = mid + 1;
            }
        }
        return y1[y1.length - 1];
    }

}
