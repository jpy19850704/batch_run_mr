package com.zcyh.mr.frtbsa.sba.core.cmty;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CMTY(Commodity)商品风险计算模块入口。
 * 负责各类商品(能源、农业、金属等)的风险资本计算。
 * 核心逻辑：
 * 1. Delta: 计算商品Delta风险资本
 * 2. Vega: 计算商品Vega风险资本
 * 3. Curvature: 计算商品Curvature风险资本
 */
public class CmtyModule {

    private final CmtyDelta cmtyDelta = new CmtyDelta();
    private final CmtyVega cmtyVega = new CmtyVega();
    private final CmtyCurvature cmtyCurvature = new CmtyCurvature();

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
            result.put("Delta", cmtyDelta.calculate(grouped.get("Delta"), needDecompose));
        }

        if (grouped.containsKey("Vega") && !grouped.get("Vega").isEmpty()) {
            result.put("Vega", cmtyVega.calculate(grouped.get("Vega"), needDecompose));
        }

        List<FrtbInput> curData = new ArrayList<>();
        if (grouped.containsKey("Curvature") && !grouped.get("Curvature").isEmpty()) {
            result.put("Curvature", cmtyCurvature.calculate(grouped.get("Curvature"), needDecompose));
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
            String vertex2 = m.getRiskFactorVertex2();
            String riskFactorType = m.getRiskFactorType();
            String key = buildRiskFactorKey(bucket, id, vertex1, vertex2, riskFactorType);

            summed.putIfAbsent(key, createEmptyPosition(bucket, id, vertex1, vertex2, riskFactorType));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, m);

            double value = getDouble(m.getSensitivityValRptCurrCny());
            if ("Delta".equals(type)) {
                // CMTY Delta RW 按 bucket 查找 (MAR21.82)
                double rw = getCmtyRiskWeight(bucket);
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
     * 获取 CMTY Delta 风险权重
     * 从 FrtbParamsCache 查找(bucket -> RW, MAR21.82)
     */
    private double getCmtyRiskWeight(String bucket) {
        java.util.HashMap<String, Double> weights = com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache.getCmtyWeights();
        Double rw = weights.get(bucket);
        if (rw == null) {
            throw new IllegalArgumentException("未配置 CMTY 风险权重: " + bucket);
        }
        return rw;
    }

    private List<Map<String, Object>> sumCurvatureByRiskFactor(List<FrtbInput> dataList) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput m : dataList) {
            String bucket = m.getRiskFactorBucket();
            String id = m.getRiskFactorId();
            String vertex1 = m.getRiskFactorVertex1();
            String vertex2 = m.getRiskFactorVertex2();
            String riskFactorType = m.getRiskFactorType();
            String key = buildRiskFactorKey(bucket, id, vertex1, vertex2, riskFactorType);

            summed.putIfAbsent(key, createEmptyPosition(bucket, id, vertex1, vertex2, riskFactorType));
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

    private Map<String, Object> createEmptyPosition(String bucket, String id, String vertex1, String vertex2,
            String riskFactorType) {
        Map<String, Object> pos = new HashMap<>();
        pos.put("riskFactorClass", FrtbConstants.RISK_CLASS_CMTY);
        pos.put("riskFactorBucket", bucket);
        pos.put("riskFactorId", id);
        pos.put("riskFactorVertex1", vertex1);
        pos.put("riskFactorVertex2", vertex2);
        pos.put("riskFactorType", riskFactorType);
        pos.put("sensitivityValRptCurrCny", 0.0);
        pos.put("ws", 0.0);
        pos.put("vega", 0.0);
        pos.put("CVR_up", 0.0);
        pos.put("CVR_down", 0.0);
        return pos;
    }

    private String buildRiskFactorKey(String bucket, String id, String vertex1, String vertex2, String riskFactorType) {
        return nullSafe(bucket) + "|" + nullSafe(id) + "|" + nullSafe(vertex1) + "|"
                + nullSafe(vertex2) + "|" + nullSafe(riskFactorType);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private void fillDimensionContext(Map<String, Object> pos, FrtbInput model) {
        pos.put("ruleId", model.getRuleId());
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
