package com.zcyh.mr.springboot.input.market;

public class MarketDeleteKey {
    private String dataDate;
    private String marketDataType;
    private String curveId;
    private Integer versionNo;

    public String getDataDate() { return dataDate; }
    public void setDataDate(String dataDate) { this.dataDate = dataDate; }
    public String getMarketDataType() { return marketDataType; }
    public void setMarketDataType(String marketDataType) { this.marketDataType = marketDataType; }
    public String getCurveId() { return curveId; }
    public void setCurveId(String curveId) { this.curveId = curveId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
}
