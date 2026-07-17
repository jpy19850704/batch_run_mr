package com.zcyh.mr.calc.product;

import com.zcyh.mr.calc.AbstractCalc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.calendar.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.credit.Cds;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CdsCalc extends AbstractCalc {
    private final Map<String, Cds> cdsCache = new LinkedHashMap<>();

    public CdsCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar, JSONObject otherData) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected void calcTrade(HashMap<String, Object> tradeData) {
        Cds.CdsInfo info = JSONObject.parseObject(tradeData.toString(), Cds.CdsInfo.class);
        Cds cds = new Cds(dataDate, info, marketData, calendar,
                indexUnderlyingDataByBondId(tradeData.get("UNDERLYING_DATA")));
        cdsCache.put(info.instrumentId, cds);
        trade.add(cds.calc());
    }

    @Override
    protected void runScenarioLoop(MarketData scenarioMd, Set<String> affectedIds, JSONArray scenarioRst) {
        for (Map.Entry<String, Cds> entry : cdsCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            try {
                scenarioRst.add(entry.getValue().calc(scenarioMd));
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
    }

}
