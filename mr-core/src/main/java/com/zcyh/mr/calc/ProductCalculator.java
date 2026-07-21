package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.loader.TradeValidator;
import com.zcyh.mr.marketdata.MarketData;

import java.util.List;
import java.util.Set;

/**
 * 产品计算器统一契约。
 */
public interface ProductCalculator {

    Class<?> tradeInputType();

    String calc();

    JSONArray calcScenario(MarketData scenarioMd);

    default JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        return calcScenario(scenarioMd);
    }

    default List<String> validateTradeInput(JSONObject tradeData) {
        String productCode = tradeData == null ? null : tradeData.getString("PRODUCT_CODE");
        return TradeValidator.validate(tradeData, productCode, "TRADE");
    }
}
