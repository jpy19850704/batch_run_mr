package com.zcyh.mr.frtbsa.sba.core.fx;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FX(Forex)风险计算模块入口。
 * 负责调度和汇总外汇风险的Delta、Vega和Curvature资本计算。
 * 包含数据检查、FX Bucket(常为单一Bucket)分组和结果汇总逻辑。
 */
public class FxModule {

    private final FxDelta fxDelta = new FxDelta();
    private final FxVega fxVega = new FxVega();
    private final FxCurvature fxCurvature = new FxCurvature();

    public Map<String, Object> calc(List<FrtbInput> dataList) {
        return calc(dataList, true);
    }

    public Map<String, Object> calc(List<FrtbInput> dataList, Boolean needDecompose) {
        if (dataList == null || dataList.isEmpty()) {
            return new HashMap<>();
        }

        // 1. 按敏感性类型分组
        Map<String, List<Map<String, Object>>> groupedData = groupAndSum(dataList);

        Map<String, Object> resultMap = new HashMap<>();

        // 2. 计算 Delta
        if (groupedData.containsKey("Delta") && !groupedData.get("Delta").isEmpty()) {
            resultMap.put("Delta", fxDelta.calculate(groupedData.get("Delta"), needDecompose));
        }

        // 3. 计算 Vega
        if (groupedData.containsKey("Vega") && !groupedData.get("Vega").isEmpty()) {
            resultMap.put("Vega", fxVega.calculate(groupedData.get("Vega"), needDecompose));
        }

        // 4. 计算 Curvature
        if (groupedData.containsKey("Curvature") && !groupedData.get("Curvature").isEmpty()) {
            resultMap.put("Curvature", fxCurvature.calculate(groupedData.get("Curvature"), needDecompose));
        }

        return resultMap;
    }

