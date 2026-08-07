package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.cva.CvaCounterpartyResult;
import com.zcyh.mr.cva.CvaHedgeResult;
import com.zcyh.mr.cva.CvaNettingSetResult;
import com.zcyh.mr.cva.CvaPortfolioResult;
import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
public class CvaResultPersistService {
    private static final int BATCH_SIZE = 5000;
    private static final String RESULT_TABLE = "TB_OUT_CVA_RESULT";
    private static final String COUNTERPARTY_TABLE = "TB_OUT_CVA_COUNTERPARTY_DETAIL";
    private static final String NETTING_SET_TABLE = "TB_OUT_CVA_NETTING_SET_DETAIL";
    private static final String HEDGE_TABLE = "TB_OUT_CVA_HEDGE_DETAIL";

    private static final String RESULT_COLUMNS =
            "BATCH_ID,DATA_DATE,CALCULATION_MODE,REDUCTION_REASON,DERIVATIVE_NOTIONAL_CNY,"
                    + "COUNTERPARTY_COUNT,NETTING_SET_COUNT,HEDGE_COUNT,"
                    + "K_REDUCED,K_HEDGED,K_FULL,CVA_CAPITAL_REQUIREMENT,CVA_RWA,CREATE_TIME";
    private static final String COUNTERPARTY_COLUMNS =
            "BATCH_ID,DATA_DATE,COUNTERPARTY_ID,SCVA,SINGLE_NAME_HEDGE,HEDGING_MISALIGNMENT,CREATE_TIME";
    private static final String NETTING_SET_COLUMNS =
            "BATCH_ID,DATA_DATE,NETTING_SET_ID,COUNTERPARTY_ID,EFFECTIVE_MATURITY_YEARS,EAD,"
                    + "RISK_WEIGHT,DISCOUNT_FACTOR,SCVA_CONTRIBUTION,CREATE_TIME";
    private static final String HEDGE_COLUMNS =
            "BATCH_ID,DATA_DATE,HEDGE_ID,HEDGE_TYPE,COUNTERPARTY_ID,RISK_WEIGHT,CORRELATION,"
                    + "REMAINING_MATURITY_YEARS,NOTIONAL_CNY,DISCOUNT_FACTOR,SINGLE_NAME_HEDGE,"
                    + "INDEX_HEDGE,HEDGING_MISALIGNMENT,CREATE_TIME";

    private final JdbcTemplate engineResultJdbc;
    private final DorisStreamLoadService dorisStreamLoadService;

    public CvaResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultJdbc,
            DorisStreamLoadService dorisStreamLoadService) {
        this.engineResultJdbc = engineResultJdbc;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    public void persist(String batchId, LocalDate dataDate, CvaPortfolioResult result) {
        java.sql.Date sqlDataDate = com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_CVA_RESULT WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, sqlDataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_CVA_COUNTERPARTY_DETAIL WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, sqlDataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_CVA_NETTING_SET_DETAIL WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, sqlDataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_CVA_HEDGE_DETAIL WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, sqlDataDate);

        String now = ResultPersistTime.nowText();
        writeResult(batchId, dataDate, result, now);
        writeCounterparties(batchId, dataDate, result, now);
        writeNettingSets(batchId, dataDate, result, now);
        writeHedges(batchId, dataDate, result, now);
    }

    private void writeResult(String batchId, LocalDate dataDate, CvaPortfolioResult result, String now) {
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService, RESULT_TABLE, RESULT_COLUMNS, "cva_result_" + batchId, BATCH_SIZE);
        buffer.appendRow(batchId, dataDate, result.calculationMode, result.reductionReason,
                decimal(result.derivativeNotionalCny), result.counterparties.size(), result.nettingSets.size(),
                result.hedges.size(), decimal(result.kReduced), decimal(result.kHedged),
                decimal(result.kFull), decimal(result.cvaCapitalRequirement), decimal(result.cvaRiskWeightedAssets), now);
        buffer.flush();
    }

    private void writeCounterparties(String batchId, LocalDate dataDate,
                                     CvaPortfolioResult result, String now) {
        if (result.counterparties.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService, COUNTERPARTY_TABLE, COUNTERPARTY_COLUMNS,
                "cva_counterparty_" + batchId, BATCH_SIZE);
        for (CvaCounterpartyResult value : result.counterparties) {
            buffer.appendRow(batchId, dataDate, value.counterpartyId, decimal(value.scva),
                    decimal(value.singleNameHedge), decimal(value.hedgingMisalignment), now);
        }
        buffer.flush();
    }

    private void writeNettingSets(String batchId, LocalDate dataDate,
                                  CvaPortfolioResult result, String now) {
        if (result.nettingSets.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService, NETTING_SET_TABLE, NETTING_SET_COLUMNS,
                "cva_netting_set_" + batchId, BATCH_SIZE);
        for (CvaNettingSetResult value : result.nettingSets) {
            buffer.appendRow(batchId, dataDate, value.nettingSetId, value.counterpartyId,
                    decimal(value.effectiveMaturity), decimal(value.ead), decimal(value.riskWeight),
                    decimal(value.discountFactor), decimal(value.scvaContribution), now);
        }
        buffer.flush();
    }

    private void writeHedges(String batchId, LocalDate dataDate,
                             CvaPortfolioResult result, String now) {
        if (result.hedges.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService, HEDGE_TABLE, HEDGE_COLUMNS,
                "cva_hedge_" + batchId, BATCH_SIZE);
        for (CvaHedgeResult value : result.hedges) {
            buffer.appendRow(batchId, dataDate, value.hedgeId, value.hedgeType, value.counterpartyId,
                    decimal(value.riskWeight), decimal(value.correlation), decimal(value.remainingMaturity),
                    decimal(value.notional), decimal(value.discountFactor), decimal(value.singleNameHedge),
                    decimal(value.indexHedge), decimal(value.hedgingMisalignment), now);
        }
        buffer.flush();
    }

    private static String decimal(double value) {
        return DorisCsvStreamLoadBuffer.decimalText(BigDecimal.valueOf(value));
    }
}
