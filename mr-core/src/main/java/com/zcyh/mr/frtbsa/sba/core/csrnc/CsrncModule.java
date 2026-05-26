package com.zcyh.mr.frtbsa.sba.core.csrnc;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSRNC(信用风险-非CTP)风险计算模块入口。
 * 核心逻辑：
 * 2. 楂樼浉鍏虫細rho_base=0.80锟?
 * 3. 跨Bucket聚合：gamma=0.50
 */
public class CsrncModule {

    private final CsrncDelta csrncDelta = new CsrncDelta();
    private final CsrncVega csrncVega = new CsrncVega();
    private final CsrncCurvature csrncCurvature = new CsrncCurvature();

    public Map<String, Object> calc(List<FrtbInput> dataList) {
        return calc(dataList, true);
    }

    public Map<String, Object> calc(List<FrtbInput> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, List<Map<String, Object>>> grouped = groupAndSum(dataList);
        Map<String, Object> result = new HashMap<>();

        if (grouped.containsKey("Delta") && !grouped.get("Delta").isEmpty()) {
            result.put("Delta", csrncDelta.calculate(grouped.get("Delta"), needDecompose));
        }

        if (grouped.containsKey("Vega") && !grouped.get("Vega").isEmpty()) {
            result.put("Vega", csrncVega.calculate(grouped.get("Vega"), needDecompose));
        }

        if (grouped.containsKey("Curvature") && !grouped.get("Curvature").isEmpty()) {
            result.put("Curvature", csrncCurvature.calculate(grouped.get("Curvature"), needDecompose));
        }

        return result;
    }

    private Map<String, List<Map<String, Object>>> groupAndSum(List<FrtbInput> dataList) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        Map<String, List<FrtbInput>> grouped = dataList.stream()
                .collect(Collectors.groupingBy(FrtbInput::getSensitivityType));

        if (grouped.containsKey(FrtbConstants.SENS_DELTA)) {
            result.put("Delta", sumByRiskFactor(grouped.get(FrtbConstants.SENS_DELTA), "Delta"));
        }
        if (grouped.containsKey(FrtbConstants.SENS_VEGA)) {
            result.put("Vega", sumByRiskFactor(grouped.get(FrtbConstants.SENS_VEGA), "Vega"));
        }

        // 处理 Curvature
        List<FrtbInput> curData = new ArrayList<>();
        if (grouped.containsKey(FrtbConstants.SENS_CURVATURE_UP))
            curData.addAll(grouped.get(FrtbConstants.SENS_CURVATURE_UP));
        if (grouped.containsKey(FrtbConstants.SENS_CURVATURE_DOWN))
            curData.addAll(grouped.get(FrtbConstants.SENS_CURVATURE_DOWN));

        if (!curData.isEmpty()) {
            result.put("Curvature", sumCurvatureByRiskFactor(curData));
        }

        return result;
    }

    private List<Map<String, Object>> sumByRiskFactor(List<FrtbInput> dataList, String type) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput m : dataList) {
            String bucket = m.getRiskFactorBucket();
            String id = m.getRiskFactorId();
            String vertex1 = m.getRiskFactorVertex1();
            String safeVertex1 = vertex1 == null ? "" : vertex1;
            String riskType = normalizeType(m.getRiskFactorType());
            String key = bucket + "|" + id;
            if ("Delta".equals(type)) {
                // CSR Delta 按 Vertex1 区分
                key = key + "|" + safeVertex1 + "|" + riskType;
            } else if ("Vega".equals(type)) {
                key = key + "|" + safeVertex1 + "|" + riskType;
            }

            summed.putIfAbsent(key,
                    createEmptyPosition(bucket, id, ("Delta".equals(type) || "Vega".equals(type)) ? safeVertex1 : null,
                            riskType));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, m);

            double value = getDouble(m.getSensitivityValRptCurrCny());
            if ("Delta".equals(type)) {
                // CSRNC Delta RW 锟?bucket 鏌 (MAR21.71)
                double rw = getCsrncRiskWeight(bucket);
                double ws = value * rw;
                pos.put("ws", getDouble(pos.get("ws")) + ws);
                pos.put("sensitivityValRptCurrCny",
                        getDouble(pos.get("sensitivityValRptCurrCny")) + value);
                pos.put("riskWeight", rw);
            } else if ("Vega".equals(type)) {
                double rw = 1.0;
                double weightedValue = value * rw;
                pos.put("vega", getDouble(pos.get("vega")) + weightedValue);
                pos.put("sensitivityValRptCurrCny",
                        getDouble(pos.get("sensitivityValRptCurrCny")) + value);
                pos.put("ws", getDouble(pos.get("ws")) + weightedValue);
                pos.put("riskWeight", rw);
            }
        }
        return new ArrayList<>(summed.values());
    }

    /**
     * 获取 CSRNC Delta 风险权重
     * 锟?FrtbParamsCache 鏌锛坆ucket 锟?RW锟?MAR21.71)
     */
    private double getCsrncRiskWeight(String bucket) {
        java.util.HashMap<String, Double> weights = com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache.getCsrncWeights();
        Double rw = weights.get(bucket);
        if (rw == null) {
            throw new IllegalArgumentException("未配置 CSRNC 风险权重: " + bucket);
        }
        return rw;
    }

    private List<Map<String, Object>> sumCurvatureByRiskFactor(List<FrtbInput> dataList) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput m : dataList) {
            String bucket = m.getRiskFactorBucket();
            String id = m.getRiskFactorId();
            String key = bucket + "|" + id;

            summed.putIfAbsent(key, createEmptyPosition(bucket, id, null, null));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, m);

            double value = getDouble(m.getSensitivityValRptCurrCny());
            if (FrtbConstants.SENS_CURVATURE_UP.equals(m.getSensitivityType()))
                pos.put("CVR_up", getDouble(pos.get("CVR_up")) + value);
            else
                pos.put("CVR_down", getDouble(pos.get("CVR_down")) + value);
        }
        return new ArrayList<>(summed.values());
    }

    private Map<String, Object> createEmptyPosition(String bucket, String id, String vertex1, String riskType) {
        Map<String, Object> pos = new HashMap<>();
        pos.put("riskFactorClass", FrtbConstants.RISK_CLASS_CSRNC);
        pos.put("riskFactorBucket", bucket);
        pos.put("riskFactorId", id);
        pos.put("riskFactorVertex1", vertex1);
        pos.put("riskFactorType", riskType);
        pos.put("sensitivityValRptCurrCny", 0.0);
        pos.put("ws", 0.0);
        pos.put("vega", 0.0);
        pos.put("CVR_up", 0.0);
        pos.put("CVR_down", 0.0);
        return pos;
    }

    private String normalizeType(String riskType) {
        if (riskType == null || riskType.trim().isEmpty()) {
            return "BOND";
        }
        return riskType.trim().toUpperCase(Locale.ROOT);
    }

    private void fillDimensionContext(Map<String, Object> pos, FrtbInput model) {
        pos.put("treeId", model.getTreeId());
        pos.put("groupType", model.getGroupType());
        pos.put("groupValue", model.getGroupValue());
    }

    private double getDouble(Object val) {
        if (val instanceof BigDecimal)
            return ((BigDecimal) val).doubleValue();
        if (val instanceof Number)
            return ((Number) val).doubleValue();
        return 0.0;
    }
}
