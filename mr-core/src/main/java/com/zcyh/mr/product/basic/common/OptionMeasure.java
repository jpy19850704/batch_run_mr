package com.zcyh.mr.product.basic.common;

import com.alibaba.fastjson2.annotation.JSONField;

/**
 * 期权类产品计量结果公共基类
 * 包含 Greeks、隐含波动率、标的价格等期权通用指标
 * 不支持的字段保持默认值 0
 */
public class OptionMeasure extends Measure {
    @JSONField(name = "IMPLIED_VOL")
    public double impliedVol;

    @JSONField(name = "DELTA")
    public double delta;

    @JSONField(name = "GAMMA")
    public double gamma;

    @JSONField(name = "VEGA")
    public double vega;

    @JSONField(name = "THETA")
    public double theta;

    @JSONField(name = "FWD_PRICE")
    public double fwdPrice;

    @JSONField(name = "SPOT_PRICE")
    public double spotPrice;
}
