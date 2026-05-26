package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxRangeAccureOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxRangeAccureOpt 估值计算器
 */
public class FxRangeAccureOptCalc extends AbstractProductCacheCalc<FxRangeAccureOpt, FxRangeAccureOpt.FxRangeAccureInfo> {

    public FxRangeAccureOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxRangeAccureOpt.FxRangeAccureInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxRangeAccureOpt.FxRangeAccureInfo.class);
    }

    @Override
    protected String getInstrumentId(FxRangeAccureOpt.FxRangeAccureInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxRangeAccureOpt createProduct(FxRangeAccureOpt.FxRangeAccureInfo info, MarketData md) {
        return new FxRangeAccureOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxRangeAccureOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxRangeAccureOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
