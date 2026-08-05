package com.zcyh.mr.product.ir;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CapFloorFrequencyTest {

    @Test
    void resolveFrequency_whenResetMissing_shouldUsePayFrequency() {
        Assertions.assertEquals("3M", CapFloor.resolveFrequency(null, "3M"));
    }

    @Test
    void resolveFrequency_whenFixingMissing_shouldUseEffectiveResetFrequency() {
        String resetFrequency = CapFloor.resolveFrequency("", "3M");

        Assertions.assertEquals("3M", CapFloor.resolveFrequency(null, resetFrequency));
    }

    @Test
    void resolveFrequency_whenConfigured_shouldKeepConfiguredFrequency() {
        Assertions.assertEquals("5Y", CapFloor.resolveFrequency("5Y", "1M"));
    }
}
