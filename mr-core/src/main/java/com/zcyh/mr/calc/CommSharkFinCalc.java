package com.zcyh.mr.calc;

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
public class CommSharkFinCalc extends AbstractProductCacheCalc<CommSharkFin, CommSharkFin.CommSharkFinInfo> {

    public CommSharkFinCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected CommSharkFin.CommSharkFinInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), CommSharkFin.CommSharkFinInfo.class);
    }

    @Override
    protected String getInstrumentId(CommSharkFin.CommSharkFinInfo info) {
        return info.instrumentId;
    }

    @Override
    protected CommSharkFin createProduct(CommSharkFin.CommSharkFinInfo info, MarketData md) {
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
