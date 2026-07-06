package com.zcyh.mr.frtbsa.sba.core.csrns;

import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getByScenario;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getDouble;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.putByScenario;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.SbaAggregationUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSRNS Delta 资本计算逻辑
 * 非证券化信用利差风险 - Delta敏感性
 */
public class CsrnsDelta {

    private static final String SENS_TYPE = FrtbConstants.SENS_DELTA;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_CSRNS;
    private static final String SPECIAL_BUCKET = "16";
    private static final HashMap<String, Double> CSRNS_DELTA_GAMMA_MATRIX = FrtbParamsCache.buildCsrnsDeltaGammaMatrix();

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
        Map<String, Object> csrd = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);

            // bucket16 单列：先汇总非16桶，再单独加16桶
            double sumRsltBcMain = bcList.stream()
                    .filter(e -> !SPECIAL_BUCKET.equals(e.get("bucket_b").toString())
                            && !SPECIAL_BUCKET.equals(e.get("bucket_c").toString()))
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();
            double kbSpecial = aggList.stream()
                    .filter(e -> SPECIAL_BUCKET.equals(e.get("bucket").toString()))
                    .mapToDouble(e -> getByScenario(e, "Kb", scenario + scenario, 0.0))
                    .sum();

            double est = SbaAggregationUtils.calculateDeltaVegaCapital(bcList, scenario, e -> !SPECIAL_BUCKET.equals(e.get("bucket_b").toString())
                    && !SPECIAL_BUCKET.equals(e.get("bucket_c").toString())) + kbSpecial;

            csrd.put("capital_" + scenarioName, est);

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
        csrd.put("capital", finalCapital);
        csrd.put("riskFactorClass", RISK_CLASS);
        csrd.put("sensType", SENS_TYPE);
        csrd.put("capital_normal", capital_M);
        csrd.put("capital_high", capital_H);
        csrd.put("capital_low", capital_L);

        // 4. (可选) 资本分解
        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, klList, aggList, bcList, csrd);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", klList);
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", csrd);
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
                String riskTypeK = normalizeRiskType(k.get("riskFactorType"));
                String riskTypeL = normalizeRiskType(l.get("riskFactorType"));
                kl.put("riskFactorType_K", riskTypeK);
                kl.put("riskFactorType_L", riskTypeL);

                double wsK = getDouble(k, "ws");
                double wsL = getDouble(l, "ws");
                kl.put("ws_L", wsL);

                double tenorK = parseTenor(k.get("riskFactorVertex1"));
                double tenorL = parseTenor(l.get("riskFactorVertex1")); // Vertex1 瀛樺偍 Tenor/鏈熼檺

                // 统一 Tenor 解析逻辑：如果是 "riskFactorVertex1" 或 "tenor" 字段
                if (tenorK == 0.0 && k.containsKey("tenor"))
                    tenorK = getDouble(k, "tenor");
                if (tenorL == 0.0 && l.containsKey("tenor"))
                    tenorL = getDouble(l, "tenor");

                // CSRNS 相关性逻辑：
                // 相关性公式：rho = rho_name * rho_tenor * rho_basis
                boolean sameName = isSameName(k, l);
                boolean sameType = riskTypeK.equals(riskTypeL);
                double rhoName = sameName ? 1.0 : getNameDiffRhoByBucket(bucketK);
                boolean sameVertex = isSameVertex(tenorK, tenorL);
                boolean isSelf = sameName && sameVertex && sameType;

                double rhoTenor = calculateTenorRho(tenorK, tenorL);
                double rhoBasis = sameType ? 1.0 : FrtbParamsCache.getCsrnsRhoBasisDiff();
                double currentBaseRho = rhoName * rhoTenor * rhoBasis;

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

    private boolean isSameName(Map<String, Object> k, Map<String, Object> l) {
        // 判断是否为同一发行人名称
        // 基于 riskFactorId 判断
        return k.get("riskFactorId").equals(l.get("riskFactorId"));
    }

    private boolean isSameVertex(double tenorK, double tenorL) {
        return tenorK > 0 && tenorL > 0 && Math.abs(tenorK - tenorL) < 1e-9;
    }

    private double calculateTenorRho(double tenorK, double tenorL) {
        if (tenorK == 0 || tenorL == 0)
            return 1.0;
        if (tenorK == tenorL)
            return 1.0;
        return FrtbParamsCache.getCsrnsRhoTenorDiff();
    }

    private double getNameDiffRhoByBucket(String bucket) {
        if ("17".equals(bucket) || "18".equals(bucket)) {
            return FrtbParamsCache.getCsrnsRhoNameDiffBucket1718();
        }
        return FrtbParamsCache.getCsrnsRhoNameDiff();
    }

    private String normalizeRiskType(Object riskType) {
        if (riskType == null) {
            return "BOND";
        }
        String normalized = riskType.toString().trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? "BOND" : normalized;
    }

    private double parseTenor(Object obj) {
        if (obj == null)
            return 0.0;
        try {
            String s = obj.toString().trim().toUpperCase();
            if (s.endsWith("Y"))
                return Double.parseDouble(s.replace("Y", ""));
            if (s.endsWith("M"))
                return Double.parseDouble(s.replace("M", "")) / 12.0;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
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

    private double lookupGamma(String bucketB, String bucketC) {
        if (bucketB.equals(bucketC)) {
            return 1.0;
        }
        Double gamma = CSRNS_DELTA_GAMMA_MATRIX.get(bucketB + "," + bucketC);
        if (gamma == null) {
            throw new IllegalArgumentException("未配置 CSRNS Delta 跨桶相关性: " + bucketB + "," + bucketC);
        }
        return gamma;
    }

    private List<Map<String, Object>> decompose(List<Map<String, Object>> dataList,
            List<Map<String, Object>> klList,
            List<Map<String, Object>> aggList,
            List<Map<String, Object>> bcList,
            Map<String, Object> csrd) {

        List<Map<String, Object>> decompList = new ArrayList<>();

        Map<String, Map<String, Double>> rholSums = groupAndSumRhol(klList);
        Map<String, Map<String, Double>> gammacSums = groupAndSumGammac(bcList);

        for (Map<String, Object> trade : dataList) {
            Map<String, Object> res = new HashMap<>(trade);
            String id = trade.get("riskFactorId").toString();
            String vertex1 = trade.get("riskFactorVertex1") == null ? "" : trade.get("riskFactorVertex1").toString();
            String riskType = normalizeRiskType(trade.get("riskFactorType"));
            String tradeKey = buildTradeKey(id, vertex1, riskType);
            String bucket = trade.get("riskFactorBucket").toString();
            double ws = getDouble(trade, "ws");

            for (String scenario : FrtbConstants.SCENARIOS) {
                String scenName = FrtbConstants.SCENARIO_NAMES.get(scenario);
                double capital = getDouble(csrd, "capital_" + scenName);

                if (Math.abs(capital) < 1e-9) {
                    res.put("allocatedCapital_" + scenName, 0.0);
                    continue;
                }

                double rhol = rholSums.getOrDefault(tradeKey, Collections.emptyMap()).getOrDefault(scenario, 0.0);
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
            String id = kl.get("riskFactorId_K").toString();
            String vertex1 = kl.get("riskFactorVertex1_K") == null ? "" : kl.get("riskFactorVertex1_K").toString();
            String riskType = normalizeRiskType(kl.get("riskFactorType_K"));
            String key = buildTradeKey(id, vertex1, riskType);
            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(kl, "rhol", scenario, 0.0);
                if (val != 0) {
                    sums.computeIfAbsent(key, k -> new HashMap<>()).merge(scenario, val, Double::sum);
                }
            }
        }
        return sums;
    }

    private String buildTradeKey(String id, String vertex1, String riskType) {
        return id + "@" + (vertex1 == null ? "" : vertex1) + "@"
                + (riskType == null ? "BOND" : riskType);
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



}
