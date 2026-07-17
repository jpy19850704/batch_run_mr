package com.zcyh.mr.math;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.SobolSequenceGenerator;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class RandomMatrix {
    private final static Map<String, double[][]> cache = new ConcurrentHashMap<>();
    private final static Queue<String> cacheKeyOrder = new ConcurrentLinkedQueue<>();
    private static final int MAX_RANDOM_MATRIX_CACHE_ENTRIES = Math.max(
            1, Integer.getInteger("mr.random.matrix.cache.max-entries", 128));

    /**
     * 统一 Sobol 缓存矩阵（单一 bin 文件，维度/路径不足时自动扩展覆盖）。
     * cachedMatrix[dim][path] 格式存储。
     */
    private static volatile double[][] cachedMatrix = null;
    private static volatile int cachedRows = 0;
    private static volatile int cachedCols = 0;
    private static final Object SOBOL_LOCK = new Object();

    private static final NormalDistribution STD_NORMAL = new NormalDistribution();
    private static final int SOBOL_SKIP = Math.max(0, Integer.getInteger("mr.sobol.skip", 1));
    private static final String PRELOAD_SOBOL_RESOURCE = System.getProperty("mr.sobol.preload.csv",
            "product/basic/mc/sobol_11d_10000.csv");
    private static volatile double[][] preloadSobolNormal = null;
    private static volatile int preloadRows = 0;
    private static volatile int preloadCols = 0;

    public static double[][] generateRandomMatrix(int rows, int cols) {
        String key = rows + "," + cols;

        AtomicBoolean created = new AtomicBoolean(false);
        double[][] matrix = cache.computeIfAbsent(key, k -> {
            created.set(true);
            double[][] m = new double[rows][cols];
            RandomGenerator randomGenerator = new JDKRandomGenerator(rows * cols);
            NormalDistribution normalDistribution = new NormalDistribution(randomGenerator, 0, 1);
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    m[i][j] = normalDistribution.sample();
                }
            }
            return m;
        });
        if (created.get()) {
            cacheKeyOrder.offer(key);
            trimRandomMatrixCache();
        }

        // 返回拷贝，避免外部修改污染缓存
        double[][] copy = new double[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            copy[i] = Arrays.copyOf(matrix[i], matrix[i].length);
        }
        return copy;
    }

    public static int randomMatrixCacheSize() {
        return cache.size();
    }

    public static void clearRandomMatrixCache() {
        cache.clear();
        cacheKeyOrder.clear();
    }

    private static void trimRandomMatrixCache() {
        while (cache.size() > MAX_RANDOM_MATRIX_CACHE_ENTRIES) {
            String key = cacheKeyOrder.poll();
            if (key == null) {
                return;
            }
            cache.remove(key);
        }
    }

    /**
     * Sobol 正态随机矩阵。
     * 从统一缓存中截取子集；维度或路径数不足时自动扩展并覆盖缓存文件。
     */
    public static double[][] generateRandomMatrixFromExist(int rows, int cols) {
        if (rows <= 0 || cols <= 0) {
            return new double[0][0];
        }
        ensureCachedMatrix(rows, cols);
        return extractSubMatrix(cachedMatrix, rows, cols);
    }

    /**
     * 确保缓存矩阵的维度和路径数满足需求。
     * 不满足时以 max(已缓存, 请求) 重新生成并覆盖。
     */
    private static void ensureCachedMatrix(int rows, int cols) {
        if (cachedMatrix != null && cachedRows >= rows && cachedCols >= cols) {
            return;
        }
        synchronized (SOBOL_LOCK) {
            if (cachedMatrix != null && cachedRows >= rows && cachedCols >= cols) {
                return;
            }
            int targetRows = Math.max(cachedRows, rows);
            int targetCols = Math.max(cachedCols, cols);

            // 尝试从 bin 缓存加载
            Path cacheFile = unifiedCacheFile();
            double[][] fromBin = readUnifiedBin(cacheFile);
            if (fromBin != null && fromBin.length >= targetRows && fromBin[0].length >= targetCols) {
                cachedMatrix = fromBin;
                cachedRows = fromBin.length;
                cachedCols = fromBin[0].length;
                return;
            }
            // bin 存在但维度不够，取 max
            if (fromBin != null) {
                targetRows = Math.max(targetRows, fromBin.length);
                targetCols = Math.max(targetCols, fromBin[0].length);
            }

            // 尝试从预置 CSV 加载
            double[][] fromPreload = loadFromPreloadResource(targetRows, targetCols);
            if (fromPreload != null) {
                cachedMatrix = fromPreload;
                cachedRows = fromPreload.length;
                cachedCols = fromPreload[0].length;
                writeUnifiedBin(cacheFile, cachedMatrix);
                return;
            }

            // 实时生成
            cachedMatrix = generateSobolNormal(targetRows, targetCols);
            cachedRows = targetRows;
            cachedCols = targetCols;
            writeUnifiedBin(cacheFile, cachedMatrix);
        }
    }

    /**
     * 从缓存矩阵中截取前 rows 行 × cols 列的子集副本
     */
    private static double[][] extractSubMatrix(double[][] matrix, int rows, int cols) {
        double[][] sub = new double[rows][cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(matrix[i], 0, sub[i], 0, cols);
        }
        return sub;
    }

    /** 统一缓存文件路径（不含维度×路径数） */
    private static Path unifiedCacheFile() {
        String cacheDir = System.getProperty("mr.sobol.cache.dir",
                Paths.get(".", "data", "sobol-cache").toString());
        return Paths.get(cacheDir, "sobol_normal_unified_skip" + SOBOL_SKIP + ".bin");
    }

    /** 读取统一 bin 文件（含行列元信息） */
    private static double[][] readUnifiedBin(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(cacheFile)))) {
            int r = in.readInt();
            int c = in.readInt();
            if (r <= 0 || c <= 0) {
                return null;
            }
            double[][] matrix = new double[r][c];
            for (int i = 0; i < r; i++) {
                for (int j = 0; j < c; j++) {
                    matrix[i][j] = in.readDouble();
                }
            }
            return matrix;
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入统一 bin 文件（含行列元信息） */
    private static void writeUnifiedBin(Path cacheFile, double[][] matrix) {
        try {
            Files.createDirectories(cacheFile.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(cacheFile)))) {
                out.writeInt(matrix.length);
                out.writeInt(matrix[0].length);
                for (double[] row : matrix) {
                    for (double v : row) {
                        out.writeDouble(v);
                    }
                }
            }
        } catch (Exception ignored) {
            // 缓存写入失败不影响本次计算
        }
    }

    private static double[][] loadFromPreloadResource(int rows, int cols) {
        ensurePreloadSobolLoaded();
        if (preloadSobolNormal == null) {
            return null;
        }
        int offset = SOBOL_SKIP;

        // 主方向：[rows][cols] = [timeStep][path]
        if (rows <= preloadRows && (cols + offset) <= preloadCols) {
            double[][] sub = new double[rows][cols];
            for (int i = 0; i < rows; i++) {
                System.arraycopy(preloadSobolNormal[i], offset, sub[i], 0, cols);
            }
            return sub;
        }

        // 兼容方向：预置为 [paths][timeStep]，转置截取
        if ((cols + offset) <= preloadRows && rows <= preloadCols) {
            double[][] sub = new double[rows][cols];
            for (int j = 0; j < cols; j++) {
                for (int i = 0; i < rows; i++) {
                    sub[i][j] = preloadSobolNormal[j + offset][i];
                }
            }
            return sub;
        }

        return null;
    }

    private static synchronized void ensurePreloadSobolLoaded() {
        if (preloadSobolNormal != null) {
            return;
        }
        InputStream in = openPreloadInputStream(PRELOAD_SOBOL_RESOURCE);
        if (in == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            java.util.List<double[]> rows = new java.util.ArrayList<>();
            String line;
            int cols = -1;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split(",");
                if (cols < 0) {
                    cols = parts.length;
                }
                if (parts.length != cols) {
                    return;
                }
                double[] row = new double[cols];
                for (int i = 0; i < cols; i++) {
                    row[i] = Double.parseDouble(parts[i].trim());
                }
                rows.add(row);
            }
            if (rows.isEmpty() || cols <= 0) {
                return;
            }
            double[][] matrix = new double[rows.size()][cols];
            for (int i = 0; i < rows.size(); i++) {
                matrix[i] = rows.get(i);
            }
            preloadSobolNormal = matrix;
            preloadRows = matrix.length;
            preloadCols = matrix[0].length;
        } catch (IOException | NumberFormatException ignored) {
            preloadSobolNormal = null;
            preloadRows = 0;
            preloadCols = 0;
        }
    }

    private static InputStream openPreloadInputStream(String location) {
        InputStream in = RandomMatrix.class.getClassLoader().getResourceAsStream(location);
        if (in != null) {
            return in;
        }
        Path p = Paths.get(location);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir"), location);
        }
        try {
            if (Files.exists(p)) {
                return Files.newInputStream(p);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static double[][] generateSobolNormal(int rows, int cols) {
        // Sobol 维度对应随机时步（rows），路径数为 cols
        SobolSequenceGenerator generator = new SobolSequenceGenerator(rows);
        double[][] matrix = new double[rows][cols];

        // 跳过前 N 个 Sobol 点
        for (int k = 0; k < SOBOL_SKIP; k++) {
            generator.nextVector();
        }

        // 每条路径生成一个 rows 维 Sobol 点，正态逆变换
        for (int j = 0; j < cols; j++) {
            double[] vector = generator.nextVector();
            for (int i = 0; i < rows; i++) {
                double u = clampOpenUnit(vector[i]);
                matrix[i][j] = STD_NORMAL.inverseCumulativeProbability(u);
            }
        }
        return matrix;
    }

    private static double clampOpenUnit(double u) {
        if (u <= 0.0) {
            return 1e-12;
        }
        if (u >= 1.0) {
            return 1.0 - 1e-12;
        }
        return u;
    }

    public static void main(String[] args) {
        int rows = 5;
        int cols = 5;
        double[][] matrix = generateRandomMatrix(rows, cols);
    }
}
