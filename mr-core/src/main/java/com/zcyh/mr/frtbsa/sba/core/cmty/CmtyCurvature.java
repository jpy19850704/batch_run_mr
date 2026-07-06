package com.zcyh.mr.frtbsa.sba.core.cmty;

import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getByScenario;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getDouble;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.putByScenario;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CMTY Curvature 资本计算逻辑
 * 商品风险 - Curvature敏感性
 */
public class CmtyCurvature {

    private static final String SENS_TYPE = FrtbConstants.SENS_CURVATURE;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_CMTY;
    private static final HashMap<String, Double> CMTY_RHO_MAP = FrtbParamsCache.buildCmtyRhoMap();
    private static final HashMap<String, Double> CMTY_GAMMA_MATRIX = FrtbParamsCache.buildCmtyGammaMatrix();

    public Map<String, Object> calculate(List<Map<String, Object>> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        // 1. 计算KL矩阵
        List<Map<String, Object>> klList = calculateKL(dataList);

        // 2. Bucket内 & Bucket间 聚合
        Map<String, List<Map<String, Object>>> aggAndBc = calculateAgg(klList, dataList);
        List<Map<String, Object>> aggList = aggAndBc.get("agg");
        List<Map<String, Object>> bcList = aggAndBc.get("bc");

        // 3. 计算最终资本
        Map<String, Object> cmtyc = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);

            double sumKbSq = aggList.stream()
                    .mapToDouble(e -> Math.pow(getByScenario(e, "Kb", scenario + scenario, 0.0), 2))
                    .sum();

            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();

            double est = Math.sqrt(Math.max(0.0, sumKbSq + sumRsltBc));

            cmtyc.put("capital_" + scenarioName, est);

