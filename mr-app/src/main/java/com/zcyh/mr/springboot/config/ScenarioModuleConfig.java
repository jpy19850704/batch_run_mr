package com.zcyh.mr.springboot.config;

import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.scenario.strategy.ScenarioStrategy;
import com.zcyh.mr.springboot.ima.ImaRfetDataRepository;
import com.zcyh.mr.springboot.ima.ImaRfetScenarioAnnotator;
import com.zcyh.mr.springboot.ima.ImaScenarioStrategy;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.scenario.mapper.ScenarioMapper;
import com.zcyh.mr.springboot.service.AlertService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scenario 业务组件配置。
 */
@Configuration
public class ScenarioModuleConfig {

    @Bean
    @ConditionalOnBean({ScenarioMapper.class, Calendar.class})
    public ScenarioRequestAssembler scenarioRequestAssembler(
            ScenarioMapper scenarioMapper,
            @Qualifier("mrHolidayCalendar") Calendar mrHolidayCalendar,
            AlertService alertService,
            ImaRfetDataRepository imaRfetDataRepository,
            @Value("${mr.calendar.default-code:}") String defaultHolidayCalendarCode,
            @Value("${mr.fx-spot.base-currency:USD}") String fxSpotBaseCurrency) {
        return new ScenarioRequestAssembler(
                scenarioMapper,
                mrHolidayCalendar,
                alertService,
                imaRfetDataRepository,
                defaultHolidayCalendarCode,
                fxSpotBaseCurrency);
    }

    @Bean(name = "scenarioExecutor", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "mr.scenario.service", name = "enabled", havingValue = "true")
    public ExecutorService scenarioExecutor(
            @Value("${mr.scenario.executor.core-size:4}") int coreSize,
            @Value("${mr.scenario.executor.max-size:8}") int maxSize,
            @Value("${mr.scenario.executor.queue-capacity:1024}") int queueCapacity) {
        int normalizedCoreSize = Math.max(1, coreSize);
        int normalizedMaxSize = Math.max(normalizedCoreSize, maxSize);
        int normalizedQueueCapacity = Math.max(1, queueCapacity);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                normalizedCoreSize,
                normalizedMaxSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(normalizedQueueCapacity),
                namedThreadFactory("scenario-exec-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    @ConditionalOnBean({ScenarioRequestAssembler.class, ExecutorService.class, Calendar.class})
    public ScenarioGenerationEngine scenarioGenerationEngine(
            @Qualifier("scenarioExecutor") ExecutorService scenarioExecutor,
            @Qualifier("mrHolidayCalendar") Calendar mrHolidayCalendar,
            ImaRfetScenarioAnnotator imaRfetScenarioAnnotator) {
        Map<String, ScenarioStrategy> overrides = new LinkedHashMap<String, ScenarioStrategy>();
        ScenarioStrategy imaScenarioStrategy = new ImaScenarioStrategy(mrHolidayCalendar, imaRfetScenarioAnnotator);
        overrides.put("IMA_NORMAL", imaScenarioStrategy);
        overrides.put("IMA_STRESS", imaScenarioStrategy);
        overrides.put("IMA_NMRF", imaScenarioStrategy);
        return new ScenarioGenerationEngine(scenarioExecutor, mrHolidayCalendar, overrides);
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(prefix + seq.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
