package com.zcyh.mr.marketdata;

import java.io.Serializable;
import java.util.HashMap;

public class FrtbMarketData implements Serializable {
    public String riskFactorClass;
    public String riskFactorType;
    public String riskFactorId;
    public String riskFactorVertex1;
    public String riskFactorBucket;
    public String sensitivityType;
    public String instrumentCurrency;
    public MarketData marketData;
    public Double riskWeight;
    public HashMap<String,Object> param;

    public FrtbMarketData(){
        this.riskFactorClass = "";
        this.riskFactorType = "";
        this.riskFactorId = "";
        this.riskFactorVertex1 = "";
        this.riskFactorBucket = "";
        this.sensitivityType = "";
        this.instrumentCurrency= "";
        this.riskWeight = 0.0;
        this.param = new HashMap<>();
        this.marketData = new MarketData();
    }
    public FrtbMarketData(MarketData marketData){
        this.riskFactorClass = "";
        this.riskFactorType = "";
        this.riskFactorId = "";
        this.riskFactorVertex1 = "";
        this.riskFactorBucket = "";
        this.sensitivityType = "";
        this.instrumentCurrency= "";
        this.riskWeight = 0.0;
        this.param = new HashMap<>();
        this.marketData = marketData;
    }

}
