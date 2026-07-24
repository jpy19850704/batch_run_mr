package com.zcyh.mr.springboot.measurement.saccr;

import com.zcyh.mr.saccr.model.SaccrNettingSet;

import java.time.LocalDate;
import java.util.List;

/**
 * SA-CCR 单次批量计量输入。
 */
public class SaccrRunInput {
    public final String batchId;
    public final LocalDate dataDate;
    public final List<SaccrNettingSet> nettingSets;
    public final List<SaccrTradeRow> tradeRows;
    public final List<SaccrCollateralOutputRow> collateralRows;

    public SaccrRunInput(String batchId,
                         LocalDate dataDate,
                         List<SaccrNettingSet> nettingSets,
                         List<SaccrTradeRow> tradeRows,
                         List<SaccrCollateralOutputRow> collateralRows) {
        this.batchId = batchId;
        this.dataDate = dataDate;
        this.nettingSets = nettingSets;
        this.tradeRows = tradeRows;
        this.collateralRows = collateralRows;
    }
}
