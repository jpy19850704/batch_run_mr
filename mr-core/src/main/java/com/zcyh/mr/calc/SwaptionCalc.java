package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.Swaption;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * Swaption 估值计算器
 */
public class SwaptionCalc extends AbstractProductCacheCalc<Swaption, Swaption.SwaptionInfo> {

    public SwaptionCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData, Calendar calendar) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected Swaption.SwaptionInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), Swaption.SwaptionInfo.class);
    }

    @Override
    protected String getInstrumentId(Swaption.SwaptionInfo info) {
        return info.instrumentId;
    }

    @Override
    protected Swaption createProduct(Swaption.SwaptionInfo info, MarketData md) {
        return new Swaption(dataDate, info, md, calendar);
    }

    @Override
    protected Measure doCalc(Swaption product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(Swaption product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
