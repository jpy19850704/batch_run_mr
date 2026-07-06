package com.zcyh.mr.frtbsa.sba.core.girr;

import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getByScenario;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getDouble;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.putByScenario;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GIRR Curvature 资本计算逻辑
 * 利率期权曲率风险 - Curvature敏感性
 */
public class GirrCurvature {

    private static final String SENS_TYPE = FrtbConstants.SENS_CURVATURE;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_GIRR;

    public Map<String, Object> calculate(List<Map<String, Object>> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        // 1. 聚合计算 (Intra-Bucket & Inter-Bucket)
        Map<String, List<Map<String, Object>>> aggAndBc = calculateAgg(dataList);
        List<Map<String, Object>> aggList = aggAndBc.get("agg");
        List<Map<String, Object>> bcList = aggAndBc.get("bc");

        // 2. 计算资本（循环处理3个场景）
        double capital_M = 0, capital_H = 0, capital_L = 0;

        Map<String, Object> girrc = new HashMap<>();

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);

            // 2.1 聚合 Kb^2
            double sumKbSq = aggList.stream()
                    .mapToDouble(e -> Math.pow(getByScenario(e, "Kb", scenario, 0.0), 2))
                    .sum();

            // 2.2 聚合 rslt_bc
            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();

            // 2.3 计算资本
            double capital = Math.sqrt(Math.max(0.0, sumKbSq + sumRsltBc));

            // 2.4 存储结果
            girrc.put("capital_" + scenarioName, capital);

