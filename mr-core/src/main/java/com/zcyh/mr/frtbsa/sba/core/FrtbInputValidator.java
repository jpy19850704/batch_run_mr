package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * FRTB SBA 输入校验器。
 */
final class FrtbInputValidator {
    private static final List<String> VALID_SENSITIVITY_TYPES = Arrays.asList(
            FrtbConstants.SENS_DELTA,
            FrtbConstants.SENS_VEGA,
            FrtbConstants.SENS_CURVATURE_UP,
            FrtbConstants.SENS_CURVATURE_DOWN);
    private static final Set<String> CMTY_TENORS = new HashSet<>(Arrays.asList(
            "0", "0.25", "0.5", "1", "2", "3", "5", "10", "15", "20", "30"));

    Map<String, Object> validate(List<FrtbInput> dataList) {
        List<FrtbInput> validData = new ArrayList<>();
        List<FrtbInput> errorData = new ArrayList<>();
        List<Map<String, Object>> errorDetails = new ArrayList<>();
        Map<FrtbInput, Integer> sourceRowNoMap = new IdentityHashMap<>();

        if (dataList == null || dataList.isEmpty()) {
            return buildResult(validData, errorData, errorDetails);
        }

        int rowNo = 0;
        for (FrtbInput model : dataList) {
            rowNo++;
            boolean invalid = false;
            if (model == null) {
                errorDetails.add(buildError("NULL_INPUT", "输入记录为空", null, rowNo));
                continue;
            }
            sourceRowNoMap.put(model, rowNo);
            model.setRiskFactorBucket(FrtbConstants.normalizeBucketForRiskClass(
                    model.getRiskFactorClass(), model.getRiskFactorBucket()));

            if (!FrtbConstants.isValidRiskClass(model.getRiskFactorClass())) {
                invalid = true;
                errorDetails.add(buildError("INVALID_RISK_FACTOR_CLASS", "风险类别缺失或非法", model, rowNo));
            }
            String sensitivityType = model.getSensitivityType();
            if (sensitivityType == null || !VALID_SENSITIVITY_TYPES.contains(sensitivityType)) {
                invalid = true;
                errorDetails.add(buildError("INVALID_SENSITIVITY_TYPE",
                        "敏感性类型必须为 Delta、Vega、Curvature Up 或 Curvature Down", model, rowNo));
            }
            if (isBlank(model.getRiskFactorBucket())) {
                invalid = true;
                errorDetails.add(buildError("MISSING_RISK_FACTOR_BUCKET", "风险因子桶不能为空", model, rowNo));
            }
            if (!invalid && !validateStandardVertices(model, rowNo, errorDetails)) {
                invalid = true;
            }
            if (isGirr(model) && !invalid && !validateGirrTenor(model, rowNo, errorDetails)) {
                invalid = true;
            }
            if (invalid) {
                errorData.add(model);
            } else {
                validData.add(model);
            }
        }

        Map<String, List<FrtbInput>> curvatureGroups = validData.stream()
                .filter(item -> item.getSensitivityType().startsWith("Curvature"))
                .collect(Collectors.groupingBy(this::buildCurvaturePairKey));
        for (Map.Entry<String, List<FrtbInput>> entry : curvatureGroups.entrySet()) {
            List<FrtbInput> group = entry.getValue();
            boolean hasUp = group.stream()
                    .anyMatch(item -> FrtbConstants.SENS_CURVATURE_UP.equals(item.getSensitivityType()));
            boolean hasDown = group.stream()
                    .anyMatch(item -> FrtbConstants.SENS_CURVATURE_DOWN.equals(item.getSensitivityType()));
            if (hasUp && hasDown) {
                continue;
            }
            for (FrtbInput item : group) {
                errorDetails.add(buildError("CURVATURE_PAIR_MISSING",
                        "Curvature Up 与 Curvature Down 必须成对出现，分组键=" + entry.getKey(),
                        item, sourceRowNoMap.getOrDefault(item, -1)));
            }
            errorData.addAll(group);
            validData.removeAll(group);
        }
        return buildResult(validData, errorData, errorDetails);
    }

    private static Map<String, Object> buildResult(List<FrtbInput> validData,
            List<FrtbInput> errorData, List<Map<String, Object>> errorDetails) {
        Map<String, Object> result = new HashMap<>();
        result.put("checked", validData);
        result.put("errors", errorData);
        result.put("errorDetails", errorDetails);
        return result;
    }

