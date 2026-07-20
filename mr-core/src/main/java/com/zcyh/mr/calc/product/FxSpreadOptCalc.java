package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxSpreadOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxSpreadOpt 估值计算器
 */
public class FxSpreadOptCalc extends AbstractProductCacheCalc<FxSpreadOpt, FxSpreadOpt.SpreadOptTradeInfo> {

    public FxSpreadOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxSpreadOpt.SpreadOptTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxSpreadOpt.SpreadOptTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(FxSpreadOpt.SpreadOptTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxSpreadOpt createProduct(FxSpreadOpt.SpreadOptTradeInfo info, MarketData md) {
        return new FxSpreadOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxSpreadOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxSpreadOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
