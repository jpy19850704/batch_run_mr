package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.springboot.input.db.PortfolioHierarchyRepository;
import com.zcyh.mr.springboot.input.db.PortfolioHierarchyRow;
import com.zcyh.mr.springboot.input.db.TradeInputRepository;
import com.zcyh.mr.springboot.input.db.TradeInputRow;
import com.zcyh.mr.springboot.measurement.aggregation.AggregationRule;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IMA 资本汇总交易维度服务。
 */
@Service
public class ImaCapitalDimensionService {
    private final TradeInputRepository tradeInputRepository;
    private final PortfolioHierarchyRepository portfolioHierarchyRepository;

    public ImaCapitalDimensionService(
            TradeInputRepository tradeInputRepository,
            PortfolioHierarchyRepository portfolioHierarchyRepository) {
        this.tradeInputRepository = tradeInputRepository;
        this.portfolioHierarchyRepository = portfolioHierarchyRepository;
    }

    public Map<String, Map<String, String>> buildDimensionRows(LocalDate dataDate, AggregationRule rule) {
        List<TradeInputRow> trades =
                tradeInputRepository.findByFilter(dataDate, rule.getFilterTree());
        List<String> portfolios = new ArrayList<String>();
        for (TradeInputRow trade : trades) {
            String portfolio = trade.tradeDimensions.get("portfolio");
            if (trimToNull(portfolio) != null) {
                portfolios.add(portfolio);
            }
        }
        Map<String, PortfolioHierarchyRow> portfolioFlatRows =
                portfolioHierarchyRepository.findByPortfolioCodes(portfolios);

        Map<String, Map<String, String>> rows = new LinkedHashMap<String, Map<String, String>>();
        for (TradeInputRow trade : trades) {
            String instrumentId = trimToNull(trade.instrumentId);
            if (instrumentId == null) {
                continue;
            }
            if (rows.containsKey(instrumentId)) {
                throw new IllegalStateException("IMA 汇总规则匹配到重复交易ID: " + instrumentId);
            }
            Map<String, String> row = new LinkedHashMap<String, String>();
            put(row, "INSTRUMENT_ID", instrumentId);
            put(row, "PRODUCT_CODE", trade.productCode);
            put(row, "PORTFOLIO", trade.tradeDimensions.get("portfolio"));
            put(row, "DESK", trade.tradeDimensions.get("desk"));
            put(row, "TRADER", trade.tradeDimensions.get("trader"));
            PortfolioHierarchyRow flatRow =
                    portfolioFlatRows.get(trade.tradeDimensions.get("portfolio"));
            if (flatRow != null) {
                put(row, "PORTFOLIO_CODE_1", flatRow.portfolioCode1);
                put(row, "PORTFOLIO_CODE_2", flatRow.portfolioCode2);
                put(row, "PORTFOLIO_CODE_3", flatRow.portfolioCode3);
                put(row, "PORTFOLIO_CODE_4", flatRow.portfolioCode4);
                put(row, "PORTFOLIO_CODE_5", flatRow.portfolioCode5);
                put(row, "PORTFOLIO_CODE_6", flatRow.portfolioCode6);
                put(row, "PORTFOLIO_CODE_7", flatRow.portfolioCode7);
            }
            rows.put(instrumentId, row);
        }
        return rows;
    }

    private static void put(Map<String, String> row, String field, String value) {
        String safeField = trimToNull(field);
        if (safeField != null) {
            row.put(safeField.toUpperCase(Locale.ROOT), value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
