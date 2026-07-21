package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractCalc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.basic.common.Measure;
import com.zcyh.mr.product.ir.BondFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BondFutureCalc extends AbstractCalc {
    private static final Logger log = LoggerFactory.getLogger(BondFutureCalc.class);
    private final Map<String, BondFuture> bondFutureCache = new LinkedHashMap<>();

    public BondFutureCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar, JSONObject otherData) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected void calcTrade(HashMap<String, Object> tradeData) {
        BondFuture.BondFutureTradeInfo info = JSONObject.parseObject(JSON.toJSONString(tradeData),
                BondFuture.BondFutureTradeInfo.class);
        info.validateInput(new JSONObject(tradeData), String.valueOf(tradeData.get("PRODUCT_CODE")))
                .throwIfInvalid();
        JSONObject underlyingData = indexUnderlyingDataByBondId(tradeData.get("UNDERLYING_DATA"));
        BondFuture future = new BondFuture(dataDate, info, marketData, calendar, underlyingData);
        bondFutureCache.put(info.instrumentId, future);
        trade.add(future.calc());
    }

    @Override
    public List<String> validateTradeInput(JSONObject tradeData) {
        BondFuture.BondFutureTradeInfo info = JSONObject.parseObject(JSON.toJSONString(tradeData),
                BondFuture.BondFutureTradeInfo.class);
        return info.validateInput(tradeData, tradeData.getString("PRODUCT_CODE")).getErrors();
    }

    @Override
    protected void runScenarioLoop(MarketData scenarioMd, Set<String> affectedIds, JSONArray scenarioRst) {
        for (Map.Entry<String, BondFuture> entry : bondFutureCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            try {
                Measure measure = entry.getValue().calc(scenarioMd);
                ensureScenarioInstrumentId(measure, entry.getKey());
                scenarioRst.add(measure);
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
    }

    @Override
    protected void handleError(HashMap<String, Object> tradeData, Exception e) {
        log.error("BondFuture 计算异常, instrumentId={}",
                java.util.Objects.toString(tradeData.get("INSTRUMENT_ID"), ""), e);
        super.handleError(tradeData, e);
    }

}
