package com.zcyh.mr.springboot.input.trade;

import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

public class TradeEditRequest {
    private String dataDate;
    private String instrumentId;
    private String productCode;
    private Integer versionNo;
    private JSONObject tradeData;
    private Map<String, Object> attributes;

    public String getDataDate() {
        return dataDate;
    }

    public void setDataDate(String dataDate) {
        this.dataDate = dataDate;
    }

    public String getInstrumentId() {
        return instrumentId;
    }

    public void setInstrumentId(String instrumentId) {
        this.instrumentId = instrumentId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public JSONObject getTradeData() {
        return tradeData;
    }

    public void setTradeData(JSONObject tradeData) {
        this.tradeData = tradeData;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
