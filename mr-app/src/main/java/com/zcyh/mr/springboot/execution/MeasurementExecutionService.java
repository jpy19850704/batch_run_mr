package com.zcyh.mr.springboot.execution;

import com.zcyh.mr.springboot.output.db.GeneratedMarketDataPersistService;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.measurement.valuation.ValuationExecutionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 计量执行服务。
 */
@Service
public class MeasurementExecutionService {
    private static final Logger log = LoggerFactory.getLogger(MeasurementExecutionService.class);
    private final ExecutionRegistry registry;
    private final GeneratedMarketDataPersistService generatedMarketDataPersistService;

    public MeasurementExecutionService(ExecutionRegistry registry,
                                     GeneratedMarketDataPersistService generatedMarketDataPersistService) {
        this.registry = registry;
        this.generatedMarketDataPersistService = generatedMarketDataPersistService;
    }

    public MeasurementExecutionResult run(MeasurementExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (request.getPayload() == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }

        String engineCode = trimToNull(request.getEngineCode());
        if (engineCode == null) {
            engineCode = ValuationExecutionAdapter.CODE;
        }

        ExecutionAdapter adapter = registry.get(engineCode);
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的 engine_code: " + engineCode);
        }

        String payloadJson;
        Object payload = request.getPayload();
        if (payload instanceof String) {
            payloadJson = (String) payload;
        } else {
            payloadJson = JSON.toJSONString(payload, JSONWriter.Feature.WriteBigDecimalAsPlain);
        }
        if (trimToNull(payloadJson) == null) {
            throw new IllegalArgumentException("payload 不能为空字符串");
        }

        long start = System.nanoTime();
        String raw = adapter.execute(payloadJson);
        double adapterMs = elapsedMs(start);

        long parseStart = System.nanoTime();
        Object parsedData = parseJsonResult(raw);
        double outputParseMs = elapsedMs(parseStart);
        long generatedMarketPersistStart = System.nanoTime();
        generatedMarketDataPersistService.persistIfRequested(engineCode, payloadJson, parsedData);
        double generatedMarketPersistMs = elapsedMs(generatedMarketPersistStart);
        long elapsed = Math.round(elapsedMs(start));
        log.info("计量执行性能统计: requestId={}, engineCode={}, adapterMs={}, outputParseMs={}, generatedMarketPersistMs={}, totalMs={}",
                request.getRequestId(), engineCode, adapterMs, outputParseMs, generatedMarketPersistMs, elapsed);

        MeasurementExecutionResult result = new MeasurementExecutionResult();
        result.setRequestId(request.getRequestId());
        result.setEngineCode(adapter.code());
        result.setSuccess(true);
        result.setElapsedMs(elapsed);
        result.setData(parsedData);
        return result;
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0d;
    }

    public java.util.List<java.util.Map<String, String>> listExecutions() {
        java.util.List<java.util.Map<String, String>> items = new java.util.ArrayList<java.util.Map<String, String>>();
        for (ExecutionAdapter adapter : registry.list()) {
            java.util.Map<String, String> row = new java.util.LinkedHashMap<String, String>();
            row.put("code", adapter.code());
            row.put("description", adapter.description());
            items.add(row);
        }
        return items;
    }

    private static Object parseJsonResult(String raw) {
        if (trimToNull(raw) == null) {
            throw new IllegalStateException("计量执行结果不能为空");
        }
        try {
            return JSON.parse(raw);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("计量执行结果必须是合法 JSON", ex);
        }
    }

}
