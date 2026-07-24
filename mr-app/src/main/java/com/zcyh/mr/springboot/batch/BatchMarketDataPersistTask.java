package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.output.db.MarketDataResultWriter;
import com.zcyh.mr.springboot.output.db.MrCalcDetailCleanupService;
import org.springframework.stereotype.Component;

/**
 * 完整批次市场数据快照写入任务。
 */
@Component
public class BatchMarketDataPersistTask implements BatchRunTask {
    private final MarketDataResultWriter marketDataResultWriter;
    private final MrCalcDetailCleanupService cleanupService;

    public BatchMarketDataPersistTask(MarketDataResultWriter marketDataResultWriter,
                                      MrCalcDetailCleanupService cleanupService) {
        this.marketDataResultWriter = marketDataResultWriter;
        this.cleanupService = cleanupService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (context.isLocalRerun() || !context.isPersistResult()) {
            return;
        }
        cleanupService.cleanupMarketDataByBatchId(context.getBatchId());
        marketDataResultWriter.writeSnapshot(
                context.getBatchId(),
                context.getDataDate(),
                context.getLoadedMarketData());
    }
}
