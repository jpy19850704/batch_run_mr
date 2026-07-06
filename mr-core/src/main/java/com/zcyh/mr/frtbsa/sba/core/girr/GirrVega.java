package com.zcyh.mr.frtbsa.sba.core.girr;

import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getByScenario;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.getDouble;
import static com.zcyh.mr.frtbsa.sba.core.SbaScenarioValueSupport.putByScenario;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import com.zcyh.mr.frtbsa.sba.common.SbaAggregationUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GIRR Vega 资本计算逻辑
 * 利率期权波动率风险 - Vega敏感性
 */
public class GirrVega {

    private static final String SENS_TYPE = FrtbConstants.SENS_VEGA;
    private static final String RISK_CLASS = FrtbConstants.RISK_CLASS_GIRR;

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
        Map<String, Object> girrv = new HashMap<>();
        double capital_M = 0, capital_H = 0, capital_L = 0;

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenarioName = FrtbConstants.SCENARIO_NAMES.get(scenario);

            double sumRsltBc = bcList.stream()
                    .mapToDouble(e -> getByScenario(e, "rslt_bc", scenario, 0.0))
                    .sum();

            double est = SbaAggregationUtils.calculateDeltaVegaCapital(bcList, scenario);

            girrv.put("capital_" + scenarioName, est);

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
        girrv.put("capital", finalCapital);
        girrv.put("riskFactorClass", RISK_CLASS);
        girrv.put("sensType", SENS_TYPE);
        girrv.put("capital_normal", capital_M);
        girrv.put("capital_high", capital_H);
        girrv.put("capital_low", capital_L);

