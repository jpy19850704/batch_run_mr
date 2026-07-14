package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.core.Constants;
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
            int seqNo = context.getFirstJobSeqNo() + i;
            List<BatchTradeDataLoader.TradeRow> chunkTrades = context.getTradeChunks().get(i);
            BatchJobPayload jobPayload = new BatchJobPayload();
            jobPayload.setSeqNo(seqNo);
            jobPayload.setChunkTrades(chunkTrades);
            try {
                MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                        JobPayloadBuilder.toTradeSliceSources(chunkTrades),
                        curveSources);
                JSONObject payload = payloadBuilder.buildPayload(
                        resolveCalcMode(context),
                        dataDate,
                        chunkTrades,
                        sliceResult.getCurves(),
                        sliceResult.getTradeMarketDataKeys(),
                        context.getBatchId(),
                        seqNo,
                        context.getRegularScenarioIdList(),
                        context.getVarScenarioIdList(),
                        context.getNormalFullScenarioIdList(),
                        context.getNormalReducedScenarioIdList(),
                        context.getStressReducedScenarioIdList(),
                        context.getNmrfScenarioIdList(),
                        context.isPersistResult(),
                        context.isFrtbDisabled());
                if (context.getRunMode() != null) {
                    payload.put("run_mode", context.getRunMode());
                }
                if (context.isScenarioMode() && context.isCacheScenarioResult()) {
                    payload.put("cache_scenario_result", true);
                }
                payload.getJSONObject("batch_meta").put("localRerun", context.isLocalRerun());
                jobPayload.setPayload(payload);
            } catch (PayloadJsonParseException ex) {
                jobPayload.setFailed(true);
                jobPayload.setErrorCode(BatchJobService.PAYLOAD_JSON_PARSE_ERROR);
                jobPayload.setErrorMessage(ex.getMessage());
            }
            jobPayloads.add(jobPayload);
        }
        context.setJobPayloads(jobPayloads);
    }

    private static String resolveCalcMode(BatchRunWorkflowContext context) {
        return Constants.CALC_MODE.PRICING;
    }
}
