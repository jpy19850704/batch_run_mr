package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractProductCacheCalc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqSharkFin;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqSharkFin 估值计算器
 */
public class EqSharkFinCalc extends AbstractProductCacheCalc<EqSharkFin, EqSharkFin.EqSharkFinInfo> {

    public EqSharkFinCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqSharkFin.EqSharkFinInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqSharkFin.EqSharkFinInfo.class);
    }

    @Override
    protected String getInstrumentId(EqSharkFin.EqSharkFinInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqSharkFin createProduct(EqSharkFin.EqSharkFinInfo info, MarketData md) {
        return new EqSharkFin(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqSharkFin product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqSharkFin product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
