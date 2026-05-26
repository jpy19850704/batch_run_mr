package com.zcyh.mr.product.basic.willow;

import java.util.Objects;

public class WillowModelConfig {
    public final String treeId;
    public final WillowUnderlyingType underlyingType;
    public final WillowModelType modelType;
    public final String willowReferenceCurve;
    public final String discountCurve;
    public final int stepDays;

    public WillowModelConfig(String treeId,
            WillowUnderlyingType underlyingType,
            WillowModelType modelType,
            String willowReferenceCurve,
            String discountCurve,
            int stepDays) {
        this.treeId = requireText(treeId, "TREE_ID");
        this.underlyingType = Objects.requireNonNull(underlyingType, "UNDERLYING_TYPE不能为空");
        this.modelType = Objects.requireNonNull(modelType, "MODEL_TYPE不能为空");
        this.willowReferenceCurve = requireText(willowReferenceCurve, "WILLOW_REFERENCE_CURVE");
        this.discountCurve = discountCurve == null || discountCurve.trim().isEmpty() ? null : discountCurve.trim();
        if (WillowUnderlyingType.EQUITY.equals(underlyingType)) {
            requireText(this.discountCurve, "DISCOUNT_CURVE");
        }
        if (stepDays <= 0) {
            throw new IllegalArgumentException("STEP_DAYS必须大于0");
        }
        this.stepDays = stepDays;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.trim();
    }
}
