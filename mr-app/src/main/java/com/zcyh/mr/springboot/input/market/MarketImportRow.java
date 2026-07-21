package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;

public final class MarketImportRow {
    public int rowNumber;
    public int pointCount;
    public LocalDate dataDate;
    public String marketDataType;
    public String curveId;
    public JSONObject curveContent = new JSONObject();
}
