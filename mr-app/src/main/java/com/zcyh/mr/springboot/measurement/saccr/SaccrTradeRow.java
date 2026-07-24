package com.zcyh.mr.springboot.measurement.saccr;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.saccr.model.SaccrTrade;

import java.time.LocalDate;

/**
 * SA-CCR 交易明细输出行。
 */
public class SaccrTradeRow {
    public String batchId;
    public LocalDate dataDate;
    public String instrumentId;
    public String counterpartyId;
    public String nettingMode;
    public String nettingSetId;
    public String productCode;
    public String assetClass;
    public int direction;
    public double mtmCny;
    public double notional;
    public String currency;
    public LocalDate startDate;
    public LocalDate endDate;
    public String referenceEntity;
    public String creditRating;
    public boolean isIndex;
    public String currencyPair;
    public String commodityBucket;
    public String commodityType;
    public boolean isOption;
    public String optionType;
    public LocalDate optionExpiry;
    public double strikePrice;
    public double underlyingPrice;
    public double quantity;
    public SaccrTrade trade;

    public String measureFactorJson() {
        JSONObject json = new JSONObject();
        json.put("delta", trade.delta);
        json.put("adjustedNotional", trade.adjustedNotional);
        json.put("maturityFactor", trade.maturityFactor);
        json.put("supervisoryDuration", trade.supervisoryDuration);
        json.put("effectiveNotional", trade.effectiveNotional);
        json.put("mporDays", trade.mporDays);
        json.put("startYears", trade.startYears);
        json.put("endYears", trade.endYears);
        json.put("optionTYears", trade.optionTYears);
        return json.toJSONString();
    }
}
