package com.zcyh.mr.product.basic.frtb.builder;

import com.zcyh.mr.product.basic.common.OptionMeasure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EqSensitivityBuilderTest {

    @Test
    void missingBucket_shouldSkipDependenciesAndWriteTopLevelWarning() {
        OptionMeasure measure = new OptionMeasure();

        assertTrue(EqSensitivityBuilder.buildDeltaDependencies("EQ_CURVE", null).isEmpty());
        assertTrue(EqSensitivityBuilder.buildVegaDependencies("EQ_VOL", "EQ_CURVE", null).isEmpty());
        assertTrue(EqSensitivityBuilder.warnMissingSensitivityInputs(measure, null));
        assertEquals(1, measure.logs.size());
        assertEquals("WARNING", measure.logs.get(0).level);
        assertEquals("FRTB_EQ_BUCKET为空，跳过EQ敏感性计算", measure.logs.get(0).message);
    }

    @Test
    void configuredBucket_shouldBuildDependenciesWithoutWarning() {
        OptionMeasure measure = new OptionMeasure();

        assertEquals("7", EqSensitivityBuilder.buildDeltaDependencies("EQ_CURVE", "7").get(0).bucket);
        assertEquals("7", EqSensitivityBuilder.buildVegaDependencies("EQ_VOL", "EQ_CURVE", "7").get(0).bucket);
        assertTrue(!EqSensitivityBuilder.warnMissingSensitivityInputs(measure, "7"));
        assertTrue(measure.logs == null || measure.logs.isEmpty());
    }
}
