package com.zcyh.mr.product.basic.frtb;

import com.alibaba.fastjson2.annotation.JSONField;

import java.time.LocalDate;

public class DrcDetail {
    @JSONField(name = "DATA_DATE")
    public LocalDate dataDate;
    @JSONField(name = "PORTFOLIO_CODE")
    public String portfolioCode;
    @JSONField(name = "PRODUCT_CODE")
    public String productCode;
    @JSONField(name = "INSTRUMENT_ID")
    public String instrumentId;
    @JSONField(name = "SECURITY_ID")
    public String securityId;
    @JSONField(name = "SECURITY_TYPE")
    public String securityType;
    @JSONField(name = "LEGAL_ENTITY")
    public String legalEntity;
    @JSONField(name = "DRC_BUCKET")
    public String drcBucket;
    @JSONField(name = "JTD_TYPE")
    public String jtdType;
    @JSONField(name = "SENIORITY")
    public Integer seniority;
    @JSONField(name = "TERM_TO_MATURITY")
    public Double termToMaturity;
    @JSONField(name = "MODIFIED_REMAIN_TERM")
    public Double modifiedRemainTerm;
    @JSONField(name = "RISK_WEIGHT")
    public Double riskWeight;
    @JSONField(name = "JTD")
    public Double jtd;
    @JSONField(name = "JTD_CNY")
    public Double jtdCny;
    @JSONField(name = "INSTRUMENT_VALUE")
    public Double instrumentValue;
    @JSONField(name = "FRTB_LGD")
    public Double frtbLgd;
    @JSONField(name = "NOTIONAL")
    public Double notional;
}
