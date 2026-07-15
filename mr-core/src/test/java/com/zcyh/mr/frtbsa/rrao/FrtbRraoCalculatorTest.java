package com.zcyh.mr.frtbsa.rrao;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrtbRraoCalculatorTest {

    @Test
    void shouldCalculateCapitalByRraoType() {
        List<FrtbRraoCalculator.Input> inputs = Arrays.asList(
                new FrtbRraoCalculator.Input("TOTAL", "TOTAL", "EXOTIC", new BigDecimal("100")),
                new FrtbRraoCalculator.Input("TOTAL", "TOTAL", "OTHER", new BigDecimal("200")));

        List<FrtbRraoCalculator.Result> results = new FrtbRraoCalculator().calculate(inputs);

        assertEquals(2, results.size());
        assertEquals(new BigDecimal("1.00"), results.get(0).getCapital());
        assertEquals(new BigDecimal("0.200"), results.get(1).getCapital());
    }

    @Test
    void shouldRejectMalformedSystemInput() {
        List<FrtbRraoCalculator.Input> inputs = Collections.singletonList(
                new FrtbRraoCalculator.Input("TOTAL", "TOTAL", null, BigDecimal.ONE));

        assertThrows(IllegalArgumentException.class, () -> new FrtbRraoCalculator().calculate(inputs));
    }
}
