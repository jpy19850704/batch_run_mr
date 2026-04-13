package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量任务单个分片的待提交载荷。
 */
public class BatchJobPayload {
    private int seqNo;
    private JSONObject payload;
    private List<BatchTradeDataLoader.TradeRow> chunkTrades = new ArrayList<BatchTradeDataLoader.TradeRow>();

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

    public List<BatchTradeDataLoader.TradeRow> getChunkTrades() {
        return chunkTrades;
    }

    public void setChunkTrades(List<BatchTradeDataLoader.TradeRow> chunkTrades) {
        this.chunkTrades = chunkTrades == null
                ? new ArrayList<BatchTradeDataLoader.TradeRow>()
                : chunkTrades;
    }
}
