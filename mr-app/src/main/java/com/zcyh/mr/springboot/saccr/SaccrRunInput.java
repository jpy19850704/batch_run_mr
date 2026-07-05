package com.zcyh.mr.springboot.saccr;

import com.zcyh.mr.saccr.model.SaccrNettingSet;

import java.util.List;

/**
 * SA-CCR 单次批量计量输入。
 */
public class SaccrRunInput {
    public final String batchId;
    public final String dataDate;
    public final List<SaccrNettingSet> nettingSets;
    public final List<SaccrTradeRow> tradeRows;
    public final List<SaccrCollateralOutputRow> collateralRows;

    public SaccrRunInput(String batchId,
                         String dataDate,
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
