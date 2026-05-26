package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.fx.FxSharkFin;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * FxSharkFin 估值计算器
 */
public class FxSharkFinCalc extends AbstractProductCacheCalc<FxSharkFin, FxSharkFin.FxSharkFinInfo> {

    public FxSharkFinCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected FxSharkFin.FxSharkFinInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), FxSharkFin.FxSharkFinInfo.class);
    }

    @Override
    protected String getInstrumentId(FxSharkFin.FxSharkFinInfo info) {
        return info.instrumentId;
    }

    @Override
    protected FxSharkFin createProduct(FxSharkFin.FxSharkFinInfo info, MarketData md) {
        return new FxSharkFin(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(FxSharkFin product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(FxSharkFin product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
