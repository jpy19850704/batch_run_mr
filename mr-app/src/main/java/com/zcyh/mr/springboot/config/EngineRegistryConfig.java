package com.zcyh.mr.springboot.config;

import com.zcyh.mr.springboot.engine.EngineAdapter;
import com.zcyh.mr.springboot.engine.EngineRegistry;
import com.zcyh.mr.springboot.engine.FrtbDrcEngineAdapter;
import com.zcyh.mr.springboot.engine.FrtbSaEngineAdapter;
import com.zcyh.mr.springboot.engine.ImaCapitalEngineAdapter;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.engine.SaccrEngineAdapter;
import com.zcyh.mr.frtbsa.sba.core.FrtbAggregator;
import com.zcyh.mr.scenario.ScenarioGenerationEngine;
import com.zcyh.mr.springboot.engine.ScenarioEngineAdapter;
import com.zcyh.mr.springboot.scenario.ScenarioRequestAssembler;
import com.zcyh.mr.springboot.out.db.ImaCapitalResultPersistService;
import com.zcyh.mr.springboot.out.db.ImaEsResultDetailPersistService;
import com.zcyh.mr.springboot.out.db.ImaNmrfResultPersistService;
import com.zcyh.mr.springboot.service.BatchTradeDataLoader;
import com.zcyh.mr.springboot.out.db.CalcRuleMetaPersistService;
import com.zcyh.mr.springboot.service.DimensionAggregationService;
import com.zcyh.mr.springboot.service.FrtbDrcDbRunnerService;
import com.zcyh.mr.springboot.service.FrtbSbaDbRunnerService;
import com.zcyh.mr.springboot.service.FrtbSbaDbRunnerService;
import com.zcyh.mr.springboot.service.ImaRiskFactorConfigService;
import com.zcyh.mr.springboot.out.db.ScenarioDetailResultService;
import com.zcyh.mr.springboot.out.cache.ScenarioResultCacheService;
import com.zcyh.mr.springboot.out.db.SaccrResultPersistService;
import com.zcyh.mr.springboot.saccr.SaccrInputQueryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
            @Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir,
            ImaRiskFactorConfigService imaRiskFactorConfigService) {
        return new MrCalcEngineAdapter(scenarioSetRootDir, imaRiskFactorConfigService);
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
    public ImaCapitalEngineAdapter imaCapitalEngineAdapter(
            @Qualifier("engineDbJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate engineDbJdbcTemplate,
            @Qualifier("engineResultDbJdbcTemplate") org.springframework.jdbc.core.JdbcTemplate engineResultDbJdbcTemplate,
            BatchTradeDataLoader batchTradeDataLoader,
            CalcRuleMetaPersistService calcRuleMetaPersistService,
            DimensionAggregationService dimensionAggregationService,
            FrtbSbaDbRunnerService frtbSbaDbRunnerService,
            ImaCapitalResultPersistService imaCapitalResultPersistService,
            ImaEsResultDetailPersistService imaEsResultDetailPersistService,
            ImaNmrfResultPersistService imaNmrfResultPersistService) {
        return new ImaCapitalEngineAdapter(
                engineDbJdbcTemplate,
                engineResultDbJdbcTemplate,
                batchTradeDataLoader,
                calcRuleMetaPersistService,
                dimensionAggregationService,
                frtbSbaDbRunnerService,
                imaCapitalResultPersistService,
                imaEsResultDetailPersistService,
                imaNmrfResultPersistService);
    }

    @Bean
    public EngineRegistry engineRegistry(List<EngineAdapter> engineAdapters) {
        return new EngineRegistry(engineAdapters);
    }
}