    /**
     * 将原始数据分组并汇总
     */
    private Map<String, List<Map<String, Object>>> groupAndSum(List<FrtbInput> dataList) {
        Map<String, List<Map<String, Object>>> result = new HashMap<>();

        // 按敏感性类型分组
        Map<String, List<FrtbInput>> groupedByType = dataList.stream()
                .collect(Collectors.groupingBy(FrtbInput::getSensitivityType));

        // 处理 Delta
        if (groupedByType.containsKey(FrtbConstants.SENS_DELTA)) {
            result.put("Delta", sumByRiskFactor(groupedByType.get(FrtbConstants.SENS_DELTA), "Delta"));
        }

        // 处理 Vega
        if (groupedByType.containsKey(FrtbConstants.SENS_VEGA)) {
            result.put("Vega", sumByRiskFactor(groupedByType.get(FrtbConstants.SENS_VEGA), "Vega"));
        }

        // 处理 Curvature (Up 和 Down 合并处理)
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

    /**
     * 按风险因子汇总数据 (Delta/Vega)
     */
    private List<Map<String, Object>> sumByRiskFactor(List<FrtbInput> dataList, String sensitivityType) {
        Map<String, Map<String, Object>> summedMap = new LinkedHashMap<>();

        for (FrtbInput model : dataList) {
            String riskFactorId = model.getRiskFactorId();
            String bucket = normalizeBucket(model.getRiskFactorBucket());
            String vertex1 = model.getRiskFactorVertex1();
            String riskFactorType = model.getRiskFactorType();
            String key = (bucket == null ? "" : bucket) + "|" + riskFactorId;
            if ("Vega".equals(sensitivityType)) {
                key = key + "|" + nullSafe(vertex1) + "|" + nullSafe(riskFactorType);
            }

            summedMap.putIfAbsent(key, createEmptyPosition(riskFactorId, bucket,
                    "Vega".equals(sensitivityType) ? vertex1 : null, riskFactorType));
            Map<String, Object> pos = summedMap.get(key);
            fillDimensionContext(pos, model);

            double value = getDouble(model.getSensitivityValRptCurrCny());

            if ("Delta".equals(sensitivityType)) {
                // 加权敏感度公式：WS = Sensitivity * RW (MAR21.88)
                double rw = getFxDeltaRiskWeight(bucket);
                double weightedValue = value * rw;
                pos.put("sensitivityValRptCurrCny",
                        getDouble(pos.get("sensitivityValRptCurrCny")) + value);
                pos.put("ws", getDouble(pos.get("ws")) + weightedValue);
                pos.put("riskWeight", rw);
            } else if ("Vega".equals(sensitivityType)) {
                // Vega RW = min(RW_蟽 脳 鈭?LH/10), 100%) = min(55% 脳 鈭?, 100%) = 100%
                double rw = 1.0;
                double weightedValue = value * rw;
                pos.put("sensitivityValRptCurrCny",
                        getDouble(pos.get("sensitivityValRptCurrCny")) + value);
                pos.put("ws", getDouble(pos.get("ws")) + weightedValue);
                // vega 仅保留给现有计算器内部使用，与 ws 保持一致
                pos.put("vega", getDouble(pos.get("vega")) + weightedValue);
                pos.put("riskWeight", rw); // FX Vega RW = 100%
            }
        }

        return new ArrayList<>(summedMap.values());
    }

    /**
     * 按风险因子汇总数据 (Curvature)
     */
    private List<Map<String, Object>> sumCurvatureByRiskFactor(List<FrtbInput> dataList) {
        Map<String, Map<String, Object>> summedMap = new LinkedHashMap<>();

        for (FrtbInput model : dataList) {
            String riskFactorId = model.getRiskFactorId();
            String bucket = normalizeBucket(model.getRiskFactorBucket());
            String type = model.getSensitivityType();
            String key = (bucket == null ? "" : bucket) + "|" + riskFactorId;

            summedMap.putIfAbsent(key, createEmptyPosition(riskFactorId, bucket, null, model.getRiskFactorType()));
            Map<String, Object> pos = summedMap.get(key);
            fillDimensionContext(pos, model);

            double value = getDouble(model.getSensitivityValRptCurrCny());

            if (FrtbConstants.SENS_CURVATURE_UP.equals(type)) {
                pos.put("CVR_up", getDouble(pos.get("CVR_up")) + value);
            } else if (FrtbConstants.SENS_CURVATURE_DOWN.equals(type)) {
                pos.put("CVR_down", getDouble(pos.get("CVR_down")) + value);
            }
        }

        return new ArrayList<>(summedMap.values());
    }

    private Map<String, Object> createEmptyPosition(String riskFactorId, String bucket, String vertex1,
            String riskFactorType) {
        Map<String, Object> pos = new HashMap<>();
        pos.put("riskFactorId", riskFactorId);
        pos.put("riskFactorBucket", bucket != null ? bucket : "FX");
        pos.put("riskFactorClass", FrtbConstants.RISK_CLASS_FX);
        pos.put("riskFactorVertex1", vertex1);
        pos.put("riskFactorType", riskFactorType);
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
        pos.put("treeId", model.getTreeId());
        pos.put("groupType", model.getGroupType());
        pos.put("groupValue", model.getGroupValue());
    }

    private String normalizeBucket(String bucket) {
        return FrtbConstants.normalizeBucketForRiskClass(FrtbConstants.RISK_CLASS_FX, bucket);
    }

    /**
     * 获取 FX Delta 风险权重 (MAR21.88)
     * 按 Scalar.FX 的基础权重与可缩放币种集合推导。
     * 
     * @param bucket 风险因子桶 (币种标识)
     */
    private double getFxDeltaRiskWeight(String bucket) {
        if (bucket == null || bucket.trim().isEmpty()) {
            throw new IllegalArgumentException("FX Delta 风险因子 bucket 不能为空");
        }
        return FrtbParamsCache.getFxRiskWeight(bucket);
    }

    private double getDouble(Object value) {
        if (value == null)
            return 0.0;
        if (value instanceof BigDecimal)
            return ((BigDecimal) value).doubleValue();
        if (value instanceof Number)
            return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
