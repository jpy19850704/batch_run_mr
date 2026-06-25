package com.zcyh.mr.frtbsa.sba.core.eq;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * EQ(Equity)权益风险计算模块入口。
 * 负责调度和汇总股票风险的Delta、Vega和Curvature资本计算。
 * 包含数据检查、分组和结果汇总逻辑。
 */
public class EqModule {

    private final EqDelta eqDelta = new EqDelta();
    private final EqVega eqVega = new EqVega();
    private final EqCurvature eqCurvature = new EqCurvature();

    public Map<String, Object> calc(List<FrtbInput> dataList) {
        return calc(dataList, true);
    }

    public Map<String, Object> calc(List<FrtbInput> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty())
            return new HashMap<>();

        Map<String, List<Map<String, Object>>> grouped = groupAndSum(dataList);
        Map<String, Object> result = new HashMap<>();

        if (grouped.containsKey("Delta") && !grouped.get("Delta").isEmpty()) {
            result.put("Delta", eqDelta.calculate(grouped.get("Delta"), needDecompose));
        }
        if (grouped.containsKey("Vega") && !grouped.get("Vega").isEmpty()) {
            result.put("Vega", eqVega.calculate(grouped.get("Vega"), needDecompose));
        }
        if (grouped.containsKey("Curvature") && !grouped.get("Curvature").isEmpty()) {
            result.put("Curvature", eqCurvature.calculate(grouped.get("Curvature"), needDecompose));
        }
        return result;
    }

    private Map<String, List<Map<String, Object>>> groupAndSum(List<FrtbInput> dataList) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();

        Map<String, List<FrtbInput>> groupedByType = dataList.stream()
                .collect(Collectors.groupingBy(FrtbInput::getSensitivityType));

        if (groupedByType.containsKey(FrtbConstants.SENS_DELTA)) {
            result.put("Delta", sumByRiskFactor(groupedByType.get(FrtbConstants.SENS_DELTA), "Delta"));
        }
        if (groupedByType.containsKey(FrtbConstants.SENS_VEGA)) {
            result.put("Vega", sumByRiskFactor(groupedByType.get(FrtbConstants.SENS_VEGA), "Vega"));
        }

        List<FrtbInput> curvatureData = new ArrayList<>();
        if (groupedByType.containsKey(FrtbConstants.SENS_CURVATURE_UP)) {
            curvatureData.addAll(groupedByType.get(FrtbConstants.SENS_CURVATURE_UP));
        }
        if (groupedByType.containsKey(FrtbConstants.SENS_CURVATURE_DOWN)) {
            curvatureData.addAll(groupedByType.get(FrtbConstants.SENS_CURVATURE_DOWN));
        }
        if (!curvatureData.isEmpty()) {
            result.put("Curvature", sumCurvatureByRiskFactor(curvatureData));
        }
        return result;
    }

    private List<Map<String, Object>> sumByRiskFactor(List<FrtbInput> dataList, String sensitivityType) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput model : dataList) {
            String id = model.getRiskFactorId();
            String bucket = model.getRiskFactorBucket();
            String vertex1 = model.getRiskFactorVertex1();
            String riskFactorType = normalizeEqRiskFactorType(model.getRiskFactorType());
            String key = bucket + "|" + id;
            if ("Vega".equals(sensitivityType)) {
                key = key + "|" + nullSafe(vertex1) + "|" + riskFactorType;
            }

            summed.putIfAbsent(key, createEmptyPosition(bucket, id,
                    "Vega".equals(sensitivityType) ? vertex1 : "", riskFactorType));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, model);

            double value = getDouble(model.getSensitivityValRptCurrCny());
            if ("Delta".equals(sensitivityType)) {
                // 从参数缓存获取 riskWeight
                double rw = getEqRiskWeight(bucket);
                double ws = value * rw;
                pos.put("ws", getDouble(pos.get("ws")) + ws);
                pos.put("sensitivityValRptCurrCny",
                        getDouble(pos.get("sensitivityValRptCurrCny")) + value);
                pos.put("riskWeight", rw);
            } else if ("Vega".equals(sensitivityType)) {
                // EQ Vega 风险权重 RW = 100% (MAR21.89)
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
     * 获取 EQ Delta 风险权重
     * 锟?FrtbParamsCache 鏌锛坆ucket 锟?RW锟?
     */
    private double getEqRiskWeight(String bucket) {
        java.util.HashMap<String, Double> weights = com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache.getEQWeights();
        Double rw = weights.get(bucket);
        if (rw == null) {
            throw new IllegalArgumentException("未配置 EQ 风险权重: " + bucket);
        }
        return rw;
    }

    private List<Map<String, Object>> sumCurvatureByRiskFactor(List<FrtbInput> dataList) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput model : dataList) {
            String id = model.getRiskFactorId();
            String bucket = model.getRiskFactorBucket();
            String key = bucket + "|" + id;

            summed.putIfAbsent(key, createEmptyPosition(bucket, id, "", model.getRiskFactorType()));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, model);

            double value = getDouble(model.getSensitivityValRptCurrCny());
            // CVR 累加
            if (FrtbConstants.SENS_CURVATURE_UP.equals(model.getSensitivityType())) {
                pos.put("CVR_up", getDouble(pos.get("CVR_up")) + value);
            } else if (FrtbConstants.SENS_CURVATURE_DOWN.equals(model.getSensitivityType())) {
                pos.put("CVR_down", getDouble(pos.get("CVR_down")) + value);
            }
        }
        return new ArrayList<>(summed.values());
    }

    private Map<String, Object> createEmptyPosition(String bucket, String id, String vertex, String riskFactorType) {
        Map<String, Object> pos = new HashMap<>();
        pos.put("riskFactorClass", FrtbConstants.RISK_CLASS_EQ);
        pos.put("riskFactorBucket", bucket);
        pos.put("riskFactorId", id);
        pos.put("riskFactorVertex1", vertex);
        // EQ 口径：riskFactorType 为空时统一回退为 Spot
        pos.put("riskFactorType", normalizeEqRiskFactorType(riskFactorType));
        pos.put("sensitivityValRptCurrCny", 0.0);
        pos.put("ws", 0.0);
        pos.put("vega", 0.0);
        pos.put("CVR_up", 0.0);
        pos.put("CVR_down", 0.0);
        return pos;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private void fillDimensionContext(Map<String, Object> pos, FrtbInput model) {
        pos.put("ruleId", model.getRuleId());
        pos.put("groupType", model.getGroupType());
        pos.put("groupValue", model.getGroupValue());
    }

    private String normalizeEqRiskFactorType(String riskFactorType) {
        if (riskFactorType == null || riskFactorType.trim().isEmpty()) {
            return "Spot";
        }
        return riskFactorType.trim();
    }

    private double getDouble(Object value) {
        if (value == null)
            return 0.0;
        if (value instanceof BigDecimal)
            return ((BigDecimal) value).doubleValue();
        if (value instanceof Number)
            return ((Number) value).doubleValue();
        return 0.0;
    }
}
