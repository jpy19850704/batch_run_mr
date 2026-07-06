package com.zcyh.mr.frtbsa.sba.core.fx;

import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getByScenario;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getDouble;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.putByScenario;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.SbaAggregationUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FX Vega 资本计算逻辑
 * 外汇风险 - Vega敏感性
 */
public class FxVega {

    private static final String SENS_TYPE = FrtbConstants.SENS_VEGA;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_FX;

    public Map<String, Object> calculate(List<Map<String, Object>> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        List<Map<String, Object>> klList = calculateKL(dataList);
        Map<String, List<Map<String, Object>>> aggAndBc = calculateAgg(klList, dataList);
        List<Map<String, Object>> aggList = aggAndBc.get("agg");
        List<Map<String, Object>> bcList = aggAndBc.get("bc");

        Map<String, Object> fxv = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();
            double est = SbaAggregationUtils.calculateDeltaVegaCapital(bcList, scenario);

            fxv.put("capital_" + scenarioName, est);
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
        fxv.put("capital", finalCapital);
        fxv.put("riskFactorClass", RISK_CLASS);
        fxv.put("sensType", SENS_TYPE);
        fxv.put("capital_normal", capital_M);
        fxv.put("capital_high", capital_H);
        fxv.put("capital_low", capital_L);

        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, klList, bcList, fxv);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", klList);
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", fxv);
        result.put("decompRslt", decompRsltList);
        return result;
    }

    private List<Map<String, Object>> calculateKL(List<Map<String, Object>> dataList) {
        List<Map<String, Object>> klList = new ArrayList<>();
        for (Map<String, Object> k : dataList) {
            String bucketK = normalizeBucket(k.get("riskFactorBucket"));
            for (Map<String, Object> l : dataList) {
                String bucketL = normalizeBucket(l.get("riskFactorBucket"));
                if (!bucketK.equals(bucketL)) {
                    continue;
                }

                Map<String, Object> kl = new HashMap<>();
                kl.put("bucket", bucketK);
                kl.put("riskFactorId_K", k.get("riskFactorId"));
                kl.put("riskFactorId_L", l.get("riskFactorId"));
                kl.put("riskFactorVertex1_K", k.get("riskFactorVertex1"));
                kl.put("riskFactorVertex1_L", l.get("riskFactorVertex1"));
                kl.put("riskFactorType_K", k.get("riskFactorType"));
                kl.put("riskFactorType_L", l.get("riskFactorType"));

                double vegaK = getDouble(k, "vega");
                double vegaL = getDouble(l, "vega");
                double rhoTenor = calculateTenorRho(
                        parseVertex(k.get("riskFactorVertex1")),
                        parseVertex(l.get("riskFactorVertex1")));
                boolean sameId = Objects.equals(k.get("riskFactorId"), l.get("riskFactorId"));
                boolean isSelf = isSameRiskFactor(k, l);
                double currentBaseRho = sameId ? rhoTenor : FrtbParamsCache.getFxRhoBase() * rhoTenor;

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double rho = FrtbConstants.applyScenarioStress(scenario, currentBaseRho);
                    if (isSelf) {
                        rho = 1.0;
                    }
                    putByScenario(kl, "rslt_kl", scenario, vegaK * vegaL * rho);
                    putByScenario(kl, "rho", scenario, rho);
                    putByScenario(kl, "rhol", scenario, isSelf ? 0.0 : vegaL * rho);
                }
                klList.add(kl);
            }
        }
        return klList;
    }

    private Map<String, List<Map<String, Object>>> calculateAgg(List<Map<String, Object>> klList,
            List<Map<String, Object>> dataList) {
        Set<String> allBuckets = dataList.stream()
                .map(e -> normalizeBucket(e.get("riskFactorBucket")))
                .collect(Collectors.toSet());
        Map<String, List<Map<String, Object>>> klByBucket = klList.stream()
                .collect(Collectors.groupingBy(e -> e.get("bucket").toString()));

        List<Map<String, Object>> aggList = new ArrayList<>();
        for (String bucket : allBuckets) {
            Map<String, Object> aggMap = new HashMap<>();
            aggMap.put("bucket", bucket);
            aggMap.put("riskFactorBucket", bucket);
            aggMap.put("riskFactorClass", RISK_CLASS);

            double sb = dataList.stream()
                    .filter(e -> bucket.equals(normalizeBucket(e.get("riskFactorBucket"))))
                    .mapToDouble(e -> getDouble(e, "vega"))
                    .sum();
            List<Map<String, Object>> bucketKl = klByBucket.getOrDefault(bucket, new ArrayList<>());

            for (String scenario : FrtbConstants.SCENARIOS) {
                double sumRsltKl = bucketKl.stream()
                        .mapToDouble(e -> getByScenario(e, "rslt_kl", scenario, 0.0))
                        .sum();
                double kb = Math.sqrt(Math.max(sumRsltKl, 0.0));
                putByScenario(aggMap, "Kb", scenario, kb);
                putByScenario(aggMap, "Kb", scenario + scenario, kb);
                putByScenario(aggMap, "Sb", scenario, sb);
                putByScenario(aggMap, "Sbb", scenario, SbaAggregationUtils.cappedSb(sb, kb));
            }
            aggList.add(aggMap);
        }

        List<Map<String, Object>> bcList = new ArrayList<>();
        for (Map<String, Object> b : aggList) {
            for (Map<String, Object> c : aggList) {
                String bucketB = normalizeBucket(b.get("bucket"));
                String bucketC = normalizeBucket(c.get("bucket"));

                Map<String, Object> bc = new HashMap<>();
                bc.put("bucket_b", bucketB);
                bc.put("bucket_c", bucketC);

                double baseGamma = FrtbParamsCache.getFxRhoBase();
                for (String scenario : FrtbConstants.SCENARIOS) {
                    double rslt;
                    double rsltBcc;
                    double gammac = 0.0;

                    if (bucketB.equals(bucketC)) {
                        double kb = getByScenario(b, "Kb", scenario + scenario, 0.0);
                        rslt = kb * kb;
                        rsltBcc = rslt;
                    } else {
                        double sbB = getByScenario(b, "Sb", scenario, 0.0);
                        double sbC = getByScenario(c, "Sb", scenario, 0.0);
                        double sbbB = getByScenario(b, "Sbb", scenario, 0.0);
                        double sbbC = getByScenario(c, "Sbb", scenario, 0.0);
                        double gamma = FrtbConstants.applyScenarioStress(scenario, baseGamma);
                        rslt = sbB * sbC * gamma;
                        rsltBcc = sbbB * sbbC * gamma;
                        gammac = sbC * gamma;
                    }

                    putByScenario(bc, "rslt_bc", scenario, rslt);
                    putByScenario(bc, "rslt_bcc", scenario, rsltBcc);
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
            List<Map<String, Object>> bcList,
            Map<String, Object> fxv) {

        List<Map<String, Object>> decompList = new ArrayList<>();
        Map<String, Map<String, Double>> rholSums = groupAndSumRhol(klList);
        Map<String, Map<String, Double>> gammacSums = groupAndSumGammac(bcList);
        for (Map<String, Object> trade : dataList) {
            decompList.add(new HashMap<>(trade));
        }

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double capital = getDouble(fxv, "capital_" + scenName);

            for (Map<String, Object> res : decompList) {
                String bucket = normalizeBucket(res.get("riskFactorBucket"));
                double vega = getDouble(res, "vega");
                double rhol = rholSums.getOrDefault(riskFactorKey(res), Collections.emptyMap())
                        .getOrDefault(scenario, 0.0);
                double gammac = gammacSums.getOrDefault(bucket, Collections.emptyMap()).getOrDefault(scenario, 0.0);
                double pder = (capital > 1e-9) ? (vega + rhol + gammac) / capital : 0.0;
                double allocated = pder * vega;
                res.put("pder_" + scenName, pder);
                res.put("allocatedCapital_" + scenName, allocated);
            }
        }
        return decompList;
    }

    private Map<String, Map<String, Double>> groupAndSumRhol(List<Map<String, Object>> klList) {
        Map<String, Map<String, Double>> sums = new HashMap<>();
        for (Map<String, Object> kl : klList) {
            String id = riskFactorKey(kl.get("riskFactorId_K"), kl.get("riskFactorVertex1_K"),
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
            String b = normalizeBucket(bc.get("bucket_b"));
            String c = normalizeBucket(bc.get("bucket_c"));
            if (b.equals(c)) {
                continue;
            }
            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(bc, "gammac", scenario, 0.0);
                if (val != 0) {
                    sums.computeIfAbsent(b, k -> new HashMap<>()).merge(scenario, val, Double::sum);
                }
            }
        }
        return sums;
    }

    private String normalizeBucket(Object bucket) {
        if (bucket == null || bucket.toString().isEmpty()) {
            return "FX";
        }
        return FrtbConstants.normalizeBucketForRiskClass(RISK_CLASS, bucket.toString());
    }




    private double calculateTenorRho(double tenorK, double tenorL) {
        if (tenorK <= 0 || tenorL <= 0) {
            throw new IllegalArgumentException("FX Vega 风险因子期限必须大于0");
        }
        if (Math.abs(tenorK - tenorL) < 1e-9) {
            return 1.0;
        }
        return Math.exp(-0.01 * Math.abs(tenorK - tenorL) / Math.min(tenorK, tenorL));
    }

    private double parseVertex(Object vertex) {
        if (vertex == null) {
            throw new IllegalArgumentException("FX Vega 风险因子期限不能为空");
        }
        String text = vertex.toString().trim();
        double parsed;
        if (text.toUpperCase(Locale.ROOT).endsWith("Y")) {
            parsed = Double.parseDouble(text.substring(0, text.length() - 1));
        } else if (text.toUpperCase(Locale.ROOT).endsWith("M")) {
            parsed = Double.parseDouble(text.substring(0, text.length() - 1)) / 12.0;
        } else {
            parsed = Double.parseDouble(text);
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("FX Vega 风险因子期限必须大于0: " + text);
        }
        return parsed;
    }

    private boolean isSameRiskFactor(Map<String, Object> k, Map<String, Object> l) {
        return Objects.equals(k.get("riskFactorId"), l.get("riskFactorId"))
                && Objects.equals(k.get("riskFactorVertex1"), l.get("riskFactorVertex1"))
                && Objects.equals(k.get("riskFactorType"), l.get("riskFactorType"));
    }

    private String riskFactorKey(Map<String, Object> map) {
        return riskFactorKey(map.get("riskFactorId"), map.get("riskFactorVertex1"), map.get("riskFactorType"));
    }

    private String riskFactorKey(Object id, Object vertex1, Object riskType) {
        return str(id) + "|" + str(vertex1) + "|" + str(riskType);
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
