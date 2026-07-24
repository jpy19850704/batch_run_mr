package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.zcyh.mr.saccr.model.SaccrResult;
import com.zcyh.mr.springboot.measurement.saccr.SaccrCollateralOutputRow;
import com.zcyh.mr.springboot.measurement.saccr.SaccrTradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * SA-CCR 计算结果落库服务。
 */
@Service
public class SaccrResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(SaccrResultPersistService.class);

    private static final int BATCH_SIZE = 5000;

    private static final String RESULT_TABLE = "TB_OUT_SACCR_RESULT";
    private static final String TRADE_DETAIL_TABLE = "TB_OUT_SACCR_TRADE_DETAIL";
    private static final String COLLATERAL_DETAIL_TABLE = "TB_OUT_SACCR_COLLATERAL_DETAIL";

    private static final String RESULT_COLUMNS =
            "BATCH_ID,DATA_DATE,NETTING_MODE,NETTING_SET_ID,COUNTERPARTY_ID,TRADE_COUNT,MARGIN_TYPE,"
                    + "SUM_MTM,COLLATERAL_C,THRESHOLD_CNY,MTA_CNY,NICA_CNY,RC,ADDON_IR,ADDON_FX,"
                    + "ADDON_CREDIT,ADDON_EQUITY,ADDON_COMMODITY,ADDON_AGGREGATE,MULTIPLIER,PFE,EAD,CREATE_TIME";

    private static final String TRADE_DETAIL_COLUMNS =
            "BATCH_ID,DATA_DATE,INSTRUMENT_ID,COUNTERPARTY_ID,NETTING_MODE,NETTING_SET_ID,PRODUCT_CODE,"
                    + "ASSET_CLASS,DIRECTION,MTM_CNY,NOTIONAL,CURRENCY,START_DATE,END_DATE,REFERENCE_ENTITY,"
                    + "CREDIT_RATING,IS_INDEX,CURRENCY_PAIR,COMMODITY_BUCKET,COMMODITY_TYPE,IS_OPTION,"
                    + "OPTION_TYPE,OPTION_EXPIRY,STRIKE_PRICE,UNDERLYING_PRICE,QUANTITY,MEASURE_FACTOR_JSON,CREATE_TIME";

    private static final String COLLATERAL_DETAIL_COLUMNS =
            "BATCH_ID,DATA_DATE,COLLATERAL_ID,COLLATERAL_SCOPE,NETTING_SET_ID,INSTRUMENT_ID,COLLATERAL_TYPE,"
                    + "DIRECTION,COLLATERAL_CCY,MARKET_VALUE,FX_RATE_TO_CNY,HAIRCUT_RATE,ADJUSTED_VALUE_CNY,CREATE_TIME";

    private final JdbcTemplate engineResultJdbc;
    private final DorisStreamLoadService dorisStreamLoadService;

    public SaccrResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultJdbc,
            DorisStreamLoadService dorisStreamLoadService) {
        this.engineResultJdbc = engineResultJdbc;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    public void persist(String batchId,
                        LocalDate dataDate,
                        List<SaccrResult> results,
                        List<SaccrTradeRow> tradeRows,
                        List<SaccrCollateralOutputRow> collateralRows) {
        java.sql.Date sqlDataDate = com.zcyh.mr.springboot.support.ResultDbDateSupport.sqlDate(dataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_SACCR_RESULT WHERE BATCH_ID = ? AND DATA_DATE=?", batchId, sqlDataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_SACCR_TRADE_DETAIL WHERE BATCH_ID = ? AND DATA_DATE=?", batchId, sqlDataDate);
        engineResultJdbc.update("DELETE FROM TB_OUT_SACCR_COLLATERAL_DETAIL WHERE BATCH_ID = ? AND DATA_DATE=?", batchId, sqlDataDate);

        String now = ResultPersistTime.nowText();
        writeResults(batchId, dataDate, results, now);
        writeTradeDetails(batchId, dataDate, tradeRows, now);
        writeCollateralDetails(batchId, dataDate, collateralRows, now);

        log.info("SA-CCR 结果落库完成：batchId={}，dataDate={}，结果={}，交易明细={}，押品明细={}",
                batchId,
                dataDate,
                results == null ? 0 : results.size(),
                tradeRows == null ? 0 : tradeRows.size(),
                collateralRows == null ? 0 : collateralRows.size());
    }

    private void writeResults(String batchId, LocalDate dataDate, List<SaccrResult> results, String now) {
        if (results == null || results.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                RESULT_TABLE,
                RESULT_COLUMNS,
                "saccr_result_" + batchId,
                BATCH_SIZE);
        for (SaccrResult r : results) {
            buffer.appendRow(
                    batchId,
                    dataDate,
                    r.nettingMode,
                    r.nettingSetId,
                    r.counterpartyId,
                    r.tradeCount,
                    r.marginType,
                    decimal(r.sumMtm),
                    decimal(r.collateralC),
                    decimal(r.thresholdCny),
                    decimal(r.mtaCny),
                    decimal(r.nicaCny),
                    decimal(r.rc),
                    decimal(r.addonIr),
                    decimal(r.addonFx),
                    decimal(r.addonCredit),
                    decimal(r.addonEquity),
                    decimal(r.addonCommodity),
                    decimal(r.addonAggregate),
                    decimal(r.multiplier),
                    decimal(r.pfe),
                    decimal(r.ead),
                    now
            );
        }
        buffer.flush();
    }

    private void writeTradeDetails(String batchId, LocalDate dataDate, List<SaccrTradeRow> rows, String now) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TRADE_DETAIL_TABLE,
                TRADE_DETAIL_COLUMNS,
                "saccr_trade_detail_" + batchId,
                BATCH_SIZE);
        for (SaccrTradeRow row : rows) {
            buffer.appendRow(
                    batchId,
                    dataDate,
                    row.instrumentId,
                    row.counterpartyId,
                    row.nettingMode,
                    row.nettingSetId,
                    row.productCode,
                    row.assetClass,
                    row.direction,
                    decimal(row.mtmCny),
                    decimal(row.notional),
                    row.currency,
                    row.startDate,
                    row.endDate,
                    row.referenceEntity,
                    row.creditRating,
                    row.isIndex ? 1 : 0,
                    row.currencyPair,
                    row.commodityBucket,
                    row.commodityType,
                    row.isOption ? 1 : 0,
                    row.optionType,
                    row.optionExpiry,
                    decimal(row.strikePrice),
                    decimal(row.underlyingPrice),
                    decimal(row.quantity),
                    row.measureFactorJson(),
                    now
            );
        }
        buffer.flush();
    }

    private void writeCollateralDetails(String batchId,
                                        LocalDate dataDate,
                                        List<SaccrCollateralOutputRow> rows,
                                        String now) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                COLLATERAL_DETAIL_TABLE,
                COLLATERAL_DETAIL_COLUMNS,
                "saccr_collateral_detail_" + batchId,
                BATCH_SIZE);
        for (SaccrCollateralOutputRow row : rows) {
            buffer.appendRow(
                    batchId,
                    dataDate,
                    row.collateralId,
                    row.collateralScope,
                    row.nettingSetId,
                    row.instrumentId,
                    row.collateralType,
                    row.direction,
                    row.collateralCcy,
                    decimal(row.marketValue),
                    decimal(row.fxRateToCny),
                    decimal(row.haircutRate),
                    decimal(row.adjustedValueCny),
                    now
            );
        }
        buffer.flush();
    }

    private static String decimal(double value) {
        return DorisCsvStreamLoadBuffer.decimalText(BigDecimal.valueOf(value));
    }

}
