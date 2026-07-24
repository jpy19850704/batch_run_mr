package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.PortfolioHierarchyRepository;
import com.zcyh.mr.springboot.input.db.TradeInputRepository;
import com.zcyh.mr.springboot.input.db.TradeInputRow;
import com.zcyh.mr.springboot.output.cache.TradeInfoCacheService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量交易加载任务。
 */
@Component
public class BatchTradeLoadTask implements BatchRunTask {
    private final TradeInputRepository tradeInputRepository;
    private final PortfolioHierarchyRepository portfolioHierarchyRepository;
    private final TradeInfoCacheService tradeInfoCacheService;

    public BatchTradeLoadTask(TradeInputRepository tradeInputRepository,
                              PortfolioHierarchyRepository portfolioHierarchyRepository,
                              TradeInfoCacheService tradeInfoCacheService) {
        this.tradeInputRepository = tradeInputRepository;
        this.portfolioHierarchyRepository = portfolioHierarchyRepository;
        this.tradeInfoCacheService = tradeInfoCacheService;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        List<TradeInputRow> loadedTrades;
        if (context.isLocalRerun()) {
            loadedTrades = tradeInputRepository.findByInstrumentIds(context.getDataDate(), context.getInstrumentIds());
            ensureAllInstrumentIdsLoaded(context.getInstrumentIds(), loadedTrades);
        } else {
            loadedTrades = tradeInputRepository.findByFilter(context.getDataDate(), context.getTradeFilter());
        }
        if (loadedTrades.isEmpty()) {
            throw new IllegalArgumentException("未查询到交易数据，请检查 dataDate 条件");
        }
        context.setLoadedTrades(loadedTrades);
        if (context.isCacheScenarioResult() && !context.isLocalRerun()) {
            tradeInfoCacheService.putBatchTradeInfo(
                    context.getBatchId(),
                    com.zcyh.mr.springboot.support.ResultDbDateSupport.protocolDate(context.getDataDate()),
                    loadedTrades,
                    portfolioHierarchyRepository.findByPortfolioCodes(collectPortfolioCodes(loadedTrades)));
        }
    }

    private static void ensureAllInstrumentIdsLoaded(List<String> instrumentIds, List<TradeInputRow> trades) {
        Set<String> loadedInstrumentIds = new LinkedHashSet<String>();
        for (TradeInputRow trade : trades) {
            if (trade != null && trade.instrumentId != null) {
                loadedInstrumentIds.add(trade.instrumentId);
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String instrumentId : instrumentIds) {
            if (!loadedInstrumentIds.contains(instrumentId)) {
                missing.add(instrumentId);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("以下 instrumentId 未查询到输入交易: " + String.join(", ", missing));
        }
    }

    private static List<String> collectPortfolioCodes(List<TradeInputRow> trades) {
        List<String> portfolioCodes = new ArrayList<String>();
        if (trades == null) {
            return portfolioCodes;
        }
        for (TradeInputRow trade : trades) {
            if (trade == null) {
                continue;
            }
            String portfolio = trade.getTextAttribute("PORTFOLIO");
            if (portfolio != null) {
                portfolioCodes.add(portfolio);
            }
        }
        return portfolioCodes;
    }
}
