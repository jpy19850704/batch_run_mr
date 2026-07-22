package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.springboot.output.file.BatchResultStageService;
import com.zcyh.mr.springboot.output.file.ScenarioSetPathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Coordinator 启动时清理上次运行遗留的未完成任务。
 */
@Service
public class BatchStartupResetService {
    private static final Logger log = LoggerFactory.getLogger(BatchStartupResetService.class);

    private final BatchJobStateRepository batchJobStateRepository;
    private final AsyncJobStateRepository asyncJobStateRepository;
    private final BatchResultStageService batchResultStageService;
    private final ScenarioSetPathResolver scenarioSetPathResolver;
    private final boolean coordinatorEnabled;

    public BatchStartupResetService(
            BatchJobStateRepository batchJobStateRepository,
            AsyncJobStateRepository asyncJobStateRepository,
            BatchResultStageService batchResultStageService,
            ScenarioSetPathResolver scenarioSetPathResolver,
            @Value("${mr.batch.coordinator.enabled:${MR_BATCH_COORDINATOR_ENABLED:true}}")
            boolean coordinatorEnabled) {
        this.batchJobStateRepository = batchJobStateRepository;
        this.asyncJobStateRepository = asyncJobStateRepository;
        this.batchResultStageService = batchResultStageService;
        this.scenarioSetPathResolver = scenarioSetPathResolver;
        this.coordinatorEnabled = coordinatorEnabled;
    }

    @PostConstruct
    void resetUnfinishedTasks() {
        if (!coordinatorEnabled) {
            log.info("当前实例未启用Coordinator启动重置");
            return;
        }
        List<BatchJobStateRepository.BatchJobRow> activeBatches = batchJobStateRepository.findActiveBatchRows();
        for (BatchJobStateRepository.BatchJobRow batch : activeBatches) {
            batchResultStageService.resetBatch(batch.batchId);
            if (batch.dataDate != null) {
                scenarioSetPathResolver.resetBatchDirectory(
                        batch.dataDate.format(DateTimeFormatter.BASIC_ISO_DATE), batch.batchId);
            }
            CalcScenarioInputCache.evictByBatchId(batch.batchId);
            batchJobStateRepository.clearExistingBatchData(batch.batchId);
            log.warn("Coordinator重启已重置未完成批次，需重新提交计量: batchId={}", batch.batchId);
        }
        int orphanJobs = asyncJobStateRepository.deleteNonTerminalJobs();
        if (!activeBatches.isEmpty() || orphanJobs > 0) {
            log.warn("Coordinator启动重置完成: batchCount={}, orphanJobCount={}",
                    activeBatches.size(), orphanJobs);
        }
    }
}
