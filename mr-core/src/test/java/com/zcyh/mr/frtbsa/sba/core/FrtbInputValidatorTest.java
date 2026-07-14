package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.common.FrtbConstants;
import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrtbInputValidatorTest {

    private final FrtbInputValidator validator = new FrtbInputValidator();

    @Test
    void validate_whenInputIsValid_shouldKeepInput() {
        FrtbInput input = baseInput(FrtbConstants.RISK_CLASS_EQ, FrtbConstants.SENS_DELTA);

        Map<String, Object> result = validator.validate(Collections.singletonList(input));

        assertEquals(1, list(result, "checked").size());
        assertEquals(0, list(result, "errors").size());
    }

    @Test
    void validate_whenCurvaturePairIsMissing_shouldRejectInput() {
        FrtbInput input = baseInput(FrtbConstants.RISK_CLASS_EQ, FrtbConstants.SENS_CURVATURE_UP);

        Map<String, Object> result = validator.validate(Collections.singletonList(input));

        assertEquals(0, list(result, "checked").size());
        assertEquals(1, list(result, "errors").size());
        assertEquals(1, list(result, "errorDetails").size());
    }

    @Test
    void validate_whenCommodityTenorIsNotStandard_shouldRejectInput() {
        FrtbInput input = baseInput(FrtbConstants.RISK_CLASS_CMTY, FrtbConstants.SENS_DELTA);
        input.setRiskFactorVertex1("4");

        Map<String, Object> result = validator.validate(Collections.singletonList(input));

        assertEquals(0, list(result, "checked").size());
        assertEquals(1, list(result, "errors").size());
    }

    private static FrtbInput baseInput(String riskClass, String sensitivityType) {
        FrtbInput input = new FrtbInput();
        input.setRiskFactorClass(riskClass);
        input.setSensitivityType(sensitivityType);
        input.setRiskFactorId("RF001");
        input.setRiskFactorBucket("1");
        return input;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> result, String key) {
        return (List<Object>) result.get(key);
    }
}
