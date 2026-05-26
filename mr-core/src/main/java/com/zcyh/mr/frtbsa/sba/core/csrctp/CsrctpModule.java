package com.zcyh.mr.frtbsa.sba.core.csrctp;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSR securitisations (CTP) 模块入口
 * 信用利差风险 — 证券化（关联交易组合）
 *
 * 根据 MAR21.56-21.64：
 * - 桶 1-16，按发行人信用评级和行业分类
 * - Delta 风险权重按桶查表（param.json 中 CSR (ctp)）
 * - 桶内相关性 ρ_same = 0.90（同一发行人），ρ_diff = 0.42（不同发行人）
 * - 跨桶相关性 γ = 0.18
 * - 支持 Delta / Vega / Curvature 三类敏感性
 */
public class CsrctpModule {

    private final CsrctpDelta csrctpDelta = new CsrctpDelta();
    private final CsrctpVega csrctpVega = new CsrctpVega();
    private final CsrctpCurvature csrctpCurvature = new CsrctpCurvature();

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
            result.put("Delta", csrctpDelta.calculate(grouped.get("Delta"), needDecompose));
        }

        if (grouped.containsKey("Vega") && !grouped.get("Vega").isEmpty()) {
            result.put("Vega", csrctpVega.calculate(grouped.get("Vega"), needDecompose));
        }

        if (grouped.containsKey("Curvature") && !grouped.get("Curvature").isEmpty()) {
            result.put("Curvature", csrctpCurvature.calculate(grouped.get("Curvature"), needDecompose));
        }

        return result;
    }

    /**
     * 按敏感性类型分组，并将同一 riskFactorId 的值合计
     */
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

        // Curvature Up + Down 合并
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
                // CTP Delta RW 按 bucket 查表（MAR21.59）
                double rw = getCtpRiskWeight(bucket);
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
     * 获取 CTP Delta 风险权重
     * 从 FrtbParamsCache 查询（bucket 对应 RW，MAR21.59）
     */
    private double getCtpRiskWeight(String bucket) {
        java.util.HashMap<String, Double> weights = com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache.getCtpWeights();
        Double rw = weights.get(bucket);
        if (rw == null) {
            throw new IllegalArgumentException("未配置 CSRCTP 风险权重: " + bucket);
        }
        return rw;
    }

    private List<Map<String, Object>> sumCurvatureByRiskFactor(List<FrtbInput> dataList) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput m : dataList) {
            String bucket = m.getRiskFactorBucket();
            String id = m.getRiskFactorId();
            String riskType = normalizeType(m.getRiskFactorType());
            String key = bucket + "|" + id;

            summed.putIfAbsent(key, createEmptyPosition(bucket, id, null, riskType));
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
        pos.put("riskFactorClass", FrtbConstants.RISK_CLASS_CSRCTP);
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

    private void fillDimensionContext(Map<String, Object> pos, FrtbInput model) {
        pos.put("treeId", model.getTreeId());
        pos.put("groupType", model.getGroupType());
        pos.put("groupValue", model.getGroupValue());
    }

    private String normalizeType(String riskType) {
        if (riskType == null || riskType.trim().isEmpty()) {
            return "BOND";
        }
        return riskType.trim().toUpperCase(Locale.ROOT);
    }

    private double getDouble(Object val) {
        if (val instanceof BigDecimal)
            return ((BigDecimal) val).doubleValue();
        if (val instanceof Number)
            return ((Number) val).doubleValue();
        return 0.0;
    }
}
