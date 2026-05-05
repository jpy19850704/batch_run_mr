package com.zcyh.mr.springboot.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 批次局部重跑请求。
 */
public class BatchPatchRequest {
    @JsonProperty("batch_id")
    private String batchId;
    @JsonProperty("request_id")
    private String requestId;
    @JsonProperty("data_date")
    private String dataDate;
    @JsonProperty("instrument_id_list")
    private List<String> instrumentIdList;
    @JsonProperty("frtb_disable")
    private Boolean frtbDisable;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getDataDate() {
        return dataDate;
    }

    public void setDataDate(String dataDate) {
        this.dataDate = dataDate;
    }

    public List<String> getInstrumentIdList() {
        return instrumentIdList;
    }

    public void setInstrumentIdList(List<String> instrumentIdList) {
        this.instrumentIdList = instrumentIdList;
    }

    public Boolean getFrtbDisable() {
        return frtbDisable;
    }

    public void setFrtbDisable(Boolean frtbDisable) {
        this.frtbDisable = frtbDisable;
    }
}
