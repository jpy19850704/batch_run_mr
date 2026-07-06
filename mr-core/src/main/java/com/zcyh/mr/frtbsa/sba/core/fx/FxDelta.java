package com.zcyh.mr.frtbsa.sba.core.fx;

import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getByScenario;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getDouble;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.putByScenario;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.SbaAggregationUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FX Delta 资本计算逻辑
 * 外汇风险 - Delta敏感性
 */
public class FxDelta {

    private static final String SENS_TYPE = FrtbConstants.SENS_DELTA;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_FX;

    public Map<String, Object> calculate(List<Map<String, Object>> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        // FX Delta：按币种 bucket 直接聚合，不构造 KL
        Map<String, List<Map<String, Object>>> aggAndBc = calculateAgg(dataList);
        List<Map<String, Object>> aggList = aggAndBc.get("agg");
        List<Map<String, Object>> bcList = aggAndBc.get("bc");

        Map<String, Object> fxd = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();
            double est = SbaAggregationUtils.calculateDeltaVegaCapital(bcList, scenario);

            fxd.put("capital_" + scenarioName, est);
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
        fxd.put("capital", finalCapital);
        fxd.put("riskFactorClass", RISK_CLASS);
        fxd.put("sensType", SENS_TYPE);
        fxd.put("capital_normal", capital_M);
        fxd.put("capital_high", capital_H);
        fxd.put("capital_low", capital_L);

        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, aggList, bcList, fxd);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("kl", new ArrayList<>());
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", fxd);
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

            double sb = dataList.stream()
                    .filter(e -> bucket.equals(normalizeBucket(e.get("riskFactorBucket"))))
                    .mapToDouble(e -> getDouble(e, "ws"))
                    .sum();

            for (String scenario : FrtbConstants.SCENARIOS) {
                double kb = Math.abs(sb);
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
            List<Map<String, Object>> aggList,
            List<Map<String, Object>> bcList,
            Map<String, Object> fxd) {

        List<Map<String, Object>> decompList = new ArrayList<>();
        for (Map<String, Object> trade : dataList) {
            decompList.add(new HashMap<>(trade));
        }

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double capital = getDouble(fxd, "capital_" + scenName);

            Map<String, Double> crossTermSumMap = new HashMap<>();
            for (Map<String, Object> bc : bcList) {
                String bb = normalizeBucket(bc.get("bucket_b"));
                String bcBucket = normalizeBucket(bc.get("bucket_c"));
                if (bb.equals(bcBucket)) {
                    continue;
                }
                double val = getByScenario(bc, "rslt_bc", scenario, 0.0);
                crossTermSumMap.merge(bb, val, Double::sum);
            }

            Map<String, Double> unitContribMap = new HashMap<>();
            for (Map<String, Object> agg : aggList) {
                String bucket = normalizeBucket(agg.get("bucket"));
                double sb = getByScenario(agg, "Sbb", scenario, 0.0);
                double kb = getByScenario(agg, "Kb", scenario, 0.0);
                double cross = crossTermSumMap.getOrDefault(bucket, 0.0);
                double bucketVal = (capital > 1e-9) ? (kb * kb + cross) / capital : 0.0;
                double unitContrib = (Math.abs(sb) > 1e-9) ? bucketVal / sb : 0.0;
                unitContribMap.put(bucket, unitContrib);
            }

            for (Map<String, Object> res : decompList) {
                String bucket = normalizeBucket(res.get("riskFactorBucket"));
                double ws = getDouble(res, "ws");
                double unitContrib = unitContribMap.getOrDefault(bucket, 0.0);
                double pder = (capital > 1e-9) ? (ws * unitContrib / capital) : 0.0;
                double allocated = pder * capital;
                res.put("pder_" + scenName, pder);
                res.put("allocatedCapital_" + scenName, allocated);
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



}
