package com.zcyh.mr.springboot.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobPayloadBuilderTest {

    @Test
    void buildPayloadRejectsInvalidTradeJson() {
        JobPayloadBuilder builder = new JobPayloadBuilder();
        BatchTradeDataLoader.TradeRow trade = trade("T_BAD", "{bad-json");

        PayloadJsonParseException ex = assertThrows(PayloadJsonParseException.class, () ->
                builder.buildPayload(
                        "PRICING",
                        LocalDate.of(2026, 7, 9),
                        Collections.singletonList(trade),
                        Collections.<MrMarketDataSliceService.CurveSliceSource>emptyList(),
                        Collections.<String, java.util.Set<String>>emptyMap(),
                        "BATCH_TEST",
                        1,
                        null,
                        null,
                        true,
                        false));

        assertTrue(ex.getMessage().contains("instrumentId=T_BAD"));
    }

    @Test
    void payloadBuildTaskMarksOnlyInvalidChunkFailed() {
        BatchPayloadBuildTask task = new BatchPayloadBuildTask(
                new MrMarketDataSliceService(),
                new JobPayloadBuilder());
        BatchRunWorkflowContext context = new BatchRunWorkflowContext();
        context.setBatchId("BATCH_TEST");
        context.setDataDate(LocalDate.of(2026, 7, 9).format(DateTimeFormatter.BASIC_ISO_DATE));

        List<List<BatchTradeDataLoader.TradeRow>> chunks = new ArrayList<List<BatchTradeDataLoader.TradeRow>>();
        chunks.add(Collections.singletonList(trade("T_BAD", "{bad-json")));
        chunks.add(Collections.singletonList(trade("T_OK", "{\"INSTRUMENT_ID\":\"T_OK\"}")));
        context.setTradeChunks(chunks);

        task.execute(context);

        assertEquals(2, context.getJobPayloads().size());
        assertTrue(context.getJobPayloads().get(0).isFailed());
        assertEquals(BatchPayloadBuildTask.PAYLOAD_JSON_PARSE_ERROR, context.getJobPayloads().get(0).getErrorCode());
        assertTrue(context.getJobPayloads().get(0).getErrorMessage().contains("instrumentId=T_BAD"));
        assertFalse(context.getJobPayloads().get(1).isFailed());
        assertNotNull(context.getJobPayloads().get(1).getPayload());
    }

    private static BatchTradeDataLoader.TradeRow trade(String instrumentId, String contentText) {
        BatchTradeDataLoader.TradeRow trade = new BatchTradeDataLoader.TradeRow();
        trade.instrumentId = instrumentId;
        trade.productCode = "TEST_PRODUCT";
        trade.tradeContentText = contentText;
        return trade;
    }
}
