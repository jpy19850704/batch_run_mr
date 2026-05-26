package com.zcyh.mr.frtbsa.sba.core.cmty;

import com.zcyh.mr.frtbsa.sba.common.CmtyRiskFactorId;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.SbaAggregationUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CMTY Delta 资本计算逻辑
 * 商品风险 - Delta敏感性
 */
public class CmtyDelta {

    private static final String SENS_TYPE = FrtbConstants.SENS_DELTA;
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
        Map<String, Object> cmtyd = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);

            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();

            double est = SbaAggregationUtils.calculateDeltaVegaCapital(bcList, scenario);

            cmtyd.put("capital_" + scenarioName, est);

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
        cmtyd.put("capital", finalCapital);
        cmtyd.put("riskFactorClass", RISK_CLASS);
        cmtyd.put("sensType", SENS_TYPE);
        cmtyd.put("capital_normal", capital_M);
        cmtyd.put("capital_high", capital_H);
        cmtyd.put("capital_low", capital_L);

        // 4. (可选) 资本分解
        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, klList, aggList, bcList, cmtyd);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", klList);
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", cmtyd);
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

                double wsK = getDouble(k, "ws");
                double wsL = getDouble(l, "ws");
                kl.put("ws_L", wsL);

                boolean isSelf = isSameRiskFactor(k, l);
                double currentBaseRho = lookupRho(k, l);

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double rho = FrtbConstants.applyScenarioStress(scenario, currentBaseRho);
                    if (isSelf)
                        rho = 1.0;

                    putByScenario(kl, "rslt_kl", scenario, wsK * wsL * rho);
                    putByScenario(kl, "rho", scenario, rho);

                    double rhol = isSelf ? 0.0 : wsL * rho;
                    putByScenario(kl, "rhol", scenario, rhol);
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

            // Sb 场景无关，循环外计算一次
            double Sb = dataList.stream()
                    .filter(e -> bucket.equals(e.get("riskFactorBucket")))
                    .mapToDouble(e -> getDouble(e, "ws"))
                    .sum();

            for (String scenario : FrtbConstants.SCENARIOS) {
                double sumRsltKl = bucketKl.stream()
                        .mapToDouble(e -> getByScenario(e, "rslt_kl", scenario, 0.0)).sum();

                double Kb = Math.sqrt(Math.max(sumRsltKl, 0.0));

                putByScenario(aggMap, "Kb", scenario + scenario, Kb);
                putByScenario(aggMap, "Sb", scenario, Sb);
                putByScenario(aggMap, "Sbb", scenario, SbaAggregationUtils.cappedSb(Sb, Kb));
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

                double baseGamma = lookupGamma(bucketB, bucketC);

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double rslt_bc_val;
                    double rslt_bcc_val;
                    double gamma = 1.0;
                    double gammac = 0.0;

                    if (bucketB.equals(bucketC)) {
                        double Kb = getByScenario(b, "Kb", scenario + scenario, 0.0);
                        rslt_bc_val = Kb * Kb;
                        rslt_bcc_val = rslt_bc_val;
                        gamma = 1.0;
                    } else {
                        double Sb_b = getByScenario(b, "Sb", scenario, 0.0);
                        double Sb_c = getByScenario(c, "Sb", scenario, 0.0);
                        double Sbb_b = getByScenario(b, "Sbb", scenario, 0.0);
                        double Sbb_c = getByScenario(c, "Sbb", scenario, 0.0);
                        gamma = FrtbConstants.applyScenarioStress(scenario, baseGamma);
                        rslt_bc_val = Sb_b * Sb_c * gamma;
                        rslt_bcc_val = Sbb_b * Sbb_c * gamma;

                        gammac = Sb_c * gamma;
                    }
                    putByScenario(bc, "rslt_bc", scenario, rslt_bc_val);
                    putByScenario(bc, "rslt_bcc", scenario, rslt_bcc_val);
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
            Map<String, Object> cmtyd) {

        List<Map<String, Object>> decompList = new ArrayList<>();

        Map<String, Map<String, Double>> rholSums = groupAndSumRhol(klList);
        Map<String, Map<String, Double>> gammacSums = groupAndSumGammac(bcList);

        for (Map<String, Object> trade : dataList) {
            Map<String, Object> res = new HashMap<>(trade);
            String id = riskFactorKey(trade);
            String bucket = trade.get("riskFactorBucket").toString();
            double ws = getDouble(trade, "ws");

            for (String scenario : FrtbConstants.SCENARIOS) {
                String scenName = FrtbConstants.SCENARIO_NAMES.get(scenario);
                double capital = getDouble(cmtyd, "capital_" + scenName);

                if (Math.abs(capital) < 1e-9) {
                    res.put("allocatedCapital_" + scenName, 0.0);
                    continue;
                }

                double rhol = rholSums.getOrDefault(id, Collections.emptyMap()).getOrDefault(scenario, 0.0);
                double gammac = gammacSums.getOrDefault(bucket, Collections.emptyMap()).getOrDefault(scenario, 0.0);

                double pder = calculatePder(ws, rhol, gammac, capital);
                double allocated = pder * ws;

                res.put("pder_" + scenName, pder);
                res.put("allocatedCapital_" + scenName, allocated);
            }
            decompList.add(res);
        }
        return decompList;
    }

    // 辅助方法
    private Map<String, Map<String, Double>> groupAndSumRhol(List<Map<String, Object>> klList) {
        Map<String, Map<String, Double>> sums = new HashMap<>();
        for (Map<String, Object> kl : klList) {
            String id = riskFactorKey(
                    kl.get("riskFactorId_K"),
                    kl.get("riskFactorVertex1_K"),
                    kl.get("riskFactorVertex2_K"),
                    kl.get("riskFactorType_K"));
            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(kl, "rhol", scenario, 0.0);
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
            String c = bc.get("bucket_c").toString();
            if (b.equals(c))
                continue;

            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(bc, "gammac", scenario, 0.0);
                if (val != 0) {
                    sums.computeIfAbsent(b, k -> new HashMap<>()).merge(scenario, val, Double::sum);
                }
            }
        }
        return sums;
    }

    private double calculatePder(double ws, double rhol, double gammac, double capital) {
        if (capital == 0)
            return 0;
        return (ws + rhol + gammac) / capital;
    }

    private double lookupRho(Map<String, Object> k, Map<String, Object> l) {
        if (isSameRiskFactor(k, l)) {
            return 1.0;
        }
        String bucket = k.get("riskFactorBucket").toString();
        CmtyRiskFactorId.Parsed idK = CmtyRiskFactorId.parseRequired(k.get("riskFactorId"));
        CmtyRiskFactorId.Parsed idL = CmtyRiskFactorId.parseRequired(l.get("riskFactorId"));
        Double commodityRho = Objects.equals(idK.getType(), idL.getType())
                ? 1.0
                : CMTY_RHO_MAP.get(bucket);
        if (commodityRho == null) {
            throw new IllegalArgumentException("未配置 CMTY 桶内商品相关性: " + bucket);
        }
        double tenorRho = Objects.equals(k.get("riskFactorVertex1"), l.get("riskFactorVertex1"))
                ? 1.0
                : FrtbParamsCache.getCmtyRhoTenorDiff();
        double locationRho = Objects.equals(idK.getLocation(), idL.getLocation())
                ? 1.0
                : FrtbParamsCache.getCmtyRhoBasisDiff();
        return commodityRho * tenorRho * locationRho;
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

    private String riskFactorKey(Map<String, Object> map) {
        return riskFactorKey(map.get("riskFactorId"), map.get("riskFactorVertex1"),
                map.get("riskFactorVertex2"), map.get("riskFactorType"));
    }

    private String riskFactorKey(Object id, Object vertex1, Object vertex2, Object riskFactorType) {
        return str(id) + "|" + str(vertex1) + "|" + str(vertex2) + "|" + str(riskFactorType);
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return (value instanceof Number) ? ((Number) value).doubleValue() : 0.0;
    }

    private double getByScenario(Map<String, Object> map, String prefix, String scenario, double defaultValue) {
        Object value = map.get(prefix + "_" + scenario);
        return (value instanceof Number) ? ((Number) value).doubleValue() : defaultValue;
    }

    private void putByScenario(Map<String, Object> map, String prefix, String scenario, double value) {
        map.put(prefix + "_" + scenario, value);
    }
}
