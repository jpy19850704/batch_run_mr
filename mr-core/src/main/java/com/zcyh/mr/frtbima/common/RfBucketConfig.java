package com.zcyh.mr.frtbima.common;

/**
 * RF_CONFIG 表单行 POJO，供 frtbima 模块使用。
 * 定义一个监管桶的曲线、风险类别和期限范围。
 *
 * bucketId = curveId + "_" + tenorMin + "_" + tenorMax
 */
public class RfBucketConfig {

    private String curveId;
    private String riskClass;
    private int tenorMin;
    private int tenorMax;

    public RfBucketConfig() {
    }

    public RfBucketConfig(String curveId, String riskClass, int tenorMin, int tenorMax) {
        this.curveId = curveId;
        this.riskClass = riskClass;
        this.tenorMin = tenorMin;
        this.tenorMax = tenorMax;
    }

    public String getBucketId() {
        return curveId + "_" + tenorMin + "_" + tenorMax;
    }

    public boolean containsTenor(int tenorDays) {
        return tenorDays >= tenorMin && tenorDays <= tenorMax;
    }

    public String getCurveId() { return curveId; }
    public void setCurveId(String curveId) { this.curveId = curveId; }

    public String getRiskClass() { return riskClass; }
    public void setRiskClass(String riskClass) { this.riskClass = riskClass; }

    public int getTenorMin() { return tenorMin; }
    public void setTenorMin(int tenorMin) { this.tenorMin = tenorMin; }

    public int getTenorMax() { return tenorMax; }
    public void setTenorMax(int tenorMax) { this.tenorMax = tenorMax; }
}
