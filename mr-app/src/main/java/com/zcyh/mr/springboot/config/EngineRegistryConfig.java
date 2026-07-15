package com.zcyh.mr.springboot.config;

import com.zcyh.mr.springboot.engine.EngineAdapter;
import com.zcyh.mr.springboot.engine.EngineRegistry;
import com.zcyh.mr.springboot.engine.FrtbDrcEngineAdapter;
import com.zcyh.mr.springboot.engine.FrtbSaEngineAdapter;
import com.zcyh.mr.springboot.engine.ImaCapitalEngineAdapter;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.engine.SaccrEngineAdapter;
import com.zcyh.mr.springboot.ima.ImaCapitalTrialService;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.frtbsa.sba.core.FrtbBatchCalculator;
import com.zcyh.mr.frtbsa.sba.core.FrtbResultMapper;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.springboot.engine.ScenarioEngineAdapter;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.out.file.ScenarioSetPathResolver;
import com.zcyh.mr.springboot.service.ImaRiskFactorConfigService;
import com.zcyh.mr.springboot.out.db.ScenarioDetailResultService;
import com.zcyh.mr.springboot.out.cache.ScenarioResultCacheService;
import com.zcyh.mr.springboot.out.db.SaccrResultPersistService;
import com.zcyh.mr.springboot.saccr.SaccrInputQueryService;
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
            ScenarioSetPathResolver scenarioSetPathResolver,
            ImaRiskFactorConfigService imaRiskFactorConfigService) {
        return new MrCalcEngineAdapter(scenarioSetPathResolver, imaRiskFactorConfigService);
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
    public FrtbSaEngineAdapter frtbSaEngineAdapter(FrtbAggregator frtbAggregator) {
        return new FrtbSaEngineAdapter(frtbAggregator);
    }

    @Bean
    public FrtbDrcEngineAdapter frtbDrcEngineAdapter() {
        return new FrtbDrcEngineAdapter();
    }

    @Bean
    public SaccrEngineAdapter saccrEngineAdapter(SaccrInputQueryService saccrInputQueryService,
                                                 SaccrResultPersistService saccrResultPersistService) {
        return new SaccrEngineAdapter(saccrInputQueryService, saccrResultPersistService);
    }

    @Bean
    public ScenarioEngineAdapter scenarioEngineAdapter(
            ObjectProvider<ScenarioGenerationEngine> scenarioGenerationEngineProvider,
            ObjectProvider<ScenarioRequestAssembler> scenarioRequestAssemblerProvider,
            ObjectProvider<ScenarioDetailResultService> scenarioGeneratedPersistServiceProvider,
            ObjectProvider<ScenarioResultCacheService> scenarioResultCacheServiceProvider) {
        return new ScenarioEngineAdapter(
                scenarioGenerationEngineProvider.getIfAvailable(),
                scenarioRequestAssemblerProvider.getIfAvailable(),
                scenarioGeneratedPersistServiceProvider.getIfAvailable(),
                scenarioResultCacheServiceProvider.getIfAvailable());
    }

    @Bean
    public ImaCapitalEngineAdapter imaCapitalEngineAdapter(ImaCapitalTrialService trialService) {
        return new ImaCapitalEngineAdapter(trialService);
    }

    @Bean
    public EngineRegistry engineRegistry(List<EngineAdapter> engineAdapters) {
        return new EngineRegistry(engineAdapters);
    }
}
