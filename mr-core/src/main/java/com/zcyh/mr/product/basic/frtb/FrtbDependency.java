package com.zcyh.mr.product.basic.frtb;

/**
 * FRTB 风险依赖描述。
 * 当前用于承载 FRTB builder 输出所需的统一依赖信息。
 * 除基础的曲线、风险因子、bucket 外，也允许携带第二维期限、FX 货币对、
 * 输出类型等扩展字段，避免相同规则再次分散回产品层。
 */
public class FrtbDependency {

    public static final String TYPE_GIRR_DELTA = "GIRR_DELTA";
    public static final String TYPE_GIRR_DELTA_BASIS = "GIRR_DELTA_BASIS";
    public static final String TYPE_GIRR_VEGA = "GIRR_VEGA";
    public static final String TYPE_FX_DELTA = "FX_DELTA";
    public static final String TYPE_FX_VEGA = "FX_VEGA";
    public static final String TYPE_EQ_DELTA = "EQ_DELTA";
    public static final String TYPE_EQ_VEGA = "EQ_VEGA";
    public static final String TYPE_CMTY_DELTA = "CMTY_DELTA";
    public static final String TYPE_CMTY_VEGA = "CMTY_VEGA";
    public static final String TYPE_CSR_NSEC_DELTA = "CSR_NSEC_DELTA";
    public static final String TYPE_CSR_SECNCTP_DELTA = "CSR_SECNCTP_DELTA";

    /** 依赖类型，例如 GIRR_DELTA / GIRR_VEGA。 */
    public String type;
    /** 产品重估时依赖的曲线名或风险因子名。 */
    public String curveOrRiskFactor;
    /** 最终输出到 FRTB 敏感性的 riskFactorId。 */
    public String riskFactorId;
    /** 最终输出到 FRTB 敏感性的 bucket。 */
    public String bucket;
    /** 最终输出到 FRTB 敏感性的 type，当前用于 CSR 输出 BOND/CDS。 */
    public String outputType;
    /** 原始第二维输入，当前用于 GIRR Vega 的标的期限拆分。 */
    public String secondaryVertex;
    /** FX 风险因子对应的货币对，当前用于 Curvature 双外币缩放判断。 */
    public String fxPair;
    /** GIRR Basis 第一腿币种。 */
    public String currency1;
    /** GIRR Basis 第一腿曲线。 */
    public String curve1;
    /** GIRR Basis 第二腿币种。 */
    public String currency2;
    /** GIRR Basis 第二腿曲线。 */
    public String curve2;

    public FrtbDependency() {
    }

    public FrtbDependency(String type, String curveOrRiskFactor, String riskFactorId, String bucket) {
        this.type = type;
        this.curveOrRiskFactor = curveOrRiskFactor;
        this.riskFactorId = riskFactorId;
        this.bucket = bucket;
    }

    public FrtbDependency(String type, String curveOrRiskFactor, String riskFactorId, String bucket, String outputType) {
        this.type = type;
        this.curveOrRiskFactor = curveOrRiskFactor;
        this.riskFactorId = riskFactorId;
        this.bucket = bucket;
        this.outputType = outputType;
    }

    public static FrtbDependency of(String type, String curveOrRiskFactor, String riskFactorId, String bucket) {
        return new FrtbDependency(type, curveOrRiskFactor, riskFactorId, bucket);
    }

    public static FrtbDependency of(String type, String curveOrRiskFactor, String riskFactorId, String bucket,
            String outputType) {
        return new FrtbDependency(type, curveOrRiskFactor, riskFactorId, bucket, outputType);
    }
}
