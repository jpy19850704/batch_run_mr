package com.zcyh.mr.springboot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批量交易分片任务。
 */
@Component
public class BatchChunkBuildTask implements BatchRunTask {
    private final TradeChunkSplitter chunkSplitter;
    private final int weightBudget;

    public BatchChunkBuildTask(
            TradeChunkSplitter chunkSplitter,
            @Value("${mr.batch.weight-budget:100}") int weightBudget) {
        this.chunkSplitter = chunkSplitter;
        this.weightBudget = Math.max(1, weightBudget);
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        List<List<BatchTradeDataLoader.TradeRow>> tradeChunks =
                chunkSplitter.splitChunks(context.getLoadedTrades(), weightBudget);
        if (tradeChunks.isEmpty()) {
            throw new IllegalStateException("交易分片结果为空，batchId=" + context.getBatchId());
        }
        context.setTradeChunks(tradeChunks);
    }
}
