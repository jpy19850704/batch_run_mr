package com.zcyh.mr.springboot.measurement.valuation;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.calc.Calc;
import com.zcyh.mr.calc.scenario.ScenarioProcessConstants;
import com.zcyh.mr.calc.scenario.CalcScenarioInputCache;
import com.zcyh.mr.frtbima.common.LiquidityHorizonTable;
import com.zcyh.mr.springboot.execution.ExecutionAdapter;
import com.zcyh.mr.springboot.measurement.ima.ImaRiskFactorConfigService;
import com.zcyh.mr.springboot.scenario.SharedScenarioInputLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 交易估值执行适配器。
 */
public class ValuationExecutionAdapter implements ExecutionAdapter {
    public static final String CODE = "MR_CALC";
    private static final Logger log = LoggerFactory.getLogger(ValuationExecutionAdapter.class);
    private final ImaRiskFactorConfigService imaRiskFactorConfigService;
    private final SharedScenarioInputLoader sharedScenarioInputLoader;

    public ValuationExecutionAdapter(ImaRiskFactorConfigService imaRiskFactorConfigService) {
        this(imaRiskFactorConfigService, null);
    }

    public ValuationExecutionAdapter(ImaRiskFactorConfigService imaRiskFactorConfigService,
                                     SharedScenarioInputLoader sharedScenarioInputLoader) {
        this.imaRiskFactorConfigService = imaRiskFactorConfigService;
        this.sharedScenarioInputLoader = sharedScenarioInputLoader;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "MR pricing engine adapter based on com.zcyh.mr.calc.Calc";
    }

    @Override
    public String execute(String inputJson) {
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }

        if (req.containsKey("batch_tasks")) {
            throw new IllegalArgumentException("mr 不再支持 batch_tasks，请改为单任务调用或走调度层拆批");
        }

        if (sharedScenarioInputLoader != null) {
            sharedScenarioInputLoader.ensureLoaded(req);
        }
        JSONObject singlePayload = validateScenarioInputCacheReferences(req);
        return runSingle(singlePayload);
    }

    private String runSingle(JSONObject taskPayload) {
        long totalStart = System.nanoTime();
        long configStart = System.nanoTime();
        LiquidityHorizonTable imaRiskFactorConfig = loadImaRiskFactorConfigIfNeeded(taskPayload);
        double configMs = elapsedMs(configStart);
        long inputStart = System.nanoTime();
        Calc calc = new Calc(
                taskPayload.toJSONString(JSONWriter.Feature.WriteBigDecimalAsPlain),
                null,
                imaRiskFactorConfig);
        double inputMs = elapsedMs(inputStart);
        long calcStart = System.nanoTime();
        String result = calc.run();
        log.info("估值性能统计: batchId={}, seqNo={}, tradeCount={}, configMs={}, inputPrepareMs={}, calcRunMs={}, totalMs={}",
                batchId(taskPayload), seqNo(taskPayload), tradeCount(taskPayload), configMs, inputMs,
                elapsedMs(calcStart), elapsedMs(totalStart));
        return result;
    }

    private static String batchId(JSONObject payload) {
        JSONObject batchMeta = payload == null ? null : payload.getJSONObject("batch_meta");
        return batchMeta == null ? null : batchMeta.getString("batch_id");
    }

    private static Integer seqNo(JSONObject payload) {
        JSONObject batchMeta = payload == null ? null : payload.getJSONObject("batch_meta");
        return batchMeta == null ? null : batchMeta.getInteger("seq_no");
    }

    private static int tradeCount(JSONObject payload) {
        JSONArray trades = payload == null ? null : payload.getJSONArray("trade_data");
        return trades == null ? 0 : trades.size();
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0d;
    }

    LiquidityHorizonTable loadImaRiskFactorConfigIfNeeded(JSONObject payload) {
        if (!hasImaScenarioRefList(payload)) {
            return null;
        }
        if (imaRiskFactorConfigService == null) {
            throw new IllegalStateException("IMA 计量缺少风险因子配置读取服务");
        }
        String dataDate = requiredPayloadField(payload, "data_date");
        return imaRiskFactorConfigService.loadImaRiskFactorConfig(
                LocalDate.parse(dataDate, DateTimeFormatter.ISO_LOCAL_DATE));
    }

    private JSONObject validateScenarioInputCacheReferences(JSONObject payload) {
        if (payload == null) {
            return null;
        }
        if (!hasScenarioRefList(payload)) {
            return payload;
        }

        validateScenarioRefList(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST);
        validateScenarioRefList(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST);
        validateScenarioRefList(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST);
        validateScenarioRefList(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST);
        return payload;
    }

    private void validateScenarioRefList(JSONObject payload, String fieldName) {
        JSONArray items = payload.getJSONArray(fieldName);
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException(fieldName + "[" + i + "] 必须是 JSON 对象");
            }
            String cacheKey = trimToNull(item.getString("cache_key"));
            if (cacheKey == null) {
                throw new IllegalArgumentException(fieldName + "[" + i + "].cache_key 必填");
            }
            if (!CalcScenarioInputCache.contains(cacheKey)) {
                throw new IllegalStateException("计量情景输入缓存未找到场景数据: cache_key=" + cacheKey);
            }
        }
    }

    private static boolean hasScenarioRefList(JSONObject payload) {
        return hasArray(payload, ScenarioProcessConstants.REGULAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.VAR_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST);
    }

    private static boolean hasImaScenarioRefList(JSONObject payload) {
        return hasArray(payload, ScenarioProcessConstants.IMA_MODELLABLE_SCENARIO_REF_LIST)
                || hasArray(payload, ScenarioProcessConstants.IMA_NMRF_SCENARIO_REF_LIST);
    }

    private static boolean hasArray(JSONObject payload, String fieldName) {
        JSONArray arr = payload == null ? null : payload.getJSONArray(fieldName);
        return arr != null && !arr.isEmpty();
    }

    private static String requiredPayloadField(JSONObject payload, String key) {
        String val = payload.getString(key);
        if (val == null || val.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " 必填");
        }
        return val.trim();
    }

}
