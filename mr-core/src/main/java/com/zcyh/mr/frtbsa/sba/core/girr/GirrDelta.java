package com.zcyh.mr.frtbsa.sba.core.girr;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.SbaAggregationUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GIRR Delta 资本计算
 * 利率风险 - Delta敏感性
 */
public class GirrDelta {

    /** GIRR 利率期限相关性矩阵（从 Cache 参数构建，与 Scalar 配置一致） */
    private static final HashMap<String, Double> GIRR_RHO_MATRIX = FrtbParamsCache.buildGirrRhoMatrix();

    /** GIRR 标准监管期限点集合（MAR21.40） */
    private static final java.util.Set<Double> VALID_TENORS = buildValidTenorSet();

    private static java.util.Set<Double> buildValidTenorSet() {
        double[] tenors = FrtbParamsCache.getGirrTenors();
        java.util.Set<Double> set = new java.util.HashSet<>();
        for (double t : tenors) {
            set.add(t);
        }
        return set;
    }

    public Map<String, Object> calculate(List<Map<String, Object>> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        // 1. 按 CCY (riskFactorBucket) 分组计算 KL 矩阵
        Map<String, List<Map<String, Object>>> groupedByCcy = dataList.stream()
                .collect(Collectors.groupingBy(e -> e.get("riskFactorBucket").toString()));

        List<Map<String, Object>> GIRR_delta_kl = new ArrayList<>();
        for (String ccy : groupedByCcy.keySet()) {
            GIRR_delta_kl.addAll(calculateKL(groupedByCcy.get(ccy), ccy));
        }

        // 2. Bucket内聚合 + Bucket间聚合
        Map<String, List<Map<String, Object>>> aggAndBc = calculateAgg(GIRR_delta_kl, dataList);
        List<Map<String, Object>> aggList = aggAndBc.get("GIRR_delta_agg");
        List<Map<String, Object>> bcList = aggAndBc.get("GIRR_delta_bc");

        // 3. 计算资本（循环处理3个场景）
        double capital_M = 0, capital_H = 0, capital_L = 0;
        double est_M = 0, est_H = 0, est_L = 0;

        // 存储中间结果用于分解
        Map<String, Object> girrd = new HashMap<>();

        for (String scenario : FrtbConstants.SCENARIOS) {
            double est = SbaAggregationUtils.rawTotal(bcList, scenario);
            double capital = SbaAggregationUtils.calculateDeltaVegaCapital(bcList, scenario);

            // 3.6 存储结果
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            girrd.put("capital_" + scenarioName, capital);
            girrd.put("est_" + scenarioName, est);

            switch (scenario) {
                case "M":
                    capital_M = capital;
                    est_M = est;
                    break;
                case "H":
                    capital_H = capital;
                    est_H = est;
                    break;
                case "L":
                    capital_L = capital;
                    est_L = est;
                    break;
            }
        }

        girrd.put("riskFactorClass", "GIRR");
        girrd.put("sensType", "DELTA");
        girrd.put("capital_normal", capital_M);
        girrd.put("capital_high", capital_H);
        girrd.put("capital_low", capital_L);
        // 最终资本取最大
        double finalCapital = Math.max(Math.max(capital_M, capital_H), capital_L);
        girrd.put("capital", finalCapital);

        // 4. 分解 (可选)
        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, GIRR_delta_kl, aggList, bcList,
                    girrd, est_M, est_H, est_L);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", GIRR_delta_kl);
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", girrd);
        result.put("decompRslt", decompRsltList);
        return result;
    }

    /**
     * 计算 GIRR KL 矩阵 (桶内风险因子两两配对)
     */
    private List<Map<String, Object>> calculateKL(List<Map<String, Object>> dataList, String bucket) {
        // 深拷贝一份用于双重循环，避免引用问题
        List<Map<String, Object>> dataList1 = new ArrayList<>(dataList);
        List<Map<String, Object>> returnList = new ArrayList<>();

        for (Map<String, Object> map : dataList) {
            for (Map<String, Object> map1 : dataList1) {
                Map<String, Object> klMap = new HashMap<>();

                // 提取数据
                String riskFactorId_K = map.get("riskFactorId").toString();
                String riskFactorVertex1_K = map.get("riskFactorVertex1") == null ? ""
                        : map.get("riskFactorVertex1").toString();
                double ws_K = getDouble(map, "ws");

                String riskFactorId_L = map1.get("riskFactorId").toString();
                String riskFactorVertex1_L = map1.get("riskFactorVertex1") == null ? ""
                        : map1.get("riskFactorVertex1").toString();
                double ws_L = getDouble(map1, "ws");

                // 存储基础信息
                klMap.put("riskFactorId_K", riskFactorId_K);
                klMap.put("riskFactorVertex1_K", riskFactorVertex1_K);
                klMap.put("ws_K", ws_K);
                klMap.put("riskFactorId_L", riskFactorId_L);
                klMap.put("riskFactorVertex1_L", riskFactorVertex1_L);
                klMap.put("ws_L", ws_L);
                klMap.put("bucket", bucket);
                klMap.put("riskFactorBucket", bucket);
                klMap.put("riskFactorClass", "GIRR");

                // 1. 计算基础相关性（包含基差风险、通胀、下限处理）
                String curveName_K = getCurveName(map);
                String curveName_L = getCurveName(map1);
                String type_K = getRiskFactorType(map);
                String type_L = getRiskFactorType(map1);

                double VERTEX_1_K = parseTenor(riskFactorVertex1_K);
                double VERTEX_1_L = parseTenor(riskFactorVertex1_L);

                double baseRho = calculateBaseRho(VERTEX_1_K, VERTEX_1_L, curveName_K, curveName_L, type_K, type_L);

                boolean isSameVertex = riskFactorId_K.equals(riskFactorId_L)
                        && riskFactorVertex1_K.equals(riskFactorVertex1_L)
                        && curveName_K.equals(curveName_L)
                        && type_K.equals(type_L);

                // 2. 循环处理3个场景（统一规则）
                for (String scenario : FrtbConstants.SCENARIOS) {
                    // 2.1 应用场景调整（使用常量类通用方法）
                    double rho_kl = FrtbConstants.applyScenarioStress(scenario, baseRho);

                    // 2.2 计算 rslt_kl（显式函数）
                    double rslt_kl = calculateRsltKl(ws_K, ws_L, rho_kl);

                    // 2.3 计算 rhol（显式函数）- 仅用于分解
                    // 注意：这里的 rhol 是 K 对 L 的贡献
                    double rhol = calculateRhol(ws_L, rho_kl, isSameVertex);

                    // 2.4 存储结果锛堟樉鎬ey锛?
                    putByScenario(klMap, "rslt_kl", scenario, rslt_kl);
                    putByScenario(klMap, "rhol", scenario, rhol);
                    putByScenario(klMap, "rho", scenario, rho_kl);
                }

                returnList.add(klMap);
            }

        }
        return returnList;
    }

    private Map<String, List<Map<String, Object>>> calculateAgg(List<Map<String, Object>> klList,
            List<Map<String, Object>> dataList) {

        // Sb: 每个bucket的加权敏感性总和
        Map<String, Double> aggMap = new HashMap<>();
        for (Map<String, Object> map : dataList) {
            String bucket = map.get("riskFactorBucket").toString();
            aggMap.merge(bucket, getDouble(map, "ws"), Double::sum);
        }

        List<Map<String, Object>> aggList = new ArrayList<>();
        double GIRR_Cross_Mlt = FrtbParamsCache.getGirrGamma(); // 0.5

        // Bucket内聚合
        for (String bucket : aggMap.keySet()) {
            Map<String, Object> aggTotal = new HashMap<>();
            aggTotal.put("riskFactorBucket", bucket);
            aggTotal.put("bucket", bucket);
            aggTotal.put("riskFactorClass", "GIRR");
            aggTotal.put("Sb", aggMap.get(bucket));

            // 循环处理3个场景
            for (String scenario : FrtbConstants.SCENARIOS) {
                // 1. 累加 rslt_kl
                double sumRsltKl = klList.stream()
                        .filter(kl -> bucket.equals(kl.get("bucket")))
                        .mapToDouble(kl -> getByScenario(kl, "rslt_kl", scenario, 0.0))
                        .sum();

                // 2. 计算 Kb（显式函数）
                double Kb = calculateKb(sumRsltKl);

                // 3. 计算 Sbb（显式函数）
                double Sb = aggMap.get(bucket);
                double Sbb = calculateSbb(Kb, Sb);

                // 4. 存储结果
                putByScenario(aggTotal, "Kb", scenario, Kb);
                putByScenario(aggTotal, "Sbb", scenario, Sbb);
                putByScenario(aggTotal, "Kb", scenario + scenario, Kb); // KbMM/HH/LL
            }

            aggList.add(aggTotal);
        }

        // Bucket间聚合
        List<Map<String, Object>> bcList = new ArrayList<>();
        // 转为List以便双重循环
        List<String> bucketKeys = new ArrayList<>(aggMap.keySet());

        for (String key : bucketKeys) {
            for (String bucket : bucketKeys) {
                Map<String, Object> bcMap = new HashMap<>();
                bcMap.put("Bucket_b", key);
                bcMap.put("Bucket_c", bucket);
                bcMap.put("riskFactorClass", "GIRR");

                double WS_b = aggMap.get(key);
                double WS_c = aggMap.get(bucket);

                // 1. 计算跨桶相关性 Gamma
                double baseGamma = GIRR_Cross_Mlt;
                if (key.equals(bucket)) {
                    baseGamma = 1.0;
                }

                // 循环处理3个场景
                for (String scenario : FrtbConstants.SCENARIOS) {
                    // 2.1 应用场景调整
                    double Gamma_bc;
                    if (key.equals(bucket)) {
                        Gamma_bc = 1.0;
                    } else {
                        // 2.1 应用场景调整
                        Gamma_bc = FrtbConstants.applyScenarioStress(scenario, baseGamma);
                    }

                    double rslt_bc = 0, gammac = 0, rslt_bcc = 0;

                    if (key.equals(bucket)) {
                        Map<String, Object> bAgg = aggList.stream()
                                .filter(e -> key.equals(e.get("bucket")))
                                .findFirst().orElse(null);
                        if (bAgg != null) {
                            double Kb = getByScenario(bAgg, "Kb", scenario, 0.0);
                            rslt_bc = Kb * Kb;
                            rslt_bcc = rslt_bc;
                        }
                    } else {
                        // 2.2 计算 rslt_bc
                        rslt_bc = calculateRsltBc(WS_b, WS_c, Gamma_bc);

                        // 2.3 计算 gammac
                        gammac = calculateGammac(WS_c, Gamma_bc);

                        // 2.4 计算 rslt_bcc
                        Map<String, Object> bAgg = aggList.stream()
                                .filter(e -> key.equals(e.get("bucket")))
                                .findFirst().orElse(null);
                        Map<String, Object> cAgg = aggList.stream()
                                .filter(e -> bucket.equals(e.get("bucket")))
                                .findFirst().orElse(null);

                        if (bAgg != null && cAgg != null) {
                            double Sbb_b = getByScenario(bAgg, "Sbb", scenario, 0.0);
                            double Sbb_c = getByScenario(cAgg, "Sbb", scenario, 0.0);
                            rslt_bcc = calculateRsltBcc(Sbb_b, Sbb_c, Gamma_bc);
                        }
                    }

                    // 2.5 存储结果
                    putByScenario(bcMap, "Gamma_bc", scenario, Gamma_bc);
                    putByScenario(bcMap, "rslt_bc", scenario, rslt_bc);
                    putByScenario(bcMap, "gammac", scenario, gammac);
                    putByScenario(bcMap, "rslt_bcc", scenario, rslt_bcc);
                }
                bcList.add(bcMap);
            }
        }
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        result.put("GIRR_delta_agg", aggList);
        result.put("GIRR_delta_bc", bcList);
        return result;
    }

    // ==================== 分解计算 ====================

    private List<Map<String, Object>> decompose(List<Map<String, Object>> dataList,
            List<Map<String, Object>> klList,
            List<Map<String, Object>> aggList,
            List<Map<String, Object>> bcList,
            Map<String, Object> girrd,
            double est_M, double est_H, double est_L) {

        // 获取中间计算结果
        List<Map<String, Object>> rhol2List = groupAndSumRhol(klList);
        List<Map<String, Object>> gammac3List = groupAndSumGammac(bcList);

        List<Map<String, Object>> decompRsltList = new ArrayList<>();

        for (Map<String, Object> data : dataList) {
            // 深拷贝或新建Map以避免修改原数据
            Map<String, Object> decompData = new HashMap<>(data);

            String riskFactorId = data.get("riskFactorId").toString();
            String riskFactorVertex1 = data.get("riskFactorVertex1") == null ? ""
                    : data.get("riskFactorVertex1").toString();
            String riskFactorBucket = data.get("riskFactorBucket").toString();
            double ws = getDouble(data, "ws");

            // 查找对应数据
            // Rhol 键：ID + Vertex + Bucket
            Map<String, Object> rholData = rhol2List.stream()
                    .filter(e -> riskFactorId.equals(e.get("riskFactorId_K"))
                            && riskFactorVertex1.equals(e.get("riskFactorVertex1_K"))
                            && riskFactorBucket.equals(e.get("riskFactorBucket")))
                    .findFirst().orElse(null);

            // Gammac 键：Bucket
            Map<String, Object> gammacData = gammac3List.stream()
                    .filter(e -> riskFactorBucket.equals(e.get("Bucket_b")))
                    .findFirst().orElse(null);

            // AggData 键：Bucket
            Map<String, Object> aggData = aggList.stream()
                    .filter(e -> e.get("bucket").equals(riskFactorBucket))
                    .findFirst().orElse(null);

            // 循环计算三个场景的pder
            for (String scenario : FrtbConstants.SCENARIOS) {
                double rhol = getByScenario(rholData, "rhol", scenario, 0.0);
                double gammac = getByScenario(gammacData, "gammac", scenario, 0.0);
                double Kb = getByScenario(aggData, "Kb", scenario, 0.0);
                double Sbb = getByScenario(aggData, "Sbb", scenario, 0.0);

                // 获取 Total Capital (分母)
                String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);
                double capitalTotal = getDouble(girrd, "capital_" + scenarioName);

                // 获取 est
                double est = 0;
                if ("M".equals(scenario))
                    est = est_M;
                else if ("H".equals(scenario))
                    est = est_H;
                else if ("L".equals(scenario))
                    est = est_L;

                // 显式调用pder计算函数
                double pderValue = calculatePder(est, Kb, Sbb, ws, rhol, gammac, capitalTotal);
                if (Double.isNaN(pderValue))
                    pderValue = 0;

                decompData.put("pder_" + scenarioName, pderValue);

                // Euler 分配：allocated = ws × ∂Capital/∂ws
                double allocatedCapital = pderValue * ws;
                decompData.put("allocatedCapital_" + scenarioName, allocatedCapital);
            }

            decompRsltList.add(decompData);
        }
        return decompRsltList;
    }

    // ==================== 显式计算函数====================

    /**
     * 计算 GIRR 基础相关性系数（MAR21.46）
     * 利率期限相关性从 GIRR_RHO_MATRIX 查表（与 Cache buildGirrRhoMatrix 一致）
     * 通胀/基差/曲线间折扣等业务场景在本方法中处理
     */
    private double calculateBaseRho(double vertex_K, double vertex_L, String curve_K, String curve_L, String type_K,
            String type_L) {
        // 1. 基差风险：一个是基差、另一个不是，相关性 = 0
        boolean isBasisK = isBasis(type_K);
        boolean isBasisL = isBasis(type_L);
        if (isBasisK != isBasisL) {
            return 0.0;
        }

        // 2. 通胀因子：通胀 vs 名义利率，相关性 = rhoFloor
        boolean isInfK = isInflation(type_K);
        boolean isInfL = isInflation(type_L);
        if (isInfK != isInfL) {
            return FrtbParamsCache.getGirrRhoFloor();
        }

        // 3. 利率期限相关性：从 Cache 矩阵查表
        double rho_time = lookupTenorRho(vertex_K, vertex_L);

        // 4. 不同曲线间基差折扣
        if (!curve_K.equals(curve_L)) {
            return rho_time * FrtbParamsCache.getGirrSubCurveFactor();
        }

        return rho_time;
    }

    /**
     * 从 GIRR_RHO_MATRIX 查表获取利率期限相关性
     * 通胀/基差等无期限因子（tenor=0）直接返回 1.0
     */
    private double lookupTenorRho(double tenorK, double tenorL) {
        if (tenorK == 0 || tenorL == 0) {
            return 1.0;
        }
        String key = tenorKey(tenorK) + "," + tenorKey(tenorL);
        Double rho = GIRR_RHO_MATRIX.get(key);
        if (rho == null) {
            throw new IllegalArgumentException(
                    "GIRR 期限相关性查找失败: (" + tenorK + ", " + tenorL + ") 不在标准期限列表中");
        }
        return rho;
    }

    /**
     * tenor 数值转字符串 key（与 FrtbParamsCache.tenorKey 一致）
     */
    private static String tenorKey(double tenor) {
        if (tenor == (long) tenor) {
            return String.valueOf((long) tenor);
        }
        return String.valueOf(tenor);
    }

    private double calculateRsltKl(double ws_K, double ws_L, double rho_kl) {
        return rho_kl * ws_K * ws_L;
    }

    private double calculateRhol(double ws_L, double rho_kl, boolean isSameVertex) {
        return isSameVertex ? 0.0 : ws_L * rho_kl;
    }

    private double calculateKb(double sumRsltKl) {
        return Math.sqrt(Math.max(0, sumRsltKl));
    }

    private double calculateSbb(double Kb, double Sb) {
        return Math.max(Math.min(Kb, Sb), -Kb);
    }

    private double calculateRsltBc(double WS_b, double WS_c, double Gamma_bc) {
        return WS_b * WS_c * Gamma_bc;
    }

    private double calculateGammac(double WS_c, double Gamma_bc) {
        return WS_c * Gamma_bc;
    }

    private double calculateRsltBcc(double Sbb_b, double Sbb_c, double Gamma_bc) {
        return Sbb_b * Sbb_c * Gamma_bc;
    }

    private double calculateCapitalPositive(double est) {
        return Math.sqrt(Math.max(0, est));
    }

    private double calculateCapitalNegative(double sumKb, double sumRsltBcc) {
        return Math.sqrt(Math.max(0, sumKb + sumRsltBcc));
    }

    /**
     * 计算pder（偏导数/贡献率）
     */
    private double calculatePder(double est, double Kb, double Sbb,
            double ws, double rhol, double gammac, double capital) {
        // 如果总资本为0，贡献为0
        if (capital == 0)
            return 0;

        // 情况 1：Est >= 0
        if (est >= 0) {
            if (Kb > 0)
                return (ws + rhol + gammac) / capital;
            else // Kb=0
                return gammac / capital;
        }

        // 情况 2：Est < 0
        // 判断 Sbb == Kb 或 Sbb == -Kb (使用 double 比较)
        boolean equalsKb = Math.abs(Sbb - Kb) < 0.000001;
        boolean equalsMinusKb = Math.abs(Sbb - (-Kb)) < 0.000001;

        if (est < 0 && Kb > 0) {
            if (equalsKb)
                return ((ws + rhol) * (1 + 1 / Kb * gammac)) / capital;
            if (equalsMinusKb)
                return ((ws + rhol) * (1 - 1 / Kb * gammac)) / capital;

            if (!equalsKb && !equalsMinusKb)
                return (ws + rhol + gammac) / capital;
        }

        return 0;
    }

    // ==================== 工具方法 ====================

    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value instanceof Number) ? ((Number) value).doubleValue() : 0.0;
    }

    private double getByScenario(Map<String, Object> map, String key, String scenario, double defaultValue) {
        if (map == null)
            return defaultValue;
        return getDouble(map, key + "_" + scenario);
    }

    private void putByScenario(Map<String, Object> map, String key, String scenario, double value) {
        map.put(key + "_" + scenario, value);
    }

    private double parseTenor(String tenor) {
        if (tenor == null || tenor.isEmpty())
            return 0.0;
        try {
            String s = tenor.trim().toUpperCase();
            double value;
            if (s.endsWith("Y"))
                value = Double.parseDouble(s.replace("Y", ""));
            else if (s.endsWith("M"))
                value = Double.parseDouble(s.replace("M", "")) / 12.0;
            else
                value = Double.parseDouble(s);

            // 校验期限是否在监管标准期限范围内（MAR21.40）
            if (value != 0.0 && !VALID_TENORS.contains(value)) {
                throw new IllegalArgumentException(
                        "GIRR tenor '" + tenor + "' (" + value + "Y) 不在标准期限列表中 " + VALID_TENORS);
            }
            return value;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String getCurveName(Map<String, Object> map) {
        if (map == null || !map.containsKey("curveName") || map.get("curveName") == null
                || map.get("curveName").toString().trim().isEmpty()) {
            throw new IllegalArgumentException("GIRR Delta 缺少 curveName");
        }
        return map.get("curveName").toString();
    }

    private String getRiskFactorType(Map<String, Object> map) {
        if (map.containsKey("riskFactorType")) {
            return map.get("riskFactorType").toString();
        }
        return "";
    }

    private boolean isInflation(String type) {
        return type != null && type.toUpperCase().contains("INFLA");
    }

    private boolean isBasis(String type) {
        return type != null && type.toUpperCase().contains("BASIS");
    }

    /**
     * 按 riskFactorId_K + Vertex1_K + Bucket 聚合 rhol
     */
    private List<Map<String, Object>> groupAndSumRhol(List<Map<String, Object>> klList) {
        Map<String, Map<String, Object>> grouped = new HashMap<>();
        for (Map<String, Object> kl : klList) {
            // Key: ID + Vertex + Bucket (K绔?
            String key = kl.get("riskFactorId_K") + "@" + kl.get("riskFactorVertex1_K") + "@" + kl.get("bucket");

            grouped.computeIfAbsent(key, k -> {
                Map<String, Object> newMap = new HashMap<>();
                newMap.put("riskFactorId_K", kl.get("riskFactorId_K"));
                newMap.put("riskFactorVertex1_K", kl.get("riskFactorVertex1_K"));
                newMap.put("riskFactorBucket", kl.get("bucket"));
                return newMap;
            });

            Map<String, Object> target = grouped.get(key);
            for (String scenario : FrtbConstants.SCENARIOS) {
                double current = getByScenario(kl, "rhol", scenario, 0.0);
                double exists = getByScenario(target, "rhol", scenario, 0.0);
                putByScenario(target, "rhol", scenario, exists + current);
            }
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 按 Bucket_b 聚合 gammac
     */
    private List<Map<String, Object>> groupAndSumGammac(List<Map<String, Object>> bcList) {
        Map<String, Map<String, Object>> grouped = new HashMap<>();
        for (Map<String, Object> bc : bcList) {
            String key = bc.get("Bucket_b").toString();

            grouped.computeIfAbsent(key, k -> {
                Map<String, Object> newMap = new HashMap<>();
                newMap.put("Bucket_b", key);
                return newMap;
            });

            Map<String, Object> target = grouped.get(key);
            for (String scenario : FrtbConstants.SCENARIOS) {
                double current = getByScenario(bc, "gammac", scenario, 0.0);
                double exists = getByScenario(target, "gammac", scenario, 0.0);
                putByScenario(target, "gammac", scenario, exists + current);
            }
        }
        return new ArrayList<>(grouped.values());
    }
}
