package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量任务载荷构建任务。
 */
@Component
public class BatchPayloadBuildTask implements BatchRunTask {
    private final MrMarketDataSliceService marketDataSliceService;
    private final JobPayloadBuilder payloadBuilder;

    public BatchPayloadBuildTask(
            MrMarketDataSliceService marketDataSliceService,
            JobPayloadBuilder payloadBuilder) {
        this.marketDataSliceService = marketDataSliceService;
        this.payloadBuilder = payloadBuilder;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        LocalDate dataDate = LocalDate.parse(context.getDataDate(), DateTimeFormatter.BASIC_ISO_DATE);
        List<MrMarketDataSliceService.CurveSliceSource> curveSources =
                JobPayloadBuilder.toCurveSliceSources(context.getLoadedMarketData());
        List<BatchJobPayload> jobPayloads = new ArrayList<BatchJobPayload>();
        for (int i = 0; i < context.getTradeChunks().size(); i++) {
            int seqNo = i + 1;
            List<BatchTradeDataLoader.TradeRow> chunkTrades = context.getTradeChunks().get(i);
            MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                    JobPayloadBuilder.toTradeSliceSources(chunkTrades),
                    curveSources);
            JSONObject payload = payloadBuilder.buildPayload(
                    context.isScenarioMode() ? "SCENARIO" : "PRICING",
                    dataDate,
                    chunkTrades,
                    sliceResult.getCurves(),
                    sliceResult.getTradeMarketDataKeys(),
                    context.getBatchId(),
                    seqNo,
                    context.getRegularScenarioIdList(),
                    context.getRiskClassDecompScenarioIdList());
            if (context.getRunMode() != null) {
                payload.put("run_mode", context.getRunMode());
            }
            BatchJobPayload jobPayload = new BatchJobPayload();
            jobPayload.setSeqNo(seqNo);
            jobPayload.setChunkTrades(chunkTrades);
            jobPayload.setPayload(payload);
            jobPayloads.add(jobPayload);
        }
        context.setJobPayloads(jobPayloads);
    }
}
