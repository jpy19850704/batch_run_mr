package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.zcyh.mr.marketdata.MarketData;

import java.util.Set;

/**
 * 产品计算器统一契约。
 */
public interface ProductCalculator {

    String calc();

    void run();

    JSONArray calcScenario(MarketData scenarioMd);

    default JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        return calcScenario(scenarioMd);
    }
}
