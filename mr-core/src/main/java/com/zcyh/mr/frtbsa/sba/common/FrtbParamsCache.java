package com.zcyh.mr.frtbsa.sba.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FRTB 参数缓存
 * 从 param.json 读取所有可调参数，首次加载后缓存。
 *
 * <p>
 * 数据结构：
 * <ul>
 * <li>Weights — 各风险类别的 Delta 风险权重（按 bucket）</li>
 * <li>Scalar — 标量参数（rho、gamma、tenor lambda 等监管公式系数）</li>
 * </ul>
 *
 * <p>
 * 相关性矩阵不再存储于 JSON，由本类根据 Scalar 参数动态构建：
 * <ul>
 * <li>GIRR RHO: ρ = max(e^(-θ×|Tk-Tl|/min(Tk,Tl)), floor)</li>
 * <li>EQ/CSRNS/CSRNC/CSRCTP RHO: 单一标量 ρ_base，CMTY RHO 按 bucket 配置</li>
 * <li>GAMMA: 按风险类别监管口径动态生成，对角线为 1.0</li>
 * </ul>
 *
 * @author system
 * @version 4.0
 */
public class FrtbParamsCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(FrtbParamsCache.class);

    // ===== 缓存 =====
    private static volatile JSONObject ROOT = null;

    private static JSONObject getRoot() {
        if (ROOT == null) {
            synchronized (FrtbParamsCache.class) {
                if (ROOT == null) {
                    String data = FileUtils.loadData("com/zcyh/mr/frtbsa/param.json");
                    ROOT = JSON.parseObject(data);
                }
            }
        }
        return ROOT;
    }

    /** 强制重新加载（用于参数热更新） */
    public static synchronized void reload() {
        ROOT = null;
    }

    // ========================================
    // 通用读取方法
    // ========================================

    /** 读取 Weights 表（HashMap<String,Double>），按 type + riskClass */
    private static HashMap<String, Double> getMap(String type, String riskClass) {
        JSONObject section = getRoot().getJSONObject(type).getJSONObject(riskClass);
        HashMap<String, Double> result = new HashMap<>();
        for (String key : section.keySet()) {
            result.put(key, section.getDouble(key));
        }
        return result;
    }

    /** 读取 Scalar 标量参数 */
    private static double getScalar(String riskClass, String paramName) {
        return getRoot().getJSONObject("Scalar").getJSONObject(riskClass).getDoubleValue(paramName);
    }

    /** 读取 Scalar 字符串参数 */
    private static String getScalarString(String riskClass, String paramName) {
        return getRoot().getJSONObject("Scalar").getJSONObject(riskClass).getString(paramName);
    }

    /** 读取 Scalar 整型参数 */
    private static int getScalarInt(String riskClass, String paramName) {
        return getRoot().getJSONObject("Scalar").getJSONObject(riskClass).getIntValue(paramName);
    }

    private static String[] getScalarCsvArray(String riskClass, String paramName) {
        String value = getScalarString(riskClass, paramName);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("未配置参数: Scalar." + riskClass + "." + paramName);
        }
        String[] parts = value.split(",");
        String[] result = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == null || parts[i].trim().isEmpty()) {
                throw new IllegalArgumentException("参数存在空值: Scalar." + riskClass + "." + paramName);
            }
            result[i] = parts[i].trim();
        }
        return result;
    }

    private static String[] getTenorCodes(String riskClass, String paramName) {
        String[] vertices = getScalarCsvArray(riskClass, paramName);
        String[] codes = new String[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            codes[i] = tenorVertexToCode(vertices[i]);
        }
        return codes;
    }

    private static String tenorVertexToCode(String vertex) {
        double years = Double.parseDouble(vertex);
        if (Math.abs(years) < 1e-12) {
            return "0D";
        }
        double months = years * 12.0;
        long roundedMonths = Math.round(months);
        if (Math.abs(months - roundedMonths) < 1e-9 && roundedMonths < 12) {
            return roundedMonths + "M";
        }
        long roundedYears = Math.round(years);
        if (Math.abs(years - roundedYears) < 1e-9) {
            return roundedYears + "Y";
        }
        if (Math.abs(months - roundedMonths) < 1e-9) {
            return roundedMonths + "M";
        }
        throw new IllegalArgumentException("无法转换标准期限为期限代码: " + vertex);
    }

    // ========================================
    // Weights（Delta 风险权重）
    // ========================================

    public static HashMap<String, Double> getGirrWeights() {
        return getMap("Weights", "GIRR");
    }

    public static HashMap<String, Double> getEQWeights() {
        return getMap("Weights", "EQ");
    }

    public static HashMap<String, Double> getCsrnsWeights() {
        return getMap("Weights", "CSR (non-sec)");
    }

    public static HashMap<String, Double> getCsrncWeights() {
        return getMap("Weights", "CSR (non-ctp)");
    }

    public static HashMap<String, Double> getCtpWeights() {
        return getMap("Weights", "CSR (ctp)");
    }

    public static HashMap<String, Double> getCmtyWeights() {
        return getMap("Weights", "CMTY");
    }

    // ========================================
    // 缩放因子 — GIRR
    // ========================================

    public static double getGirrGamma() {
        return getScalar("GIRR", "gamma");
    }

    public static double getGirrDeltaTenorLambda() {
        return getScalar("GIRR", "deltaTenorLambda");
    }

    public static double getGirrVegaTenorLambda() {
        return getScalar("GIRR", "vegaTenorLambda");
    }

    public static double getGirrRhoFloor() {
        return getScalar("GIRR", "rhoFloor");
    }

    public static double getGirrSubCurveFactor() {
        return getScalar("GIRR", "subCurveFactor");
    }

    public static double getGirrRwInflation() {
        return getScalar("GIRR", "rwInflation");
    }

    public static double getGirrRwBasis() {
        return getScalar("GIRR", "rwBasis");
    }

    /** 获取 GIRR 标准期限点数组 */
    public static double[] getGirrTenors() {
        String tenorStr = getScalarString("GIRR", "tenors");
        String[] parts = tenorStr.split(",");
        double[] tenors = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            tenors[i] = Double.parseDouble(parts[i].trim());
        }
        return tenors;
    }

    public static String[] getGirrTenorVertices() {
        return getScalarCsvArray("GIRR", "tenors");
    }

    public static String[] getGirrTenorCodes() {
        return getTenorCodes("GIRR", "tenors");
    }

    public static Set<String> getGirrScaledCurrencies() {
        String currencies = getScalarString("GIRR", "scaledCurrencies");
        Set<String> result = new HashSet<>();
        if (currencies == null || currencies.trim().isEmpty()) {
            throw new IllegalArgumentException("未配置 GIRR 可缩放币种集合: Scalar.GIRR.scaledCurrencies");
        }
        String[] parts = currencies.split(",");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                throw new IllegalArgumentException("GIRR 可缩放币种集合存在空值: Scalar.GIRR.scaledCurrencies");
            }
            result.add(part.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    public static boolean isGirrScaledCurrency(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return false;
        }
        return getGirrScaledCurrencies().contains(currency.trim().toUpperCase(Locale.ROOT));
    }

    public static double getGirrRwScaleFactor() {
        return getScalar("GIRR", "rwScaleFactor");
    }

    public static double getGirrStandardDeltaRiskWeight(double tenor) {
        HashMap<String, Double> girrWeights = getGirrWeights();
        if (girrWeights == null || girrWeights.isEmpty()) {
            throw new IllegalArgumentException("未配置 GIRR 风险权重: Weights.GIRR");
        }
        Double closestRw = null;
        double minDiff = Double.MAX_VALUE;
        for (Map.Entry<String, Double> entry : girrWeights.entrySet()) {
            double tenorKey = Double.parseDouble(entry.getKey());
            double diff = Math.abs(tenorKey - tenor);
            if (diff < minDiff) {
                minDiff = diff;
                closestRw = entry.getValue();
            }
        }
        if (closestRw == null) {
            throw new IllegalArgumentException("GIRR 风险权重为空: Weights.GIRR");
        }
        return closestRw;
    }

    public static double getGirrDeltaRiskWeight(String currency, double tenor, String riskType) {
        double rw;
        if (riskType != null) {
            String rt = riskType.toUpperCase(Locale.ROOT);
            if (rt.contains("INFLA")) {
                rw = getGirrRwInflation();
            } else if (rt.contains("BASIS")) {
                rw = getGirrRwBasis();
            } else {
                rw = getGirrStandardDeltaRiskWeight(tenor);
            }
        } else {
            rw = getGirrStandardDeltaRiskWeight(tenor);
        }
        return isGirrScaledCurrency(currency) ? rw * getGirrRwScaleFactor() : rw;
    }

    public static double getGirrCurvatureRw(String currency) {
        HashMap<String, Double> girrWeights = getGirrWeights();
        if (girrWeights == null || girrWeights.isEmpty()) {
            throw new IllegalArgumentException("未配置 GIRR 风险权重: Weights.GIRR");
        }
        Double maxDeltaRw = null;
        for (Double w : girrWeights.values()) {
            if (w == null) {
                continue;
            }
            if (maxDeltaRw == null || w > maxDeltaRw) {
                maxDeltaRw = w;
            }
        }
        if (maxDeltaRw == null) {
            throw new IllegalArgumentException("GIRR 风险权重为空: Weights.GIRR");
        }
        return isGirrScaledCurrency(currency) ? maxDeltaRw * getGirrRwScaleFactor() : maxDeltaRw;
    }

    // ========================================
    // 缩放因子 — FX
    // ========================================

    public static double getFxRhoBase() {
        return getScalar("FX", "rhoBase");
    }

    public static double getFxDeltaRw() {
        return getScalar("FX", "deltaRw");
    }

    /** FX 可缩放币种 RW = deltaRw × deltaRwMajorFactor */
    public static double getFxDeltaRwMajor() {
        return getScalar("FX", "deltaRw") * getScalar("FX", "deltaRwMajorFactor");
    }

    public static double getFxCurvatureScalar() {
        return getScalar("FX", "curvatureScalar");
    }

    public static Set<String> getFxScaledCurrencies() {
        String currencies = getScalarString("FX", "scaledCurrencies");
        Set<String> result = new HashSet<>();
        if (currencies == null || currencies.trim().isEmpty()) {
            throw new IllegalArgumentException("未配置 FX 可缩放币种集合: Scalar.FX.scaledCurrencies");
        }
        String[] parts = currencies.split(",");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                throw new IllegalArgumentException("FX 可缩放币种集合存在空值: Scalar.FX.scaledCurrencies");
            }
            result.add(part.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    public static boolean isFxScaledCurrency(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return false;
        }
        return getFxScaledCurrencies().contains(currency.trim().toUpperCase(Locale.ROOT));
    }

    public static double getFxRiskWeight(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            throw new IllegalArgumentException("FX 风险币种不能为空");
        }
        return isFxScaledCurrency(currency) ? getFxDeltaRwMajor() : getFxDeltaRw();
    }

    public static String[] getCsrTenorVertices() {
        return getScalarCsvArray("CSR", "tenors");
    }

    public static String[] getCsrTenorCodes() {
        return getTenorCodes("CSR", "tenors");
    }

    public static String[] getVegaTenorVertices() {
        return getScalarCsvArray("VEGA", "tenors");
    }

    public static String[] getVegaTenorCodes() {
        return getTenorCodes("VEGA", "tenors");
    }

    public static double getVegaShockRatio() {
        return getScalar("VEGA", "shockRatio");
    }

    public static int getVegaMatchToleranceDays() {
        return getScalarInt("VEGA", "matchToleranceDays");
    }

    // ========================================
    // 缩放因子 — EQ
    // ========================================

    public static double getEqRhoDefault() {
        return getScalar("EQ", "rhoDefault");
    }

    public static double getEqRhoBucket11() {
        return getScalar("EQ", "rhoBucket11");
    }

    public static double getEqRhoBucket12() {
        return getScalar("EQ", "rhoBucket12");
    }

    public static double getEqGammaDefault() {
        return getScalar("EQ", "gammaDefault");
    }

    public static int getEqBucketCount() {
        return getScalarInt("EQ", "bucketCount");
    }

    // ========================================
    // 缩放因子 — CSRNS
    // ========================================

    public static double getCsrnsRhoBase() {
        return getScalar("CSRNS", "rhoBase");
    }

    public static double getCsrnsRhoNameDiff() {
        return getScalar("CSRNS", "rhoNameDiff");
    }

    public static double getCsrnsRhoNameDiffBucket1718() {
        return getScalar("CSRNS", "rhoNameDiffBucket1718");
    }

    public static double getCsrnsRhoTenorDiff() {
        return getScalar("CSRNS", "rhoTenorDiff");
    }

    public static double getCsrnsRhoBasisDiff() {
        return getScalar("CSRNS", "rhoBasisDiff");
    }

    public static double getCsrnsGammaRatingDiff() {
        return getScalar("CSRNS", "gammaRatingDiff");
    }

    public static int getCsrnsBucketCount() {
        return getScalarInt("CSRNS", "bucketCount");
    }

    // ========================================
    // 缩放因子 — CSRNC
    // ========================================

    public static double getCsrncRhoBase() {
        return getScalar("CSRNC", "rhoBase");
    }

    public static double getCsrncGamma() {
        return getScalar("CSRNC", "gamma");
    }

    public static int getCsrncBucketCount() {
        return getScalarInt("CSRNC", "bucketCount");
    }

    // ========================================
    // 缩放因子 — CSR CTP
    // ========================================

    public static double getCtpRhoSameName() {
        return getScalar("CSRCTP", "rhoSameName");
    }

    public static double getCtpRhoBase() {
        return getScalar("CSRCTP", "rhoBase");
    }

    public static double getCtpRhoBasisDiff() {
        return getScalar("CSRCTP", "rhoBasisDiff");
    }

    public static double getCtpGamma() {
        return getScalar("CSRCTP", "gamma");
    }

    public static int getCtpBucketCount() {
        return getScalarInt("CSRCTP", "bucketCount");
    }

    // ========================================
    // 缩放因子 — CMTY
    // ========================================

    public static double getCmtyRhoTenorDiff() {
        return getScalar("CMTY", "rhoTenorDiff");
    }

    public static double getCmtyRhoBasisDiff() {
        return getScalar("CMTY", "rhoBasisDiff");
    }

    public static double getCmtyGamma() {
        return getScalar("CMTY", "gamma");
    }

    public static String[] getCmtyTenorVertices() {
        return getScalarCsvArray("CMTY", "tenors");
    }

    public static String[] getCmtyTenorCodes() {
        return getTenorCodes("CMTY", "tenors");
    }

    public static double getCmtyDeltaShockRatio() {
        return getScalar("CMTY", "deltaShockRatio");
    }

    public static int getCmtyBucketCount() {
        return getScalarInt("CMTY", "bucketCount");
    }

    public static double getDrcUnratedRiskWeight() {
        return getScalar("DRC", "unratedRiskWeight");
    }

    public static double getDrcSecnctpDefaultRiskWeight() {
        return getScalar("DRC", "secnctpDefaultRiskWeight");
    }

    public static HashMap<String, Double> getDrcNonSecRiskWeights() {
        JSONObject section = getRoot().getJSONObject("Scalar").getJSONObject("DRC").getJSONObject("nonSecRiskWeights");
        HashMap<String, Double> result = new HashMap<>();
        for (String key : section.keySet()) {
            result.put(key.trim().toUpperCase(Locale.ROOT), section.getDouble(key));
        }
        return result;
    }

    public static double getDrcNonSecRiskWeight(String rating) {
        if (rating == null || rating.trim().isEmpty() || "UNRATED".equalsIgnoreCase(rating.trim())) {
            return getDrcUnratedRiskWeight();
        }
        String normalizedRating = rating.trim().toUpperCase(Locale.ROOT);
        Double rw = getDrcNonSecRiskWeights().get(normalizedRating);
        if (rw == null) {
            LOGGER.warn("无法识别 DRC issuerRating={}, 按未评级风险权重处理", rating);
            return getDrcUnratedRiskWeight();
        }
        return rw;
    }

    // ========================================
    // 动态构建相关性矩阵
    // ========================================

    /**
     * 动态构建 GIRR 桶内期限相关性矩阵（MAR21.46）
     * ρ_kl = max(e^(-θ × |Tk-Tl| / min(Tk,Tl)), floor)
     *
     * @return HashMap，key 格式 "Tk,Tl"，value 为相关性值
     */
    public static HashMap<String, Double> buildGirrRhoMatrix() {
        double theta = getGirrDeltaTenorLambda();
        double floor = getGirrRhoFloor();
        double[] tenors = getGirrTenors();

        HashMap<String, Double> matrix = new HashMap<>();
        for (double tk : tenors) {
            for (double tl : tenors) {
                String key = tenorKey(tk) + "," + tenorKey(tl);
                double rho;
                if (tk == tl) {
                    rho = 1.0;
                } else {
                    rho = Math.max(
                            Math.exp(-theta * Math.abs(tk - tl) / Math.min(tk, tl)),
                            floor);
                }
                matrix.put(key, rho);
            }
        }
        return matrix;
    }

    /**
     * 动态构建 EQ 桶内相关性（MAR21.77）
     * 每个桶只有一个 ρ 值：默认 0.5，桶11=0.75，桶12=0.0
     *
     * @return HashMap，key 为桶编号字符串，value 为桶内相关性
     */
    public static HashMap<String, Double> buildEqRhoMap() {
        int count = getEqBucketCount();
        double rhoDefault = getEqRhoDefault();
        double rho11 = getEqRhoBucket11();
        double rho12 = getEqRhoBucket12();

        HashMap<String, Double> map = new HashMap<>();
        for (int b = 1; b <= count; b++) {
            double rho;
            if (b == 11)
                rho = rho11;
            else if (b == 12)
                rho = rho12;
            else
                rho = rhoDefault;
            map.put(String.valueOf(b), rho);
        }
        return map;
    }

    /**
     * 动态构建 EQ 跨桶相关性矩阵（MAR21.79）
     */
    public static HashMap<String, Double> buildEqGammaMatrix() {
        int count = getEqBucketCount();
        double gamma = getEqGammaDefault();
        HashMap<String, Double> matrix = new HashMap<>();
        for (int b = 1; b <= count; b++) {
            for (int c = 1; c <= count; c++) {
                double value;
                if (b == c) {
                    value = 1.0;
                } else if (b == 11 || c == 11) {
                    value = 0.0;
                } else if ((b == 12 && c == 13) || (b == 13 && c == 12)) {
                    value = 0.75;
                } else if (b == 12 || b == 13 || c == 12 || c == 13) {
                    value = 0.45;
                } else {
                    value = gamma;
                }
                matrix.put(b + "," + c, value);
            }
        }
        return matrix;
    }

    /**
     * 动态构建 CSRNS 跨桶相关性矩阵（MAR21.57）
     */
    public static HashMap<String, Double> buildCsrnsGammaMatrix() {
        String[] groups = {"1/9", "2/10", "3/11", "4/12", "5/13", "6/14", "7/15", "8", "16", "17", "18"};
        HashMap<String, String> bucketGroups = buildCsrnsBucketGroups(groups);
        JSONObject gammaSector = getRoot().getJSONObject("Scalar").getJSONObject("CSRNS").getJSONObject("gammaSector");
        double gammaRatingDiff = getCsrnsGammaRatingDiff();

        HashMap<String, Double> matrix = new HashMap<>();
        int bucketCount = getCsrnsBucketCount();
        for (int b = 1; b <= bucketCount; b++) {
            for (int c = 1; c <= bucketCount; c++) {
                String bucketB = String.valueOf(b);
                String bucketC = String.valueOf(c);
                double gamma;
                if (b == c) {
                    gamma = 1.0;
                } else {
                    String groupB = requireCsrnsBucketGroup(bucketGroups, bucketB);
                    String groupC = requireCsrnsBucketGroup(bucketGroups, bucketC);
                    double gammaSectorValue = groupB.equals(groupC) ? 1.0 : requireCsrnsGammaSector(gammaSector, groupB, groupC);
                    double gammaRating = isCsrnsRatingDifferent(b, c) ? gammaRatingDiff : 1.0;
                    gamma = gammaRating * gammaSectorValue;
                }
                matrix.put(bucketB + "," + bucketC, gamma);
            }
        }
        return matrix;
    }

    /**
     * CSRNS Delta 跨桶相关性矩阵
     */
    public static HashMap<String, Double> buildCsrnsDeltaGammaMatrix() {
        return buildCsrnsGammaMatrix();
    }

    /**
     * 动态构建 CSRNC 跨桶相关性矩阵
     */
    public static HashMap<String, Double> buildCsrncGammaMatrix() {
        return buildUniformGammaMatrix(getCsrncBucketCount(), getCsrncGamma());
    }

    /**
     * CSRNC Delta 跨桶相关性矩阵
     * 与普通桶相关性一致，bucket25 与其他桶跨桶相关性置 0（对角线保持 1）
     */
    public static HashMap<String, Double> buildCsrncDeltaGammaMatrix() {
        return buildUniformGammaMatrixWithIsolatedBucket(getCsrncBucketCount(), getCsrncGamma(), "25");
    }

    /**
     * 动态构建 CSR CTP 跨桶相关性矩阵（MAR21.64）
     */
    public static HashMap<String, Double> buildCtpGammaMatrix() {
        return buildUniformGammaMatrix(getCtpBucketCount(), getCtpGamma());
    }

    /**
     * CSRCTP Delta 跨桶相关性矩阵
     */
    public static HashMap<String, Double> buildCtpDeltaGammaMatrix() {
        return buildUniformGammaMatrix(getCtpBucketCount(), getCtpGamma());
    }

    /**
     * 动态构建 CMTY 桶内相关性（MAR21.82）
     * 每个桶只有一个 ρ_base 值
     */
    public static HashMap<String, Double> buildCmtyRhoMap() {
        int count = getCmtyBucketCount();
        JSONObject rhoByBucket = getRoot().getJSONObject("Scalar").getJSONObject("CMTY").getJSONObject("rhoByBucket");

        HashMap<String, Double> map = new HashMap<>();
        for (int b = 1; b <= count; b++) {
            String bucket = String.valueOf(b);
            Double rho = rhoByBucket.getDouble(bucket);
            if (rho == null) {
                throw new IllegalArgumentException("未配置 CMTY 桶内商品相关性: " + bucket);
            }
            map.put(bucket, rho);
        }
        return map;
    }

    /**
     * 动态构建 CMTY 跨桶相关性矩阵（MAR21.83）
     */
    public static HashMap<String, Double> buildCmtyGammaMatrix() {
        int count = getCmtyBucketCount();
        double gamma = getCmtyGamma();
        HashMap<String, Double> matrix = new HashMap<>();
        for (int b = 1; b <= count; b++) {
            for (int c = 1; c <= count; c++) {
                double value;
                if (b == c) {
                    value = 1.0;
                } else if (b == 11 || c == 11) {
                    value = 0.0;
                } else {
                    value = gamma;
                }
                matrix.put(b + "," + c, value);
            }
        }
        return matrix;
    }

    // ========================================
    // 内部辅助方法
    // ========================================

    private static HashMap<String, String> buildCsrnsBucketGroups(String[] groups) {
        HashMap<String, String> bucketGroups = new HashMap<>();
        for (String group : groups) {
            for (String bucket : group.split("/")) {
                bucketGroups.put(bucket, group);
            }
        }
        return bucketGroups;
    }

    private static String requireCsrnsBucketGroup(Map<String, String> bucketGroups, String bucket) {
        String group = bucketGroups.get(bucket);
        if (group == null) {
            throw new IllegalArgumentException("未配置 CSRNS bucket 分组: " + bucket);
        }
        return group;
    }

    private static double requireCsrnsGammaSector(JSONObject gammaSector, String groupB, String groupC) {
        Double value = readCsrnsGammaSector(gammaSector, groupB, groupC);
        if (value == null) {
            value = readCsrnsGammaSector(gammaSector, groupC, groupB);
        }
        if (value == null) {
            throw new IllegalArgumentException("未配置 CSRNS gammaSector: " + groupB + "," + groupC);
        }
        return value;
    }

    private static Double readCsrnsGammaSector(JSONObject gammaSector, String groupB, String groupC) {
        JSONObject row = gammaSector.getJSONObject(groupB);
        return row == null ? null : row.getDouble(groupC);
    }

    private static boolean isCsrnsRatingDifferent(int bucketB, int bucketC) {
        return bucketB >= 1 && bucketB <= 15
                && bucketC >= 1 && bucketC <= 15
                && isCsrnsInvestmentGrade(bucketB) != isCsrnsInvestmentGrade(bucketC);
    }

    private static boolean isCsrnsInvestmentGrade(int bucket) {
        return bucket >= 1 && bucket <= 8;
    }

    /**
     * 构建统一 γ 值的跨桶相关性矩阵
     * 对角线 = 1.0，其余 = gamma
     */
    private static HashMap<String, Double> buildUniformGammaMatrix(int bucketCount, double gamma) {
        HashMap<String, Double> matrix = new HashMap<>();
        for (int b = 1; b <= bucketCount; b++) {
            for (int c = 1; c <= bucketCount; c++) {
                matrix.put(b + "," + c, (b == c) ? 1.0 : gamma);
            }
        }
        return matrix;
    }

    /**
     * 构建带“隔离桶”的跨桶相关性矩阵
     * 对角线 = 1.0，普通桶间 = gamma，隔离桶与其他桶 = 0.0
     */
    private static HashMap<String, Double> buildUniformGammaMatrixWithIsolatedBucket(
            int bucketCount, double gamma, String isolatedBucket) {
        HashMap<String, Double> matrix = new HashMap<>();
        for (int b = 1; b <= bucketCount; b++) {
            for (int c = 1; c <= bucketCount; c++) {
                String bk = String.valueOf(b);
                String ck = String.valueOf(c);
                double value;
                if (b == c) {
                    value = 1.0;
                } else if (isolatedBucket.equals(bk) || isolatedBucket.equals(ck)) {
                    value = 0.0;
                } else {
                    value = gamma;
                }
                matrix.put(bk + "," + ck, value);
            }
        }
        return matrix;
    }

    /**
     * 将 tenor 数值转为字符串 key
     * 整数去掉小数点，非整数保留原值
     */
    private static String tenorKey(double tenor) {
        if (tenor == (long) tenor) {
            return String.valueOf((long) tenor);
        }
        return String.valueOf(tenor);
    }
}
