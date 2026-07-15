package com.zcyh.mr.frtbsa.sba.core;

import com.zcyh.mr.frtbsa.sba.pojo.FrtbInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrtbBatchCalculatorTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldExcludeFailedTaskWithoutBlockingOtherTasks() {
        List<FrtbInput> validInput = Collections.singletonList(new FrtbInput());
        List<FrtbInput> invalidInput = Collections.singletonList(new FrtbInput());
        StubAggregator aggregator = new StubAggregator(invalidInput);
        FrtbBatchCalculator calculator = new FrtbBatchCalculator(executor, () -> aggregator);
        Map<String, List<FrtbInput>> tasks = new LinkedHashMap<String, List<FrtbInput>>();
        tasks.put("RULE|TOTAL", validInput);
        tasks.put("RULE|BROKEN", invalidInput);

        Map<String, Map<String, Object>> result = calculator.calculateBatch(tasks, false, 1);

        assertTrue(result.get("RULE|TOTAL").containsKey("ALL"));
        assertEquals("CALC_FAILED", result.get("RULE|BROKEN").get("ERROR_CODE"));
        assertEquals(2, result.size());
    }

    private static final class StubAggregator extends FrtbAggregator {
        private final List<FrtbInput> invalidInput;

        private StubAggregator(List<FrtbInput> invalidInput) {
            this.invalidInput = invalidInput;
        }

        @Override
        Map<String, Object> calculateRiskClassMap(List<FrtbInput> rawList, Boolean needDecompose) {
            if (rawList == invalidInput) {
                throw new IllegalArgumentException("测试任务异常");
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("GIRR", riskClassResult());
            return result;
        }

        private static Map<String, Object> riskClassResult() {
            Map<String, Object> capital = new LinkedHashMap<String, Object>();
            capital.put("capital_normal", 10.0);
            capital.put("capital_high", 12.0);
            capital.put("capital_low", 8.0);
            Map<String, Object> delta = new LinkedHashMap<String, Object>();
            delta.put("class", capital);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("Delta", delta);
            return result;
        }
    }
}
