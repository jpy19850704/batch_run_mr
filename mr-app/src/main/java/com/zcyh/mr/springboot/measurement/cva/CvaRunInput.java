package com.zcyh.mr.springboot.measurement.cva;

import com.zcyh.mr.cva.CvaPortfolioResult;

import java.time.LocalDate;

public class CvaRunInput {
    public final String batchId;
    public final LocalDate dataDate;
    public final CvaPortfolioResult result;

    public CvaRunInput(String batchId, LocalDate dataDate, CvaPortfolioResult result) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.result = result;
    }
}
