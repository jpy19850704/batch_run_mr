package com.zcyh.mr.frtbima.model;

import java.math.BigDecimal;

/**
 * 最终 IMA 资本汇总结果（MAR33.43）。
 */
public class ImaCapitalResult {

    private String dataDate;
    private String batchId;

    /** 全行 IMCC 结果 */
    private ImccResult imccResult;

    /** 全行 SES 结果 */
    private SesResult sesResult;

    /** Amber 附加系数 k */
    private BigDecimal amberSurchargeRatio;

    /** 最终总资本 ACR_total = Σ CA_i */
    private BigDecimal acrTotal;

    public ImaCapitalResult() {
    }

    public String getDataDate() { return dataDate; }
    public void setDataDate(String dataDate) { this.dataDate = dataDate; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public ImccResult getImccResult() { return imccResult; }
    public void setImccResult(ImccResult imccResult) { this.imccResult = imccResult; }

    public SesResult getSesResult() { return sesResult; }
    public void setSesResult(SesResult sesResult) { this.sesResult = sesResult; }

    public BigDecimal getAmberSurchargeRatio() { return amberSurchargeRatio; }
    public void setAmberSurchargeRatio(BigDecimal amberSurchargeRatio) { this.amberSurchargeRatio = amberSurchargeRatio; }

    public BigDecimal getAcrTotal() { return acrTotal; }
    public void setAcrTotal(BigDecimal acrTotal) { this.acrTotal = acrTotal; }
}
