package com.zcyh.mr.frtbsa.sba.core.girr;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.common.FrtbParamsCache;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GIRR(General Interest Rate Risk)风险计算模块入口。
 * 负责调度和汇总利率风险的Delta、Vega和Curvature资本计算。
 * 支持多货币(USD, EUR, CNY等)的分组和聚合。
 */
public class GirrModule {

    private final GirrDelta girrDelta = new GirrDelta();
    private final GirrVega girrVega = new GirrVega();
    private final GirrCurvature girrCurvature = new GirrCurvature();

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
            result.put("Delta", girrDelta.calculate(grouped.get("Delta"), needDecompose));
        }

        if (grouped.containsKey("Vega") && !grouped.get("Vega").isEmpty()) {
            result.put("Vega", girrVega.calculate(grouped.get("Vega"), needDecompose));
        }

        // Curvature 基于 Shock 后的 PnL (CVR_up/down)，无需预先加权
        if (grouped.containsKey("Curvature") && !grouped.get("Curvature").isEmpty()) {
            result.put("Curvature", girrCurvature.calculate(grouped.get("Curvature"), needDecompose));
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

        List<FrtbInput> curvature = new ArrayList<>();
        if (grouped.containsKey(FrtbConstants.SENS_CURVATURE_UP))
            curvature.addAll(grouped.get(FrtbConstants.SENS_CURVATURE_UP));
        if (grouped.containsKey(FrtbConstants.SENS_CURVATURE_DOWN))
            curvature.addAll(grouped.get(FrtbConstants.SENS_CURVATURE_DOWN));

        if (!curvature.isEmpty()) {
            result.put("Curvature", sumCurvatureByRiskFactor(curvature));
        }

        return result;
    }

    /**
     * 按风险因子(riskFactorId)聚合敏感度
     * 不同曲线（如OIS vs Libor）在同一期限下不会被合并，保留基差风险计算能力。
     */
    private List<Map<String, Object>> sumByRiskFactor(List<FrtbInput> dataList, String type) {
        // 使用 LinkedHashMap 保持插入顺序
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();

        for (FrtbInput m : dataList) {
            String bucket = normalizeBucket(m.getRiskFactorBucket()); // Currency
            String id = m.getRiskFactorId();
            String vertex1 = m.getRiskFactorVertex1(); // e.g. "1Y"
            String vertex2 = m.getRiskFactorVertex2();

            if (id == null || id.isEmpty()) {
                id = bucket + "_" + vertex1;
            }

            String safeVertex1 = vertex1 == null ? "" : vertex1;
            String safeVertex2 = vertex2 == null ? "" : vertex2;
            String key = id;
            if ("Delta".equals(type)) {
                // GIRR Delta 必须按 Vertex1 区分，避免期限点被错误合并
                key = bucket + "|" + id + "|" + safeVertex1;
            } else if ("Vega".equals(type)) {
                // GIRR Vega 需要同时保留第一维和第二维。
                key = bucket + "|" + id + "|" + safeVertex1 + "|" + safeVertex2;
            }

            summed.putIfAbsent(key, createEmptyPosition(bucket, id, vertex1, vertex2, m.getRiskFactorType()));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, m);

            BigDecimal value = m.getSensitivityValRptCurrCny();
            if (value == null)
                value = BigDecimal.ZERO;

            if ("Delta".equals(type)) {
                // Delta: WS = Sensitivity 脳 RW
                double tenor = (double) pos.get("tenor");
                String riskType = (String) pos.get("riskFactorType");
                double rw = getGirrDeltaRiskWeight(bucket, tenor, riskType);
                BigDecimal rwBd = BigDecimal.valueOf(rw);
                BigDecimal weightedValue = value.multiply(rwBd);

                BigDecimal currentSensitivity = (BigDecimal) pos.get("sensitivityValRptCurrCny");
                BigDecimal newSensitivity = currentSensitivity.add(value);
                pos.put("sensitivityValRptCurrCny", newSensitivity);
                pos.put("ws", getDouble(pos.get("ws")) + weightedValue.doubleValue());
                pos.put("riskWeight", rw); // 增加 RiskWeight 字段

            } else if ("Vega".equals(type)) {
                // Vega: RW = min(55% 脳 鈭?60/10), 100%) = 100%
                double rw = 1.0;
                BigDecimal weightedValue = value.multiply(BigDecimal.valueOf(rw));
                BigDecimal currentSensitivity = (BigDecimal) pos.get("sensitivityValRptCurrCny");
                pos.put("sensitivityValRptCurrCny", currentSensitivity.add(value));
                pos.put("ws", getDouble(pos.get("ws")) + weightedValue.doubleValue());
                // vega 仅保留给现有计算器内部使用，与 ws 保持一致
                BigDecimal current = (BigDecimal) pos.get("vega_bd");
                BigDecimal newValue = current.add(weightedValue);
                pos.put("vega_bd", newValue);
                pos.put("vega", newValue.doubleValue());
                pos.put("riskWeight", rw); // GIRR Vega RW = 100%
            }
        }
        return new ArrayList<>(summed.values());
    }

    private List<Map<String, Object>> sumCurvatureByRiskFactor(List<FrtbInput> dataList) {
        Map<String, Map<String, Object>> summed = new LinkedHashMap<>();
        for (FrtbInput m : dataList) {
            String bucket = normalizeBucket(m.getRiskFactorBucket());
            String id = m.getRiskFactorId();
            // GIRR Curvature 只按 bucket(currency)汇总
            String key = bucket;
            String bucketId = (bucket == null || bucket.isEmpty()) ? id : bucket;
            summed.putIfAbsent(key, createEmptyPosition(bucket, bucketId, null, null, m.getRiskFactorType()));
            Map<String, Object> pos = summed.get(key);
            fillDimensionContext(pos, m);

            BigDecimal value = m.getSensitivityValRptCurrCny();
            if (value == null)
                value = BigDecimal.ZERO;

            if (FrtbConstants.SENS_CURVATURE_UP.equals(m.getSensitivityType())) {
                BigDecimal current = (BigDecimal) pos.get("CVR_up_bd");
                BigDecimal newValue = current.add(value);
                pos.put("CVR_up_bd", newValue);
                pos.put("CVR_up", newValue.doubleValue());
            } else {
                BigDecimal current = (BigDecimal) pos.get("CVR_down_bd");
                BigDecimal newValue = current.add(value);
                pos.put("CVR_down_bd", newValue);
                pos.put("CVR_down", newValue.doubleValue());
            }
        }
        return new ArrayList<>(summed.values());
    }

    private Map<String, Object> createEmptyPosition(String bucket, String id, String vertex1, String vertex2,
            String riskType) {
        Map<String, Object> pos = new HashMap<>();
        pos.put("riskFactorBucket", bucket);
        pos.put("riskFactorId", id);
        pos.put("riskFactorVertex1", vertex1); // 保留原始字符串
        pos.put("riskFactorVertex2", vertex2); // 新增 Vertex2
        pos.put("tenor", parseTenor(vertex1));
        pos.put("tenor2", parseTenor(vertex2)); // 解析 Vertex2

        // 1. RiskFactorType 保留原始字符串
        pos.put("riskFactorType", riskType != null ? riskType : "");
        // 2. CurveName 使用 riskFactorId 标识曲线
        pos.put("curveName", id);

        // 2. SensitivityValRptCurrCny 保留原始数值 (BigDecimal)
        pos.put("sensitivityValRptCurrCny", BigDecimal.ZERO);
        pos.put("ws", 0.0);

        pos.put("vega_bd", BigDecimal.ZERO);
        pos.put("vega", 0.0);

        pos.put("CVR_up_bd", BigDecimal.ZERO);
        pos.put("CVR_up", 0.0);

        pos.put("CVR_down_bd", BigDecimal.ZERO);
        pos.put("CVR_down", 0.0);
        return pos;
    }

    private void fillDimensionContext(Map<String, Object> pos, FrtbInput model) {
        pos.put("treeId", model.getTreeId());
        pos.put("groupType", model.getGroupType());
        pos.put("groupValue", model.getGroupValue());
    }

    private String normalizeBucket(String bucket) {
        return FrtbConstants.normalizeBucketForRiskClass(FrtbConstants.RISK_CLASS_GIRR, bucket);
    }

    private double parseTenor(String tenor) {
        if (tenor == null || tenor.isEmpty())
            return 0.0;
        try {
            String t = tenor.trim().toUpperCase();
            if (t.endsWith("Y"))
                return Double.parseDouble(t.replace("Y", ""));
            if (t.endsWith("M"))
                return Double.parseDouble(t.replace("M", "")) / 12.0;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double getDouble(Object value) {
        if (value instanceof BigDecimal)
            return ((BigDecimal) value).doubleValue();
        if (value instanceof Number)
            return ((Number) value).doubleValue();
        return 0.0;
    }

    /**
     * 获取 GIRR Delta 风险权重 (MAR21.45)
     * 通胀/基差风险因子: RW = 1.6%
     * 一般利率风险因子: 按期限查表
     * 可缩放币种: RW 再乘监管缩放系数
     */
    private double getGirrDeltaRiskWeight(String bucket, double tenor, String riskType) {
        return FrtbParamsCache.getGirrDeltaRiskWeight(bucket, tenor, riskType);
    }
}
