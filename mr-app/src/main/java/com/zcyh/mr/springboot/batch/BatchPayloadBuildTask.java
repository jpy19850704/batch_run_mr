package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.input.db.TradeInputRow;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.support.EngineConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        List<MrMarketDataSliceService.CurveSliceSource> curveSources =
                JobPayloadBuilder.toCurveSliceSources(context.getLoadedMarketData());
        List<BatchJobPayload> jobPayloads = new ArrayList<BatchJobPayload>();
        List<MrMarketDataSliceService.SliceResult> sliceResults =
                new ArrayList<MrMarketDataSliceService.SliceResult>();
        Set<String> scenarioMarketKeys = new LinkedHashSet<String>();
        for (int i = 0; i < context.getTradeChunks().size(); i++) {
            int seqNo = context.getFirstJobSeqNo() + i;
            List<TradeInputRow> chunkTrades = context.getTradeChunks().get(i);
            BatchJobPayload jobPayload = new BatchJobPayload();
            jobPayload.setSeqNo(seqNo);
            jobPayload.setChunkTrades(chunkTrades);
            try {
                MrMarketDataSliceService.SliceResult sliceResult = marketDataSliceService.sliceCurvesWithTradeKeys(
                        JobPayloadBuilder.toTradeSliceSources(chunkTrades),
                        curveSources);
                sliceResults.add(sliceResult);
                collectScenarioMarketKeys(scenarioMarketKeys, sliceResult.getTradeMarketDataKeys());
            } catch (PayloadJsonParseException ex) {
                sliceResults.add(null);
                markPayloadFailed(jobPayload, ex);
            }
            jobPayloads.add(jobPayload);
        }
        context.setScenarioMarketKeys(scenarioMarketKeys);

        for (int i = 0; i < jobPayloads.size(); i++) {
            BatchJobPayload jobPayload = jobPayloads.get(i);
            if (jobPayload.isFailed()) {
                continue;
            }
            List<TradeInputRow> chunkTrades = context.getTradeChunks().get(i);
            MrMarketDataSliceService.SliceResult sliceResult = sliceResults.get(i);
            try {
                JSONObject payload = payloadBuilder.buildPayload(
                        resolveCalcMode(context),
                        context.getDataDate(),
                        chunkTrades,
                        sliceResult.getCurves(),
                        sliceResult.getTradeMarketDataKeys(),
                        context.getBatchId(),
                        jobPayload.getSeqNo(),
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
                payload.getJSONObject("batch_meta").put(
                        com.zcyh.mr.springboot.output.file.BatchResultStageService.META_EXECUTION_TYPE,
                        context.getExecutionType());
                payload.getJSONObject("batch_meta").put(
                        com.zcyh.mr.springboot.output.file.BatchResultStageService.META_EXECUTION_ID,
                        context.getExecutionId());
                jobPayload.setPayload(payload);
            } catch (PayloadJsonParseException ex) {
                markPayloadFailed(jobPayload, ex);
            }
        }
        context.setJobPayloads(jobPayloads);
    }

    private static void collectScenarioMarketKeys(
            Set<String> target,
            Map<String, Set<String>> tradeMarketDataKeys) {
        if (tradeMarketDataKeys == null || tradeMarketDataKeys.isEmpty()) {
            return;
        }
        for (Set<String> keys : tradeMarketDataKeys.values()) {
            if (keys == null) {
                continue;
            }
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    target.add(key.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
    }

    private static void markPayloadFailed(BatchJobPayload jobPayload, PayloadJsonParseException ex) {
        jobPayload.setFailed(true);
        jobPayload.setErrorCode(BatchJobService.PAYLOAD_JSON_PARSE_ERROR);
        jobPayload.setErrorMessage(ex.getMessage());
    }

    private static String resolveCalcMode(BatchRunWorkflowContext context) {
        return EngineConstants.CALC_MODE.PRICING;
    }
}
