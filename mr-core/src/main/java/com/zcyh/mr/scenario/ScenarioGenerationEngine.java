package com.zcyh.mr.scenario;

import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.scenario.model.ScenarioGenerationRequest;
import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;
import com.zcyh.mr.scenario.strategy.CustomScenarioStrategy;
import com.zcyh.mr.scenario.strategy.HistoricalScenarioStrategy;
import com.zcyh.mr.scenario.strategy.McScenarioStrategy;
import com.zcyh.mr.scenario.strategy.ScenarioStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * 场景生成核心引擎。
 */
public class ScenarioGenerationEngine {
    private static final Logger log = LoggerFactory.getLogger(ScenarioGenerationEngine.class);
    private final Map<String, ScenarioStrategy> strategies;
    private final ExecutorService executor;

    public ScenarioGenerationEngine(ExecutorService executor, Calendar holidayCalendar) {
        this(executor, holidayCalendar, null);
    }

    public ScenarioGenerationEngine(ExecutorService executor,
                                    Calendar holidayCalendar,
                                    Map<String, ScenarioStrategy> strategyOverrides) {
        if (executor == null) {
            throw new IllegalArgumentException("scenarioExecutor 不能为空");
        }
        this.executor = executor;
        this.strategies = new HashMap<String, ScenarioStrategy>();
        ScenarioStrategy customStrategy = new CustomScenarioStrategy();
        ScenarioStrategy historicalStrategy = new HistoricalScenarioStrategy(holidayCalendar);
        strategies.put("CUSTOM", customStrategy);
        strategies.put("HISTORY", historicalStrategy);
        strategies.put("VAR", historicalStrategy);
        strategies.put("BACKTEST", historicalStrategy);
        strategies.put("SVAR", historicalStrategy);
        strategies.put("IMA_NORMAL", historicalStrategy);
        strategies.put("IMA_STRESS", historicalStrategy);
        strategies.put("IMA_NMRF", historicalStrategy);
        strategies.put("MC", new McScenarioStrategy(holidayCalendar));
        strategies.put("KEY_RATE", customStrategy);
        if (strategyOverrides != null && !strategyOverrides.isEmpty()) {
            strategies.putAll(strategyOverrides);
        }
    }

    public List<ScenarioGeneratedRecord> generate(ScenarioGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        List<ScenarioTaskRequest> tasks = request.getTasks();
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }

        List<CompletableFuture<List<ScenarioGeneratedRecord>>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> runSingleTask(task, request.getUser()), executor))
                .collect(Collectors.toList());

        List<ScenarioGeneratedRecord> result = futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ex);
                    } catch (ExecutionException ex) {
                        throw new RuntimeException(ex);
                    }
        })
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        return result;
    }

    private List<ScenarioGeneratedRecord> runSingleTask(ScenarioTaskRequest task, String user) {
        long startTime = System.currentTimeMillis();
        try {
            ScenarioStrategy strategy = strategies.get(task.getScenarioType());
            if (strategy == null) {
                throw new IllegalArgumentException("未找到情景策略: scenarioType="
                        + task.getScenarioType() + ", scenarioId=" + task.getScenarioId());
            }
            return strategy.generate(task, user);
        } catch (Exception ex) {
            log.error("情景任务处理异常: scenarioId={}, scenarioType={}, elapsedMs={}",
                    task.getScenarioId(), task.getScenarioType(), (System.currentTimeMillis() - startTime), ex);
            throw new IllegalStateException("情景任务处理失败: scenarioId=" + task.getScenarioId()
                    + ", scenarioType=" + task.getScenarioType(), ex);
        }
    }
}
