package com.zcyh.mr.springboot.config;

import com.zcyh.mr.springboot.execution.ExecutionAdapter;
import com.zcyh.mr.springboot.execution.ExecutionRegistry;
import com.zcyh.mr.springboot.measurement.frtb.FrtbDrcExecutionAdapter;
import com.zcyh.mr.springboot.measurement.frtb.FrtbSaExecutionAdapter;
import com.zcyh.mr.springboot.measurement.ima.ImaCapitalExecutionAdapter;
import com.zcyh.mr.springboot.measurement.valuation.ValuationExecutionAdapter;
import com.zcyh.mr.springboot.measurement.saccr.SaccrExecutionAdapter;
import com.zcyh.mr.springboot.measurement.cva.CvaExecutionAdapter;
import com.zcyh.mr.springboot.measurement.ima.ImaCapitalTrialService;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.core.FrtbBatchCalculator;
import com.zcyh.mr.frtbsa.sba.core.FrtbResultMapper;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.springboot.scenario.ScenarioExecutionAdapter;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.scenario.SharedScenarioInputLoader;
import com.zcyh.mr.springboot.measurement.ima.ImaRiskFactorConfigService;
import com.zcyh.mr.springboot.output.db.ScenarioDetailPersistService;
import com.zcyh.mr.springboot.output.cache.ScenarioDetailCacheService;
import com.zcyh.mr.springboot.output.db.SaccrResultPersistService;
import com.zcyh.mr.springboot.output.db.CvaResultPersistService;
import com.zcyh.mr.springboot.measurement.saccr.SaccrInputQueryService;
import com.zcyh.mr.springboot.measurement.cva.CvaInputQueryService;
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
 * 计量执行注册配置。
 */
@Configuration
public class ExecutionRegistryConfig {

    @Bean
    public ValuationExecutionAdapter valuationExecutionAdapter(
            ImaRiskFactorConfigService imaRiskFactorConfigService,
            SharedScenarioInputLoader sharedScenarioInputLoader) {
        return new ValuationExecutionAdapter(imaRiskFactorConfigService, sharedScenarioInputLoader);
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService frtbBatchExecutor(
            @Value("${mr.frtb.batch-executor.core-size:4}") int coreSize,
            @Value("${mr.frtb.batch-executor.max-size:8}") int maxSize,
            @Value("${mr.frtb.batch-executor.queue-capacity:512}") int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                coreSize,
                Math.max(coreSize, maxSize),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Bean
    public FrtbAggregator frtbAggregator() {
        return new FrtbAggregator();
    }

    @Bean
    public FrtbBatchCalculator frtbBatchCalculator(
            @Qualifier("frtbBatchExecutor") ExecutorService frtbBatchExecutor) {
        return new FrtbBatchCalculator(frtbBatchExecutor);
    }

    @Bean
    public FrtbResultMapper frtbResultMapper() {
        return new FrtbResultMapper();
    }

    @Bean
    public FrtbSaExecutionAdapter frtbSaExecutionAdapter(FrtbAggregator frtbAggregator) {
        return new FrtbSaExecutionAdapter(frtbAggregator);
    }

    @Bean
    public FrtbDrcExecutionAdapter frtbDrcExecutionAdapter() {
        return new FrtbDrcExecutionAdapter();
    }

    @Bean
    public SaccrExecutionAdapter saccrExecutionAdapter(SaccrInputQueryService saccrInputQueryService,
                                                 SaccrResultPersistService saccrResultPersistService) {
        return new SaccrExecutionAdapter(saccrInputQueryService, saccrResultPersistService);
    }

    @Bean
    public CvaExecutionAdapter cvaExecutionAdapter(CvaInputQueryService cvaInputQueryService,
                                                   CvaResultPersistService cvaResultPersistService) {
        return new CvaExecutionAdapter(cvaInputQueryService, cvaResultPersistService);
    }

    @Bean
    public ScenarioExecutionAdapter scenarioExecutionAdapter(
            ObjectProvider<ScenarioGenerationEngine> scenarioGenerationEngineProvider,
            ObjectProvider<ScenarioRequestAssembler> scenarioRequestAssemblerProvider,
            ObjectProvider<ScenarioDetailPersistService> scenarioDetailPersistServiceProvider,
            ObjectProvider<ScenarioDetailCacheService> scenarioDetailCacheServiceProvider) {
        return new ScenarioExecutionAdapter(
                scenarioGenerationEngineProvider.getIfAvailable(),
                scenarioRequestAssemblerProvider.getIfAvailable(),
                scenarioDetailPersistServiceProvider.getIfAvailable(),
                scenarioDetailCacheServiceProvider.getIfAvailable());
    }

    @Bean
    public ImaCapitalExecutionAdapter imaCapitalExecutionAdapter(ImaCapitalTrialService trialService) {
        return new ImaCapitalExecutionAdapter(trialService);
    }

    @Bean
    public ExecutionRegistry executionRegistry(List<ExecutionAdapter> executionAdapters) {
        return new ExecutionRegistry(executionAdapters);
    }
}