            switch (scenario) {
                case "M":
                    capital_M = capital;
                    break;
                case "H":
                    capital_H = capital;
                    break;
                case "L":
                    capital_L = capital;
                    break;
            }
        }

        girrc.put("riskFactorClass", RISK_CLASS);
        girrc.put("sensType", SENS_TYPE);
        girrc.put("capital_normal", capital_M);
        girrc.put("capital_high", capital_H);
        girrc.put("capital_low", capital_L);
        // 最终资本取最大
        double finalCapital = Math.max(Math.max(capital_M, capital_H), capital_L);
        girrc.put("capital", finalCapital);

        // 3. 分解 (可选)
        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, aggList, bcList, girrc);
        }

        // 4. 构造返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("bucket", aggList);
        result.put("bc", bcList);
        result.put("class", girrc);
        result.put("decompRslt", decompRsltList);

        return result;
    }

    // ==================== 分解计算 ====================

    private List<Map<String, Object>> decompose(List<Map<String, Object>> dataList,
            List<Map<String, Object>> aggList,
            List<Map<String, Object>> bcList,
            Map<String, Object> girrc) {

        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        Map<String, Map<String, Object>> aggByBucket = aggList.stream()
                .collect(Collectors.toMap(
                        e -> normalizeBucket(e.get("bucket")),
                        e -> e,
                        (a, b) -> a,
                        LinkedHashMap::new));

        for (Map<String, Object> data : dataList) {
            Map<String, Object> decompData = new HashMap<>(data);
            decompRsltList.add(decompData);
        }

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double capitalTotal = getDouble(girrc, "capital_" + scenarioName);

            // 1. 汇总跨桶交叉项
            Map<String, Double> crossTermSumMap = new HashMap<>();
            for (Map<String, Object> bc : bcList) {
                String bb = bc.get("bucket_b").toString();
                double val = getByScenario(bc, "rslt_bc", scenario, 0.0);
                crossTermSumMap.merge(bb, val, Double::sum);
            }

            // 2. 计算每个桶的单位贡献率
            Map<String, Double> unitContribMap = new HashMap<>();
            for (Map<String, Object> agg : aggList) {
                String bucket = normalizeBucket(agg.get("bucket"));
                double Sb = getByScenario(agg, "Sbb", scenario, 0.0);
                double Kb = getByScenario(agg, "Kb", scenario, 0.0);
                double crossSum = crossTermSumMap.getOrDefault(bucket, 0.0);

                // 欧拉分配: 单位贡献率= (Kb虏 + crossSum) / (K_scen 脳 Sb)
                double bucketVal = (capitalTotal > 1e-9) ? (Kb * Kb + crossSum) / capitalTotal : 0.0;
                double unitContrib = (Math.abs(Sb) > 1e-9) ? bucketVal / Sb : 0.0;
                unitContribMap.put(bucket, unitContrib);
            }

            // 3. 分配到每个风险因子
            for (Map<String, Object> decompData : decompRsltList) {
                String bucket = normalizeBucket(decompData.get("riskFactorBucket"));
                Map<String, Object> bAgg = aggByBucket.get(bucket);
                boolean isUp = bAgg != null && Boolean.TRUE.equals(bAgg.get("isUpDominant_" + scenario));
                double cvrUp = getDouble(decompData, "CVR_up");
                double cvrDown = getDouble(decompData, "CVR_down");
                double usedVal = isUp ? cvrUp : cvrDown;
                double unitContrib = unitContribMap.getOrDefault(bucket, 0.0);

                // 计算贡献率 pder (与 GirrDelta 保持一致
                double pderValue = (capitalTotal > 1e-9) ? usedVal * unitContrib / capitalTotal : 0.0;
                decompData.put("pder_" + scenarioName, pderValue);
                decompData.put("activeCvr_" + scenarioName, usedVal);
                decompData.put("activeCvrSide_" + scenarioName, isUp ? "UP" : "DOWN");

                // 计算分配资本
                double allocatedCapital = pderValue * capitalTotal;
                decompData.put("allocatedCapital_" + scenarioName, allocatedCapital);
                decompData.put("unit_capital_" + scenarioName, unitContrib);
            }
        }

        return decompRsltList;
    }

    private Map<String, List<Map<String, Object>>> calculateAgg(List<Map<String, Object>> dataList) {

        // 按桶分组
        Map<String, List<Map<String, Object>>> dataByBucket = dataList.stream()
                .collect(Collectors.groupingBy(e -> normalizeBucket(e.get("riskFactorBucket"))));

        List<Map<String, Object>> aggList = new ArrayList<>();
        Set<String> allBuckets = dataByBucket.keySet();

        for (String bucket : allBuckets) {
            Map<String, Object> aggMap = new HashMap<>();
            aggMap.put("bucket", bucket);
            aggMap.put("riskFactorBucket", bucket);

            List<Map<String, Object>> bucketData = dataByBucket.get(bucket);

            double sumCvrUp = bucketData.stream()
                    .mapToDouble(e -> getDouble(e, "CVR_up"))
                    .sum();
            double sumCvrDown = bucketData.stream()
                    .mapToDouble(e -> getDouble(e, "CVR_down"))
                    .sum();

            for (String scenario : FrtbConstants.SCENARIOS) {
                double KbUp = Math.max(sumCvrUp, 0.0);
                double KbDown = Math.max(sumCvrDown, 0.0);
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
                Map<String, Object> bc = new HashMap<>();
                String bucketB = b.get("bucket").toString();
                String bucketC = c.get("bucket").toString();

                bc.put("bucket_b", bucketB);
                bc.put("bucket_c", bucketC);

                if (bucketB.equals(bucketC))
                    continue;

                double baseGamma = FrtbParamsCache.getGirrGamma();

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double Sb = getByScenario(b, "Sbb", scenario, 0.0);
                    double Sc = getByScenario(c, "Sbb", scenario, 0.0);
                    double psi = (Sb < 0 && Sc < 0) ? 0.0 : 1.0;
                    double gamma = FrtbConstants.applyCurvatureScenarioStress(scenario, baseGamma);
                    double term = gamma * Sb * Sc * psi;
                    putByScenario(bc, "rslt_bc", scenario, term);
                }
                bcList.add(bc);
            }
        }

        Map<String, List<Map<String, Object>>> mp = new HashMap<>();
        mp.put("agg", aggList);
        mp.put("bc", bcList);
        return mp;
    }

    private String normalizeBucket(Object bucket) {
        if (bucket == null || bucket.toString().isEmpty()) {
            return "GIRR";
        }
        return FrtbConstants.normalizeBucketForRiskClass(RISK_CLASS, bucket.toString());
    }



}
