package com.zcyh.mr.springboot.input.market;

public class MarketDetailRequest {
    private String dataKind;
    private String dataDate;
    private String marketDataType;
    private String curveId;
    private String conversionType;
    private Integer versionNo;

    public String getDataKind() {
        return dataKind;
    }

    public void setDataKind(String dataKind) {
        this.dataKind = dataKind;
    }

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

    public String getCurveId() {
        return curveId;
    }

    public void setCurveId(String curveId) {
        this.curveId = curveId;
    }

    public String getConversionType() {
        return conversionType;
    }

    public void setConversionType(String conversionType) {
        this.conversionType = conversionType;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }
}
