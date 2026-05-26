package com.zcyh.mr.frtbsa.sba.core.fx;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FX Curvature 资本计算逻辑
 * 外汇风险 - Curvature敏感性
 */
public class FxCurvature {

    private static final String SENS_TYPE = FrtbConstants.SENS_CURVATURE;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_FX;

    public Map<String, Object> calculate(List<Map<String, Object>> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        // FX Curvature：不计算 KL，直接按 bucket 的 CVR_up/down 聚合
        Map<String, List<Map<String, Object>>> aggAndBc = calculateAgg(dataList);
        List<Map<String, Object>> aggList = aggAndBc.get("agg");
        List<Map<String, Object>> bcList = aggAndBc.get("bc");

        Map<String, Object> fxc = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);

            double sumKbSq = aggList.stream()
                    .mapToDouble(e -> Math.pow(getByScenario(e, "Kb", scenario, 0.0), 2))
                    .sum();
            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();
            double est = Math.sqrt(Math.max(0.0, sumKbSq + sumRsltBc));

            fxc.put("capital_" + scenarioName, est);
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
        fxc.put("capital", finalCapital);
        fxc.put("riskFactorClass", RISK_CLASS);
        fxc.put("sensType", SENS_TYPE);
        fxc.put("capital_normal", capital_M);
        fxc.put("capital_high", capital_H);
        fxc.put("capital_low", capital_L);

        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, aggList, bcList, fxc);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", new ArrayList<>());
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", fxc);
        result.put("decompRslt", decompRsltList);
        return result;
    }

    private Map<String, List<Map<String, Object>>> calculateAgg(List<Map<String, Object>> dataList) {
        Set<String> allBuckets = dataList.stream()
                .map(e -> normalizeBucket(e.get("riskFactorBucket")))
                .collect(Collectors.toSet());

        List<Map<String, Object>> aggList = new ArrayList<>();
        for (String bucket : allBuckets) {
            Map<String, Object> aggMap = new HashMap<>();
            aggMap.put("bucket", bucket);
            aggMap.put("riskFactorBucket", bucket);
            aggMap.put("riskFactorClass", RISK_CLASS);

            double sumCvrUp = dataList.stream()
                    .filter(e -> bucket.equals(normalizeBucket(e.get("riskFactorBucket"))))
                    .mapToDouble(e -> getDouble(e, "CVR_up"))
                    .sum();
            double sumCvrDown = dataList.stream()
                    .filter(e -> bucket.equals(normalizeBucket(e.get("riskFactorBucket"))))
                    .mapToDouble(e -> getDouble(e, "CVR_down"))
                    .sum();

            for (String scenario : FrtbConstants.SCENARIOS) {
                double KbUp = Math.max(0.0, sumCvrUp);
                double KbDown = Math.max(0.0, sumCvrDown);
                boolean isUpDominant = FrtbConstants.selectCurvatureUpScenario(KbUp, KbDown, sumCvrUp, sumCvrDown);
                double Kb = isUpDominant ? KbUp : KbDown;
                double Sb = isUpDominant ? sumCvrUp : sumCvrDown;

                putByScenario(aggMap, "Kb", scenario, Kb);
                putByScenario(aggMap, "Sbb", scenario, Sb);
                aggMap.put("isUpDominant_" + scenario, isUpDominant);
            }
            aggList.add(aggMap);
        }

        List<Map<String, Object>> bcList = new ArrayList<>();
        for (Map<String, Object> b : aggList) {
            for (Map<String, Object> c : aggList) {
                String bucketB = normalizeBucket(b.get("bucket"));
                String bucketC = normalizeBucket(c.get("bucket"));
                if (bucketB.equals(bucketC)) {
                    continue;
                }

                Map<String, Object> bc = new HashMap<>();
                bc.put("bucket_b", bucketB);
                bc.put("bucket_c", bucketC);

                double baseGamma = FrtbParamsCache.getFxRhoBase();
                for (String scenario : FrtbConstants.SCENARIOS) {
                    double Sb = getByScenario(b, "Sbb", scenario, 0.0);
                    double Sc = getByScenario(c, "Sbb", scenario, 0.0);
                    double psi = (Sb < 0 && Sc < 0) ? 0.0 : 1.0;
                    double gamma = FrtbConstants.applyCurvatureScenarioStress(scenario, baseGamma);

                    putByScenario(bc, "rslt_bc", scenario, gamma * Sb * Sc * psi);
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
            List<Map<String, Object>> aggList,
            List<Map<String, Object>> bcList,
            Map<String, Object> fxc) {

        List<Map<String, Object>> decompList = new ArrayList<>();
        for (Map<String, Object> trade : dataList) {
            decompList.add(new HashMap<>(trade));
        }

        Map<String, Map<String, Object>> aggByBucket = aggList.stream()
                .collect(Collectors.toMap(
                        e -> normalizeBucket(e.get("bucket")),
                        e -> e,
                        (a, b) -> a,
                        LinkedHashMap::new));

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double capitalTotal = getDouble(fxc, "capital_" + scenarioName);

            Map<String, Double> crossTermSumMap = new HashMap<>();
            for (Map<String, Object> bc : bcList) {
                String bb = normalizeBucket(bc.get("bucket_b"));
                double val = getByScenario(bc, "rslt_bc", scenario, 0.0);
                crossTermSumMap.merge(bb, val, Double::sum);
            }

            Map<String, Double> unitContribMap = new HashMap<>();
            for (Map<String, Object> agg : aggList) {
                String bucket = normalizeBucket(agg.get("bucket"));
                double Sb = getByScenario(agg, "Sbb", scenario, 0.0);
                double Kb = getByScenario(agg, "Kb", scenario, 0.0);
                double crossSum = crossTermSumMap.getOrDefault(bucket, 0.0);
                double bucketVal = (capitalTotal > 1e-9) ? (Kb * Kb + crossSum) / capitalTotal : 0.0;
                double unitContrib = (Math.abs(Sb) > 1e-9) ? bucketVal / Sb : 0.0;
                unitContribMap.put(bucket, unitContrib);
            }

            for (Map<String, Object> res : decompList) {
                String bucket = normalizeBucket(res.get("riskFactorBucket"));
                Map<String, Object> bAgg = aggByBucket.get(bucket);
                boolean isUp = bAgg != null && Boolean.TRUE.equals(bAgg.get("isUpDominant_" + scenario));
                double activeCvr = isUp ? getDouble(res, "CVR_up") : getDouble(res, "CVR_down");
                double unitContrib = unitContribMap.getOrDefault(bucket, 0.0);

                double pder = (capitalTotal > 1e-9) ? (activeCvr * unitContrib / capitalTotal) : 0.0;
                double allocated = pder * capitalTotal;

                res.put("pder_" + scenarioName, pder);
                res.put("activeCvr_" + scenarioName, activeCvr);
                res.put("activeCvrSide_" + scenarioName, isUp ? "UP" : "DOWN");
                res.put("allocatedCapital_" + scenarioName, allocated);
            }
        }

        return decompList;
    }

    private String normalizeBucket(Object bucket) {
        if (bucket == null || bucket.toString().isEmpty()) {
            return "FX";
        }
        return FrtbConstants.normalizeBucketForRiskClass(RISK_CLASS, bucket.toString());
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
