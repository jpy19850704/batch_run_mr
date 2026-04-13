package com.zcyh.mr.springboot.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 批量交易加载任务。
 */
@Component
public class BatchTradeLoadTask implements BatchRunTask {
    private final BatchTradeDataLoader dataLoader;

    public BatchTradeLoadTask(BatchTradeDataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        List<BatchTradeDataLoader.TradeRow> loadedTrades = dataLoader.loadTradeRows(dataDate, null, null);
        if (loadedTrades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 dataDate 条件");
        }
        context.setLoadedTrades(loadedTrades);
    }
}
