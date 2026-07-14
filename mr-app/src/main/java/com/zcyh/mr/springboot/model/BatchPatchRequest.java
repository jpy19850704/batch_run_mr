package com.zcyh.mr.springboot.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 批次局部重跑请求。
 */
public class BatchPatchRequest extends BatchRunRequest {
    @JsonProperty("instrument_id_list")
    private List<String> instrumentIdList;

    public List<String> getInstrumentIdList() {
        return instrumentIdList;
    }

    public void setInstrumentIdList(List<String> instrumentIdList) {
        this.instrumentIdList = instrumentIdList;
    }
}
