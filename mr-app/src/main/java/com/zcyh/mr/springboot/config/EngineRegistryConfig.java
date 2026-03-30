package com.zcyh.mr.springboot.config;

import com.zcyh.mr.springboot.engine.EngineAdapter;
import com.zcyh.mr.springboot.engine.EngineRegistry;
import com.zcyh.mr.springboot.engine.FrtbDrcEngineAdapter;
import com.zcyh.mr.springboot.engine.FrtbSaEngineAdapter;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.engine.ScenarioEngineAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.List;

/**
 * 引擎注册配置。
 */
@Configuration
public class EngineRegistryConfig {

    @Bean
    public MrCalcEngineAdapter mrCalcEngineAdapter(
            @Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir) {
        return new MrCalcEngineAdapter(scenarioSetRootDir);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService frtbBatchExecutor(
            @Value("${mr.frtb.batch-executor.core-size:4}") int coreSize,
            @Value("${mr.frtb.batch-executor.max-size:8}") int maxSize,
            @Value("${mr.frtb.batch-executor.queue-capacity:512}") int queueCapacity) {
        return new ThreadPoolExecutor(
                coreSize,
                Math.max(coreSize, maxSize),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean
    public FrtbAggregator frtbAggregator(@Qualifier("frtbBatchExecutor") ExecutorService frtbBatchExecutor) {
        return new FrtbAggregator(frtbBatchExecutor);
    }

    @Bean
    public FrtbSaEngineAdapter frtbSaEngineAdapter(FrtbAggregator frtbAggregator) {
        return new FrtbSaEngineAdapter(frtbAggregator);
    }

    @Bean
    public FrtbDrcEngineAdapter frtbDrcEngineAdapter() {
        return new FrtbDrcEngineAdapter();
    }

    @Bean
    public ScenarioEngineAdapter scenarioEngineAdapter(
            ObjectProvider<ScenarioGenerationEngine> scenarioGenerationEngineProvider,
            ObjectProvider<ScenarioRequestAssembler> scenarioRequestAssemblerProvider) {
        return new ScenarioEngineAdapter(
                scenarioGenerationEngineProvider.getIfAvailable(),
                scenarioRequestAssemblerProvider.getIfAvailable());
    }

    @Bean
    public EngineRegistry engineRegistry(List<EngineAdapter> engineAdapters) {
        return new EngineRegistry(engineAdapters);
    }
}
