package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.out.cache.TradeInfoCacheService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 批量交易加载任务。
 */
@Component
public class BatchTradeLoadTask implements BatchRunTask {
    private final BatchTradeDataLoader dataLoader;
    private final TradeInfoCacheService tradeInfoCacheService;

    public BatchTradeLoadTask(BatchTradeDataLoader dataLoader,
                              TradeInfoCacheService tradeInfoCacheService) {
        this.dataLoader = dataLoader;
        this.tradeInfoCacheService = tradeInfoCacheService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        List<BatchTradeDataLoader.TradeRow> loadedTrades = dataLoader.loadTradeRows(dataDate, context.getTradeFilter());
        if (loadedTrades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 dataDate 条件");
        }
        context.setLoadedTrades(loadedTrades);
        if (context.isCacheScenarioResult()) {
            tradeInfoCacheService.putBatchTradeInfo(
                    context.getBatchId(),
                    context.getDataDate(),
                    loadedTrades,
                    dataLoader.loadPortfolioFlatByCodes(collectPortfolioCodes(loadedTrades)));
        }
    }

    private static List<String> collectPortfolioCodes(List<BatchTradeDataLoader.TradeRow> trades) {
        List<String> portfolioCodes = new ArrayList<String>();
        if (trades == null) {
            return portfolioCodes;
        }
        for (BatchTradeDataLoader.TradeRow trade : trades) {
            if (trade == null || trade.tradeDimensions == null) {
                continue;
            }
            String portfolio = trade.tradeDimensions.get("portfolio");
            if (portfolio != null) {
                portfolioCodes.add(portfolio);
            }
        }
        return portfolioCodes;
    }
}
