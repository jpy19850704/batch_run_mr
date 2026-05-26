package com.zcyh.mr.frtbima.rfet.model;

/**
 * 风险因子描述。
 * 包含 RFET 判定所需的因子基本信息。
 */
public class RiskFactor {

    /** 风险因子唯一标识 */
    private String riskFactorId;

    /** 风险因子类型，参见 RfetConstants.RF_TYPE_* */
    private String riskFactorType;

    /** 监管桶标识（用于聚合同一监管桶内观测数） */
    private String bucket;

    public RiskFactor() {
    }

    public RiskFactor(String riskFactorId, String riskFactorType, String bucket) {
        this.riskFactorId = riskFactorId;
        this.riskFactorType = riskFactorType;
        this.bucket = bucket;
    }

    public String getRiskFactorId() {
        return riskFactorId;
    }

    public void setRiskFactorId(String riskFactorId) {
        this.riskFactorId = riskFactorId;
    }

    public String getRiskFactorType() {
        return riskFactorType;
    }

    public void setRiskFactorType(String riskFactorType) {
        this.riskFactorType = riskFactorType;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }
}
