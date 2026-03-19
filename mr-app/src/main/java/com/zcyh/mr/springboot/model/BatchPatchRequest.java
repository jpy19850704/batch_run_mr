package com.zcyh.mr.springboot.model;

import java.util.List;

/**
 * 批次局部重跑请求。
 */
public class BatchPatchRequest {
    private String batchId;
    private String requestId;
    private String dataDate;
    private List<String> tradeIdList;

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

    public List<String> getTradeIdList() {
        return tradeIdList;
    }

    public void setTradeIdList(List<String> tradeIdList) {
        this.tradeIdList = tradeIdList;
    }
}
