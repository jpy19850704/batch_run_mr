package com.zcyh.mr.springboot.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * 批次局部重跑请求。
 */
public class BatchPatchRequest {
    @JsonAlias("batch_id")
    private String batchId;
    @JsonAlias("request_id")
    private String requestId;
    @JsonAlias("data_date")
    private String dataDate;
    @JsonAlias("instrument_id_list")
    private List<String> instrumentIdList;

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
}