        // 4. (可选) 资本分解
        List<Map<String, Object>> decompRsltList = new ArrayList<>();
        if (Boolean.TRUE.equals(needDecompose)) {
            decompRsltList = decompose(dataList, klList, aggList, bcList, girrv);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("pos", dataList);
        result.put("class", girrv);
        result.put("kl", klList);
        result.put("bucket", aggList);
        result.put("bc", bcList);
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

                double vegaK = getDouble(k, "vega");
                double vegaL = getDouble(l, "vega");
                double tenorK = parseTenor(str(k.get("riskFactorVertex1")));
                double tenorL = parseTenor(str(l.get("riskFactorVertex1")));
                double tenor2K = parseTenor(str(k.get("riskFactorVertex2")));
                double tenor2L = parseTenor(str(l.get("riskFactorVertex2")));

                kl.put("ws_L", vegaL);

                // GIRR Vega 按第一维和第二维 tenor 共同计算相关性。
                double baseRho = calculateTenorRho(tenorK, tenorL) * calculateTenorRho(tenor2K, tenor2L);

                boolean isSelf = (k.get("riskFactorId").equals(l.get("riskFactorId"))
                        && str(k.get("riskFactorVertex1")).equals(str(l.get("riskFactorVertex1")))
                        && str(k.get("riskFactorVertex2")).equals(str(l.get("riskFactorVertex2"))));

                if (isSelf)
                    baseRho = 1.0;

                for (String scenario : FrtbConstants.SCENARIOS) {
                    double rho = FrtbConstants.applyScenarioStress(scenario, baseRho);
                    if (isSelf)
                        rho = 1.0;

                    putByScenario(kl, "rslt_kl", scenario, vegaK * vegaL * rho);
                    putByScenario(kl, "rho", scenario, rho);

                    double rhol = isSelf ? 0.0 : vegaL * rho;
                    putByScenario(kl, "rhol", scenario, rhol);
                }
                klList.add(kl);
            }
        }
        return klList;
    }

    private double calculateTenorRho(double tenorK, double tenorL) {
        if (tenorK == 0 || tenorL == 0)
            return 1.0; // 缺失数据视为完全相关
        if (Math.abs(tenorK - tenorL) < 1e-9)
            return 1.0;

        // 使用 Vega Lambda (0.01)
        return Math
                .exp(-FrtbParamsCache.getGirrVegaTenorLambda() * Math.abs(tenorK - tenorL) / Math.min(tenorK, tenorL));
    }

    // ==================== 工具方法 ====================

    private double parseTenor(String tenor) {
        if (tenor == null || tenor.isEmpty())
            return 0.0;
        try {
            String s = tenor.trim().toUpperCase();
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
            aggMap.put("riskFactorClass", RISK_CLASS);

            List<Map<String, Object>> bucketKl = klByBucket.getOrDefault(bucket, new ArrayList<>());

            // Sb 不随场景变化，提到循环外
            double Sb = dataList.stream()
                    .filter(e -> bucket.equals(e.get("riskFactorBucket")))
                    .mapToDouble(e -> getDouble(e, "vega"))
                    .sum();
            aggMap.put("Sb", Sb);

            for (String scenario : FrtbConstants.SCENARIOS) {
                double sumRsltKl = bucketKl.stream()
                        .mapToDouble(e -> getByScenario(e, "rslt_kl", scenario, 0.0)).sum();

                double Kb = Math.sqrt(Math.max(sumRsltKl, 0.0));
                double Sbb = SbaAggregationUtils.cappedSb(Sb, Kb);

                putByScenario(aggMap, "Kb", scenario, Kb);
                putByScenario(aggMap, "Kb", scenario + scenario, Kb);
                putByScenario(aggMap, "Sbb", scenario, Sbb);
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
                bc.put("riskFactorClass", RISK_CLASS);

                double baseGamma = FrtbParamsCache.getGirrGamma(); // 0.5
                if (bucketB.equals(bucketC))
                    baseGamma = 1.0;

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
            Map<String, Object> girrv) {

        List<Map<String, Object>> decompList = new ArrayList<>();

        for (Map<String, Object> trade : dataList) {
            decompList.add(new HashMap<>(trade));
        }

        for (String scenario : FrtbConstants.SCENARIOS) {
            String scenName = FrtbConstants.SCENARIO_NAMES.get(scenario);
            double capital = getDouble(girrv, "capital_" + scenName);

            // 1. 汇总每个 bucket 的总贡献（对角线 Kb² + 跨桶交叉项）
            Map<String, Double> crossTermSumMap = new HashMap<>();
            for (Map<String, Object> bc : bcList) {
                String bb = bc.get("bucket_b").toString();
                double val = getByScenario(bc, "rslt_bc", scenario, 0.0);
                crossTermSumMap.merge(bb, val, Double::sum);
            }

            // 2. 计算每个桶的单位贡献率（使用原始 Sb 而非截断后的 Sbb）
            Map<String, Double> unitContribMap = new HashMap<>();
            for (Map<String, Object> agg : aggList) {
                String bucket = agg.get("bucket").toString();
                double Sb = getDouble(agg, "Sb");
                double crossSum = crossTermSumMap.getOrDefault(bucket, 0.0);
                double bucketVal = (capital > 1e-9) ? crossSum / capital : 0.0;
                double unitContrib = (Math.abs(Sb) > 1e-9) ? bucketVal / Sb : 0.0;
                unitContribMap.put(bucket, unitContrib);
            }

            // 3. 分配到每个头寸
            for (Map<String, Object> res : decompList) {
                String bucket = res.get("riskFactorBucket").toString();
                double vega = getDouble(res, "vega");
                double unitContrib = unitContribMap.getOrDefault(bucket, 0.0);

                // Euler 分配：pder 包含 vega 因子，allocated = pder × capital
                double pder = (capital > 1e-9) ? vega * unitContrib / capital : 0.0;
                double allocated = pder * capital;

                res.put("pder_" + scenName, pder);
                res.put("allocatedCapital_" + scenName, allocated);
            }
        }
        return decompList;
    }

    // ==================== 辅助方法 ====================
    private Map<String, Map<String, Double>> groupAndSumRhol(List<Map<String, Object>> klList) {
        Map<String, Map<String, Double>> sums = new HashMap<>();
        for (Map<String, Object> kl : klList) {
            String id = str(kl.get("riskFactorId_K"));
            String bucket = str(kl.get("bucket"));
            String vertex1 = str(kl.get("riskFactorVertex1_K"));
            String vertex2 = str(kl.get("riskFactorVertex2_K"));
            String key = buildRholKey(id, bucket, vertex1, vertex2);
            for (String scenario : FrtbConstants.SCENARIOS) {
                double val = getByScenario(kl, "rhol", scenario, 0.0);
                if (val != 0) {
                    sums.computeIfAbsent(key, k -> new HashMap<>()).merge(scenario, val, Double::sum);
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

    private String buildRholKey(String id, String bucket, String vertex1, String vertex2) {
        return id + "|" + bucket + "|" + vertex1 + "|" + vertex2;
    }

    private String str(Object value) {
        return value == null ? "" : value.toString();
    }



}