            switch (scenario) {
                case "M":
                    capital_M = est;
                    break;
                case "H":
                    capital_H = est;
                    break;
                case "L":
                    capital_L = est;
                    break;
            }
        }

        double finalCapital = Math.max(Math.max(capital_M, capital_H), capital_L);
        cmtyc.put("capital", finalCapital);
        cmtyc.put("riskFactorClass", RISK_CLASS);
        cmtyc.put("sensType", SENS_TYPE);
        cmtyc.put("capital_normal", capital_M);
        cmtyc.put("capital_high", capital_H);
        cmtyc.put("capital_low", capital_L);

        // 4. (可选) 资本分解
        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, klList, aggList, bcList, cmtyc);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", klList);
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", cmtyc);
        result.put("decompRslt", decompRsltList);
        return result;
    }

    private List<Map<String, Object>> calculateKL(List<Map<String, Object>> dataList) {
        List<Map<String, Object>> dataList1 = new ArrayList<>(dataList);
        List<Map<String, Object>> klList = new ArrayList<>();

        for (Map<String, Object> k : dataList) {
            String bucketK = k.get("riskFactorBucket").toString();

            for (Map<String, Object> l : dataList1) {
                String bucketL = l.get("riskFactorBucket").toString();
                if (!bucketK.equals(bucketL))
                    continue;

                Map<String, Object> kl = new HashMap<>();
                kl.put("bucket", bucketK);
                kl.put("riskFactorId_K", k.get("riskFactorId"));
                kl.put("riskFactorId_L", l.get("riskFactorId"));
                kl.put("riskFactorVertex1_K", k.get("riskFactorVertex1"));
                kl.put("riskFactorVertex1_L", l.get("riskFactorVertex1"));
                kl.put("riskFactorVertex2_K", k.get("riskFactorVertex2"));
                kl.put("riskFactorVertex2_L", l.get("riskFactorVertex2"));
                kl.put("riskFactorType_K", k.get("riskFactorType"));
                kl.put("riskFactorType_L", l.get("riskFactorType"));

                double cvrK_up = getDouble(k, "CVR_up");
                double cvrK_down = getDouble(k, "CVR_down");
                double cvrL_up = getDouble(l, "CVR_up");
                double cvrL_down = getDouble(l, "CVR_down");

                kl.put("CVR_L_up", cvrL_up);
                kl.put("CVR_L_down", cvrL_down);

                // Psi 函数
                double psi_up = (cvrK_up < 0 && cvrL_up < 0) ? 0.0 : 1.0;
                double psi_down = (cvrK_down < 0 && cvrL_down < 0) ? 0.0 : 1.0;

                boolean isSelf = isSameRiskFactor(k, l);
                double currentBaseRho = lookupRho(k, l);

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double curvatureRho = isSelf
                            ? 1.0
                            : FrtbConstants.applyCurvatureScenarioStress(scenario, currentBaseRho);

                    putByScenario(kl, "rslt_kl_up", scenario, cvrK_up * cvrL_up * curvatureRho * psi_up);
                    putByScenario(kl, "rslt_kl_down", scenario, cvrK_down * cvrL_down * curvatureRho * psi_down);
                    putByScenario(kl, "rho", scenario, curvatureRho);

                    // 计算分解用的 rhol (用于 CVR 分解)
                    putByScenario(kl, "rhol_up", scenario, cvrL_up * curvatureRho * psi_up);
                    putByScenario(kl, "rhol_down", scenario, cvrL_down * curvatureRho * psi_down);
                }
                klList.add(kl);
            }
        }
        return klList;
    }

    private Map<String, List<Map<String, Object>>> calculateAgg(List<Map<String, Object>> klList,
            List<Map<String, Object>> dataList) {

        Map<String, List<Map<String, Object>>> klByBucket = klList.stream()
                .collect(Collectors.groupingBy(e -> e.get("bucket").toString()));
        List<Map<String, Object>> aggList = new ArrayList<>();

        Set<String> allBuckets = dataList.stream()
                .map(d -> d.get("riskFactorBucket").toString())
                .collect(Collectors.toSet());

        for (String bucket : allBuckets) {
            Map<String, Object> aggMap = new HashMap<>();
            aggMap.put("bucket", bucket);
            aggMap.put("riskFactorBucket", bucket);

            List<Map<String, Object>> bucketKl = klByBucket.getOrDefault(bucket, new ArrayList<>());

            for (String scenario : FrtbConstants.SCENARIOS) {
                double sumRsltKl_up = bucketKl.stream()
                        .mapToDouble(e -> getByScenario(e, "rslt_kl_up", scenario, 0.0)).sum();
                double sumRsltKl_down = bucketKl.stream()
                        .mapToDouble(e -> getByScenario(e, "rslt_kl_down", scenario, 0.0)).sum();

                double Kb_up = Math.sqrt(Math.max(sumRsltKl_up, 0.0));
                double Kb_down = Math.sqrt(Math.max(sumRsltKl_down, 0.0));

                double sumCvrUp = dataList.stream()
                        .filter(e -> bucket.equals(e.get("riskFactorBucket")))
                        .mapToDouble(e -> getDouble(e, "CVR_up"))
                        .sum();
                double sumCvrDown = dataList.stream()
                        .filter(e -> bucket.equals(e.get("riskFactorBucket")))
                        .mapToDouble(e -> getDouble(e, "CVR_down"))
                        .sum();

                boolean isUpDominant = FrtbConstants.selectCurvatureUpScenario(Kb_up, Kb_down, sumCvrUp, sumCvrDown);
                double Kb = isUpDominant ? Kb_up : Kb_down;

                aggMap.put("isUpDominant_" + scenario, isUpDominant);

                double Sb = isUpDominant ? sumCvrUp : sumCvrDown;

                putByScenario(aggMap, "Kb", scenario + scenario, Kb);
                putByScenario(aggMap, "Sbb", scenario, Sb);
            }
            aggList.add(aggMap);
        }

        List<Map<String, Object>> bcList = new ArrayList<>();

        for (Map<String, Object> b : aggList) {
            for (Map<String, Object> c : aggList) {
                Map<String, Object> bc = new HashMap<>();
                String bucketB = b.get("bucket").toString();
                String bucketC = c.get("bucket").toString();

                bc.put("bucket_b", bucketB);
                bc.put("bucket_c", bucketC);

                if (bucketB.equals(bucketC))
                    continue;

                double baseGamma = lookupGamma(bucketB, bucketC);

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double Sb_b = getByScenario(b, "Sbb", scenario, 0.0);
                    double Sb_c = getByScenario(c, "Sbb", scenario, 0.0);

                    double psi = (Sb_b < 0 && Sb_c < 0) ? 0.0 : 1.0;
                    double gamma = FrtbConstants.applyCurvatureScenarioStress(scenario, baseGamma);

                    double term = gamma * Sb_b * Sb_c * psi;
                    putByScenario(bc, "rslt_bc", scenario, term);

                    // 存储分解用的 gammac
                    double gammac = gamma * Sb_c * psi;
                    putByScenario(bc, "gammac", scenario, gammac);
                }
                bcList.add(bc);
            }
        }

        Map<String, List<Map<String, Object>>> mp = new HashMap<>();
        mp.put("agg", aggList);
        mp.put("bc", bcList);
        return mp;
    }

    private List<Map<String, Object>> decompose(List<Map<String, Object>> dataList,
            List<Map<String, Object>> klList,
            List<Map<String, Object>> aggList,
            List<Map<String, Object>> bcList,
            Map<String, Object> cmtyc) {

        List<Map<String, Object>> decompList = new ArrayList<>();

        // 桶聚合数据索引
        Map<String, Map<String, Object>> aggByBucket = aggList.stream()
                .collect(Collectors.toMap(
                        e -> e.get("bucket").toString(),
                        e -> e,
                        (a, b) -> a,
                        LinkedHashMap::new));

        for (Map<String, Object> trade : dataList) {
            Map<String, Object> res = new HashMap<>(trade);
            decompList.add(res);
        }

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double capitalTotal = getDouble(cmtyc, "capital_" + scenName);

            // 1. 汇总跨桶交叉项（含对角线 Kb²）
            Map<String, Double> crossTermSumMap = new HashMap<>();
            for (Map<String, Object> agg : aggList) {
                String bucket = agg.get("bucket").toString();
                double Kb = getByScenario(agg, "Kb", scenario + scenario, 0.0);
                crossTermSumMap.merge(bucket, Kb * Kb, Double::sum);
            }
            for (Map<String, Object> bc : bcList) {
                String bb = bc.get("bucket_b").toString();
                double val = getByScenario(bc, "rslt_bc", scenario, 0.0);
                crossTermSumMap.merge(bb, val, Double::sum);
            }

            // 2. 计算每个桶的单位贡献率
            Map<String, Double> unitContribMap = new HashMap<>();
            for (Map<String, Object> agg : aggList) {
                String bucket = agg.get("bucket").toString();
                double Sb = getByScenario(agg, "Sbb", scenario, 0.0);
                double bucketContrib = crossTermSumMap.getOrDefault(bucket, 0.0);
                double bucketVal = (capitalTotal > 1e-9) ? bucketContrib / capitalTotal : 0.0;
                double unitContrib = (Math.abs(Sb) > 1e-9) ? bucketVal / Sb : 0.0;
                unitContribMap.put(bucket, unitContrib);
            }

            // 3. 分配到每个头寸
            for (Map<String, Object> res : decompList) {
                String bucket = res.get("riskFactorBucket").toString();
                Map<String, Object> bAgg = aggByBucket.get(bucket);
                boolean isUp = bAgg != null && Boolean.TRUE.equals(bAgg.get("isUpDominant_" + scenario));

                double cvrUp = getDouble(res, "CVR_up");
                double cvrDown = getDouble(res, "CVR_down");
                double activeCvr = isUp ? cvrUp : cvrDown;
                double unitContrib = unitContribMap.getOrDefault(bucket, 0.0);

                // Euler 分配：pder 包含 activeCvr 因子，allocated = pder × capitalTotal
                double pder = (capitalTotal > 1e-9) ? activeCvr * unitContrib / capitalTotal : 0.0;
                double allocated = pder * capitalTotal;

                res.put("pder_" + scenName, pder);
                res.put("activeCvr_" + scenName, activeCvr);
                res.put("activeCvrSide_" + scenName, isUp ? "UP" : "DOWN");
                res.put("allocatedCapital_" + scenName, allocated);
            }
        }

        return decompList;
    }

    // 辅助方法
    private Map<String, Map<String, Double>> groupAndSum(List<Map<String, Object>> list, String keyName) {
        Map<String, Map<String, Double>> sums = new HashMap<>();
        for (Map<String, Object> map : list) {
            String id = map.get("riskFactorId_K").toString();
            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(map, keyName, scenario, 0.0);
                if (val != 0) {
                    sums.computeIfAbsent(id, k -> new HashMap<>()).merge(scenario, val, Double::sum);
                }
            }
        }
        return sums;
    }

    private Map<String, Map<String, Double>> groupAndSumGammac(List<Map<String, Object>> bcList) {
        Map<String, Map<String, Double>> sums = new HashMap<>();
        for (Map<String, Object> bc : bcList) {
            String b = bc.get("bucket_b").toString();
            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(bc, "gammac", scenario, 0.0);
                if (val != 0) {
                    sums.computeIfAbsent(b, k -> new HashMap<>()).merge(scenario, val, Double::sum);
                }
            }
        }
        return sums;
    }

    private double lookupRho(Map<String, Object> k, Map<String, Object> l) {
        if (isSameRiskFactor(k, l)) {
            return 1.0;
        }
        String bucket = k.get("riskFactorBucket").toString();
        Double commodityRho = Objects.equals(k.get("riskFactorId"), l.get("riskFactorId"))
                ? 1.0
                : CMTY_RHO_MAP.get(bucket);
        if (commodityRho == null) {
            throw new IllegalArgumentException("未配置 CMTY 桶内商品相关性: " + bucket);
        }
        double tenorRho = Objects.equals(k.get("riskFactorVertex1"), l.get("riskFactorVertex1"))
                ? 1.0
                : FrtbParamsCache.getCmtyRhoTenorDiff();
        double basisRho = isSameBasis(k, l) ? 1.0 : FrtbParamsCache.getCmtyRhoBasisDiff();
        return commodityRho * tenorRho * basisRho;
    }

    private double lookupGamma(String bucketB, String bucketC) {
        Double gamma = CMTY_GAMMA_MATRIX.get(bucketB + "," + bucketC);
        if (gamma == null) {
            throw new IllegalArgumentException("未配置 CMTY 跨桶相关性: " + bucketB + "," + bucketC);
        }
        return gamma;
    }

    private boolean isSameRiskFactor(Map<String, Object> k, Map<String, Object> l) {
        return Objects.equals(k.get("riskFactorId"), l.get("riskFactorId"))
                && Objects.equals(k.get("riskFactorVertex1"), l.get("riskFactorVertex1"))
                && Objects.equals(k.get("riskFactorVertex2"), l.get("riskFactorVertex2"))
                && Objects.equals(k.get("riskFactorType"), l.get("riskFactorType"));
    }

    private boolean isSameBasis(Map<String, Object> k, Map<String, Object> l) {
        return Objects.equals(k.get("riskFactorVertex2"), l.get("riskFactorVertex2"))
                && Objects.equals(k.get("riskFactorType"), l.get("riskFactorType"));
    }



}
