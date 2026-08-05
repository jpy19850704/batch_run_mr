package com.zcyh.mr.springboot.input.market;

import com.alibaba.fastjson2.JSONObject;

public class MarketEditRequest {
    private String dataDate;
    private String marketDataType;
    private String conversionType;
    private String curveId;
    private Integer versionNo;
    private String dataKind;
    private JSONObject marketData;

    public String getDataDate() {
        return dataDate;
    }

    public void setDataDate(String dataDate) {
        this.dataDate = dataDate;
    }

    public String getMarketDataType() {
        return marketDataType;
    }

    public void setMarketDataType(String marketDataType) {
        this.marketDataType = marketDataType;
    }

    public String getConversionType() {
        return conversionType;
    }

    public void setConversionType(String conversionType) {
        this.conversionType = conversionType;
    }

    public String getCurveId() {
        return curveId;
    }

    public void setCurveId(String curveId) {
        this.curveId = curveId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getDataKind() {
        return dataKind;
    }

    public void setDataKind(String dataKind) {
        this.dataKind = dataKind;
    }

    public JSONObject getMarketData() {
        return marketData;
    }

    public void setMarketData(JSONObject marketData) {
        this.marketData = marketData;
    }
}
