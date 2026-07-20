package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.comm.CommSharkFin;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * CommSharkFin 估值计算器
 */
public class CommSharkFinCalc extends AbstractProductCacheCalc<CommSharkFin, CommSharkFin.CommSharkFinTradeInfo> {

    public CommSharkFinCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommSharkFin.CommSharkFinTradeInfo parseTradeInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommSharkFin.CommSharkFinTradeInfo.class);
    }

    @Override
    protected String getInstrumentId(CommSharkFin.CommSharkFinTradeInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommSharkFin createProduct(CommSharkFin.CommSharkFinTradeInfo info, MarketData md) {
        return new CommSharkFin(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(CommSharkFin product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(CommSharkFin product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
