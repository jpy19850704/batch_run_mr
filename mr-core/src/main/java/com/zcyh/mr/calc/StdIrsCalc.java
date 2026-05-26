package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.StdIrs;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * 标准利率互换估值计算器。
 */
public class StdIrsCalc extends AbstractProductCacheCalc<StdIrs, StdIrs.StdIrsInfo> {

    public StdIrsCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected StdIrs.StdIrsInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), StdIrs.StdIrsInfo.class);
    }

    @Override
    protected String getInstrumentId(StdIrs.StdIrsInfo info) {
        return info.instrumentId;
    }

    @Override
    protected StdIrs createProduct(StdIrs.StdIrsInfo info, MarketData md) {
        return new StdIrs(dataDate, info, md, calendar);
    }

    @Override
    protected Measure doCalc(StdIrs product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(StdIrs product, MarketData scenarioMd) {
        return product.calcWithMarketData(scenarioMd);
    }
}
