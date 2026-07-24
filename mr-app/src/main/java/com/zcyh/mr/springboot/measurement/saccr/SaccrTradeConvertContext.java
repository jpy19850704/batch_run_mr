package com.zcyh.mr.springboot.measurement.saccr;

import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;

/**
 * SA-CCR 交易输入转换上下文。
 */
public class SaccrTradeConvertContext {
    public final String batchId;
    public final LocalDate dataDate;
    public final String instrumentId;
    public final String productCode;
    public final double valuationCny;
    public final JSONObject tradeInput;
    public final String counterpartyId;
    public final String nettingMode;
    public final String nettingSetId;

    public SaccrTradeConvertContext(String batchId,
                                    LocalDate dataDate,
                                    String instrumentId,
                                    String productCode,
                                    double valuationCny,
                                    JSONObject tradeInput,
                                    String counterpartyId,
                                    String nettingMode,
                                    String nettingSetId) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.instrumentId = instrumentId;
        this.productCode = productCode;
        this.valuationCny = valuationCny;
        this.tradeInput = tradeInput;
        this.counterpartyId = counterpartyId;
        this.nettingMode = nettingMode;
        this.nettingSetId = nettingSetId;
    }
}