    private boolean validateStandardVertices(FrtbInput model, int rowNo,
            List<Map<String, Object>> errorDetails) {
        if (!isBlank(model.getRiskFactorVertex1())
                && parseStandardVertexStrict(model.getRiskFactorVertex1()) == null) {
            errorDetails.add(buildError("INVALID_VERTEX1", "风险因子期限1必须为数字年", model, rowNo));
            return false;
        }
        if (!isBlank(model.getRiskFactorVertex2())
                && parseStandardVertexStrict(model.getRiskFactorVertex2()) == null) {
            errorDetails.add(buildError("INVALID_VERTEX2", "风险因子期限2必须为数字年", model, rowNo));
            return false;
        }
        String riskClass = model.getRiskFactorClass();
        String sensitivityType = model.getSensitivityType();
        if (FrtbConstants.SENS_VEGA.equals(sensitivityType)) {
            if (!requirePositiveVertex(model.getRiskFactorVertex1(), "MISSING_VERTEX1",
                    "Vega 风险因子期限1不能为空且必须大于0", model, rowNo, errorDetails)) {
                return false;
            }
            if (FrtbConstants.RISK_CLASS_GIRR.equals(riskClass)
                    && !requirePositiveVertex(model.getRiskFactorVertex2(), "MISSING_VERTEX2",
                    "GIRR Vega 风险因子期限2不能为空且必须大于0", model, rowNo, errorDetails)) {
                return false;
            }
        }
        if (FrtbConstants.SENS_DELTA.equals(sensitivityType)
                && (FrtbConstants.RISK_CLASS_CSRNS.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRNC.equals(riskClass)
                || FrtbConstants.RISK_CLASS_CSRCTP.equals(riskClass))
                && !requirePositiveVertex(model.getRiskFactorVertex1(), "MISSING_VERTEX1",
                "CSR Delta 风险因子期限1不能为空且必须大于0", model, rowNo, errorDetails)) {
            return false;
        }
        if (FrtbConstants.SENS_DELTA.equals(sensitivityType)
                && FrtbConstants.RISK_CLASS_CMTY.equals(riskClass)) {
            Double tenor = parseStandardVertexStrict(model.getRiskFactorVertex1());
            if (tenor == null || !CMTY_TENORS.contains(tenorKey(tenor))) {
                errorDetails.add(buildError("INVALID_CMTY_VERTEX1",
                        "CMTY Delta 风险因子期限1不能为空且必须为监管标准期限", model, rowNo));
                return false;
            }
        }
        return true;
    }

    private boolean requirePositiveVertex(String vertex, String errorCode, String message,
            FrtbInput model, int rowNo, List<Map<String, Object>> errorDetails) {
        Double parsed = parseStandardVertexStrict(vertex);
        if (parsed == null || parsed <= 0) {
            errorDetails.add(buildError(errorCode, message, model, rowNo));
            return false;
        }
        return true;
    }

    private boolean validateGirrTenor(FrtbInput model, int rowNo,
            List<Map<String, Object>> errorDetails) {
        String sensitivityType = model.getSensitivityType();
        String riskType = model.getRiskFactorType() == null ? "" : model.getRiskFactorType().toUpperCase();
        if (FrtbConstants.SENS_CURVATURE_UP.equals(sensitivityType)
                || FrtbConstants.SENS_CURVATURE_DOWN.equals(sensitivityType)) {
            return true;
        }
        boolean needsVertex1 = FrtbConstants.SENS_VEGA.equals(sensitivityType)
                || (!riskType.contains("INFLA") && !riskType.contains("BASIS"));
        if (!needsVertex1) {
            return true;
        }
        Double tenor = parseStandardVertexStrict(model.getRiskFactorVertex1());
        if (tenor == null) {
            errorDetails.add(buildError("INVALID_GIRR_VERTEX1",
                    "GIRR 风险因子期限1不能为空且必须为数字年", model, rowNo));
            return false;
        }
        if (tenor <= 0) {
            errorDetails.add(buildError("INVALID_GIRR_VERTEX1",
                    "GIRR 风险因子期限1必须大于0", model, rowNo));
            return false;
        }
        return true;
    }

    private static Double parseStandardVertexStrict(String vertex) {
        if (isBlank(vertex)) {
            return null;
        }
        String normalized = vertex.trim();
        if (!normalized.matches("\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            double value = Double.parseDouble(normalized);
            return Double.isFinite(value) && value >= 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String tenorKey(double tenor) {
        return tenor == (long) tenor ? String.valueOf((long) tenor) : String.valueOf(tenor);
    }

    private static Map<String, Object> buildError(String code, String message, FrtbInput model, int rowNo) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("rowNo", rowNo);
        if (model != null) {
            error.put("riskFactorClass", model.getRiskFactorClass());
            error.put("sensitivityType", model.getSensitivityType());
            error.put("riskFactorId", model.getRiskFactorId());
            error.put("riskFactorBucket", model.getRiskFactorBucket());
            error.put("riskFactorVertex1", model.getRiskFactorVertex1());
            error.put("riskFactorVertex2", model.getRiskFactorVertex2());
            error.put("groupType", model.getGroupType());
            error.put("groupValue", model.getGroupValue());
            error.put("dataDate", model.getDataDate());
        }
        return error;
    }

    private String buildCurvaturePairKey(FrtbInput input) {
        String bucket = FrtbConstants.normalizeBucketForRiskClass(
                input.getRiskFactorClass(), input.getRiskFactorBucket());
        if (FrtbConstants.RISK_CLASS_GIRR.equals(input.getRiskFactorClass())) {
            return input.getRiskFactorClass() + "@" + bucket;
        }
        return input.getRiskFactorClass() + "@" + input.getRiskFactorId() + "@" + bucket;
    }

    private static boolean isGirr(FrtbInput input) {
        return FrtbConstants.RISK_CLASS_GIRR.equals(input.getRiskFactorClass());
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
