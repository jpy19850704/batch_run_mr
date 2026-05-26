package com.zcyh.mr.product.basic.willow;

public final class WillowTimeIndex {
    public static final int DEFAULT_K_COUNT = 500;
    public static final double DEFAULT_ALPHA_MAX = 365.0;

    private WillowTimeIndex() {
    }

    public static double relativeAlpha(double currentBrownianTime, double nextBrownianDelta) {
        if (currentBrownianTime <= 0.0 || nextBrownianDelta <= 0.0) {
            throw new IllegalArgumentException("Brownian时间必须大于0");
        }
        return nextBrownianDelta / currentBrownianTime;
    }

    public static double kFromAlpha(double alpha) {
        if (alpha < 0.0 || !Double.isFinite(alpha)) {
            throw new IllegalArgumentException("alpha非法: " + alpha);
        }
        return 1.0 / Math.sqrt(1.0 + alpha);
    }

    public static int recoverZeroBasedIndex(double k) {
        return recoverZeroBasedIndex(k, DEFAULT_K_COUNT, DEFAULT_ALPHA_MAX);
    }

    public static int recoverZeroBasedIndex(double k, int kCount, double alphaMax) {
        if (k <= 0.0 || k > 1.0 || !Double.isFinite(k)) {
            throw new IllegalArgumentException("K非法: " + k);
        }
        if (kCount <= 0) {
            throw new IllegalArgumentException("K网格数量必须大于0");
        }
        double k0 = kFromAlpha(alphaMax);
        double raw = kCount * (1.0 - Math.acos(k) / Math.acos(k0));
        int index = (int) raw;
        if (index < 0) {
            return 0;
        }
        if (index >= kCount) {
            return kCount - 1;
        }
        return index;
    }

    public static int recoverOneBasedIndex(double k) {
        return recoverZeroBasedIndex(k) + 1;
    }

    public static int recoverZeroBasedIndex(double currentBrownianTime, double nextBrownianDelta) {
        return recoverZeroBasedIndex(kFromAlpha(relativeAlpha(currentBrownianTime, nextBrownianDelta)));
    }

    public static int recoverOneBasedIndex(double currentBrownianTime, double nextBrownianDelta) {
        return recoverZeroBasedIndex(currentBrownianTime, nextBrownianDelta) + 1;
    }
}
