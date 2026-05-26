package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.eq.EqBarOpt;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;

/**
 * EqBarOpt 估值计算器
 */
public class EqBarOptCalc extends AbstractProductCacheCalc<EqBarOpt, EqBarOpt.EqBarOptInfo> {

    public EqBarOptCalc(String operCode, LocalDate dataDate,
            List<HashMap<String, Object>> trades, MarketData marketData) {
        super(operCode, dataDate, trades, marketData);
    }

    @Override
    protected EqBarOpt.EqBarOptInfo parseInfo(HashMap<String, Object> tradeData) {
        return JSONObject.parseObject(JSONObject.toJSONString(tradeData), EqBarOpt.EqBarOptInfo.class);
    }

    @Override
    protected String getInstrumentId(EqBarOpt.EqBarOptInfo info) {
        return info.instrumentId;
    }

    @Override
    protected EqBarOpt createProduct(EqBarOpt.EqBarOptInfo info, MarketData md) {
        return new EqBarOpt(dataDate, info, md);
    }

    @Override
    protected Measure doCalc(EqBarOpt product) {
        return product.calc();
    }

    @Override
    protected Measure doScenarioCalc(EqBarOpt product, MarketData scenarioMd) {
        return product.calc(scenarioMd);
    }
}
