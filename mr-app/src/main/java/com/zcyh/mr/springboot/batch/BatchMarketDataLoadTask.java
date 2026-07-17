package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.MarketCurveInputRow;
import com.zcyh.mr.springboot.input.db.MarketDataInputRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 批量市场数据加载任务。
 */
@Component
public class BatchMarketDataLoadTask implements BatchRunTask {
    private final MarketDataInputRepository marketDataInputRepository;

    public BatchMarketDataLoadTask(MarketDataInputRepository marketDataInputRepository) {
        this.marketDataInputRepository = marketDataInputRepository;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        List<MarketCurveInputRow> loadedMarketData = marketDataInputRepository.findByDataDate(dataDate);
        if (loadedMarketData.isEmpty()) {
            throw new IllegalArgumentException("未查询到市场数据，请先加载 MR_MARKET_CURVE_INPUT");
        }
        context.setLoadedMarketData(loadedMarketData);
    }
}
