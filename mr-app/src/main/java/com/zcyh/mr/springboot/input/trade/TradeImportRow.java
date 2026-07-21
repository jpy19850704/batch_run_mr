package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TradeImportRow {
    public int rowNumber;
    public LocalDate dataDate;
    public String instrumentId;
    public String productCode;
    public JSONObject tradeData = new JSONObject();
    public Map<String, Object> attributes = new LinkedHashMap<>();
}
