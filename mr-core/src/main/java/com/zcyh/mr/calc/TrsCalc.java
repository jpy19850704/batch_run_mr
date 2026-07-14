package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.credit.Trs;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TrsCalc extends AbstractCalc {
    private final Map<String, Trs> trsCache = new LinkedHashMap<>();

    public TrsCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar, JSONObject otherData) {
        super(operCode, dataDate, trades, marketData, calendar);
    }

    @Override
    protected void calcTrade(HashMap<String, Object> tradeData) {
        Trs.TrsInfo info = JSONObject.parseObject(tradeData.toString(), Trs.TrsInfo.class);
        Trs trs = new Trs(dataDate, info, marketData, calendar,
                indexUnderlyingDataByBondId(tradeData.get("UNDERLYING_DATA")));
        trsCache.put(info.instrumentId, trs);
        trade.add(trs.calc());
    }

    @Override
    protected void runScenarioLoop(MarketData scenarioMd, Set<String> affectedIds, JSONArray scenarioRst) {
        for (Map.Entry<String, Trs> entry : trsCache.entrySet()) {
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
