package com.zcyh.mr.calc;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.core.Calendar;
import com.zcyh.mr.core.CommUtils;
import com.zcyh.mr.core.Constants;
import com.zcyh.mr.core.JsonNumberUtils;
import com.zcyh.mr.marketdata.MarketData;
import com.zcyh.mr.product.credit.Cds;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CdsCalc implements Runnable, Calc.ScenarioCapable {
    List<HashMap<String, Object>> trades;
    MarketData marketData;
    LocalDate dataDate;
    String operCode;
    Calendar calendar;
    JSONObject otherData;

    // 缓存已校准的 Cds 实例，场景估值时复用
    Map<String, Cds> cdsCache = new LinkedHashMap<>();

    JSONObject result = new JSONObject();
    JSONArray trade = new JSONArray();
    JSONArray log = new JSONArray();

    public CdsCalc(String operCode, LocalDate dataDate, List<HashMap<String, Object>> trades,
            MarketData marketData, Calendar calendar, JSONObject otherData) {
        this.operCode = operCode;
        this.dataDate = dataDate;
        this.trades = trades;
        this.marketData = marketData;
        this.otherData = otherData;
        this.calendar = calendar;
    }

    public String calc() {
        this.run();
        this.result.put("data", new JSONObject());
        ((JSONObject) this.result.get("data")).put("trade_data", trade);
        ((JSONObject) this.result.get("data")).put("log_data", log);
        JsonNumberUtils.normalizeNumbersInPlace(this.result);
        return JSON.toJSONString(this.result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }

    @Override
    public void run() {
        if (Constants.CALC_MODE.PRICING.equalsIgnoreCase(operCode)) {
            for (HashMap<String, Object> trade : trades) {
                try {
                    calcTrade(trade);
                } catch (Exception e) {
                    AbstractCalc.appendErrorResult(this.trade, log, dataDate, trade, e);
                }
            }
        }
    }

    private void calcTrade(HashMap<String, Object> trade) {
        Cds.CdsInfo cdsInfo = JSONObject.parseObject(trade.toString(), Cds.CdsInfo.class);

        // 从 trade_data 中的 UNDERLYING_DATA 数组还原为按 BOND_ID 索引的 JSONObject
        JSONObject udData = new JSONObject();
        Object rawUd = trade.get("UNDERLYING_DATA");
        if (rawUd instanceof JSONArray) {
            for (Object obj : (JSONArray) rawUd) {
                JSONObject bond = (JSONObject) obj;
                String bondId = bond.getString("BOND_ID");
                if (bondId != null) {
                    udData.put(bondId, bond);
                }
            }
        }

        Cds cds = new Cds(dataDate, cdsInfo, marketData, calendar, udData);
        Cds.CdsMeasure measure = cds.calc();

        // 缓存已校准的 Cds 实例
        cdsCache.put(cdsInfo.instrumentId, cds);

        this.trade.add(measure);

    }

    /**
     * 场景估值：复用缓存的 Cds 实例，用新的市场数据重新估值。
     * 不重新解析交易数据和标的数据，仅市场数据变化。
     */
    @Override
    public JSONArray calcScenario(MarketData scenarioMd) {
        return calcScenario(scenarioMd, null);
    }

    @Override
    public JSONArray calcScenario(MarketData scenarioMd, Set<String> affectedIds) {
        JSONArray scenarioRst = new JSONArray();
        for (Map.Entry<String, Cds> entry : cdsCache.entrySet()) {
            if (affectedIds != null && !affectedIds.contains(entry.getKey())) {
                continue;
            }
            Cds cds = entry.getValue();
            try {
                Cds.CdsMeasure measure = cds.calc(scenarioMd);
                scenarioRst.add(measure);
            } catch (Exception e) {
                scenarioRst.add(AbstractCalc.buildErrorMeasure(dataDate, entry.getKey(), null, e));
            }
        }
        return scenarioRst;
    }
}
