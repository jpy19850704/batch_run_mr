package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqSpreadOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqSpreadOpt 估值计算器
 */
public class EqSpreadOptCalc extends AbstractProductCacheCalc<EqSpreadOpt, EqSpreadOpt.SpreadOptInfo> {

    public EqSpreadOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqSpreadOpt.SpreadOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqSpreadOpt.SpreadOptInfo.class);
    }

    @Override
    protected String getInstrumentId(EqSpreadOpt.SpreadOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqSpreadOpt createProduct(EqSpreadOpt.SpreadOptInfo info, MarketData md) {
        return new EqSpreadOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqSpreadOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqSpreadOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
