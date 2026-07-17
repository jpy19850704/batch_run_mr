package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.TradeInputRow;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量任务单个分片的待提交载荷。
 */
public class BatchJobPayload {
    private int seqNo;
    private JSONObject payload;
    private List<TradeInputRow> chunkTrades = new ArrayList<TradeInputRow>();
    private boolean failed;
    private String errorCode;
    private String errorMessage;

    public int getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(int seqNo) {
        this.seqNo = seqNo;
    }

    public JSONObject getPayload() {
        return payload;
    }

    public void setPayload(JSONObject payload) {
        this.payload = payload;
    }

    public List<TradeInputRow> getChunkTrades() {
        return chunkTrades;
    }

    public void setChunkTrades(List<TradeInputRow> chunkTrades) {
        this.chunkTrades = chunkTrades == null
                ? new ArrayList<TradeInputRow>()
                : chunkTrades;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
