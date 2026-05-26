package com.zcyh.mr.product.basic.frtb;

import com.alibaba.fastjson2.annotation.JSONField;

import java.util.Map;

/**
 * FrtbSenes
 *
 * @author cmh
 * @date 2024/8/9
 */
public class FrtbSenes {
    @JSONField(name = "INSTRUMENT_ID")
    public String instrumentId;
    @JSONField(name = "RISK_FACTOR_ID")
    public String riskFactorId;
    @JSONField(name = "RISK_FACTOR_VERTEX_1")
    public String riskFactorVertex1;
    @JSONField(name = "RISK_FACTOR_VERTEX_2")
    public String riskFactorVertex2;
    @JSONField(name = "RISK_FACTOR_CLASS")
    public String riskFactorClass;
    @JSONField(name = "RISK_FACTOR_BUCKET")
    public String riskFactorBucket;
    @JSONField(name = "RISK_FACTOR_TYPE")
    public String riskFactorType;
    @JSONField(name = "SENSITIVITY_TYPE")
    public String sensitivityType;
    @JSONField(name = "SENSITIVITY_VAL_INST_CURR", format = "0.##########")
    public double sensitivityValInstCurr;
    @JSONField(name = "INSTRUMENT_CURRENCY")
    public String instrumentCurrency;
    @JSONField(name = "SENSITIVITY_VAL_INST_CURR_CNY", format = "0.##########")
    public double sensitivityValInstCurrCny;
    @JSONField(name = "DETAIL")
    public Map<String, Object> detail;
}
