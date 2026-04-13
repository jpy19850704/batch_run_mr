package com.zcyh.mr.springboot.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 批量市场数据加载任务。
 */
@Component
public class BatchMarketDataLoadTask implements BatchRunTask {
    private final BatchTradeDataLoader dataLoader;

    public BatchMarketDataLoadTask(BatchTradeDataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        List<BatchTradeDataLoader.CurveRow> loadedMarketData = dataLoader.loadCurveRows(dataDate);
        if (loadedMarketData.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 MR_MARKET_CURVE_INPUT");
        }
        context.setLoadedMarketData(loadedMarketData);
    }
}
