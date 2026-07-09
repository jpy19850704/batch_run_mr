package com.zcyh.mr.springboot.service;

import com.zcyh.mr.springboot.out.db.GeneratedMarketDataPersistService;

import static com.zcyh.mr.springboot.support.RequestParseSupport.trimToNull;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.engine.EngineAdapter;
import com.zcyh.mr.springboot.engine.EngineRegistry;
import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunRequest;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.springframework.stereotype.Service;

/**
 * 引擎编排服务。
 */
@Service
public class EngineOrchestratorService {
    private final EngineRegistry registry;
    private final GeneratedMarketDataPersistService generatedMarketDataPersistService;

    public EngineOrchestratorService(EngineRegistry registry,
                                     GeneratedMarketDataPersistService generatedMarketDataPersistService) {
        this.registry = registry;
        this.generatedMarketDataPersistService = generatedMarketDataPersistService;
    }

    public EngineRunResult run(EngineRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }
        if (request.getPayload() == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }

        String engineCode = trimToNull(request.getEngineCode());
        if (engineCode == null) {
            engineCode = MrCalcEngineAdapter.CODE;
        }

        EngineAdapter adapter = registry.get(engineCode);
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

        long start = System.currentTimeMillis();
        String raw = adapter.calculate(payloadJson);
        long elapsed = System.currentTimeMillis() - start;

        Object parsedData = parseJsonSafely(raw);
        generatedMarketDataPersistService.persistIfRequested(engineCode, payloadJson, parsedData);

        EngineRunResult result = new EngineRunResult();
        result.setRequestId(request.getRequestId());
        result.setEngineCode(adapter.code());
        result.setSuccess(true);
        result.setElapsedMs(elapsed);
        result.setData(parsedData);
        return result;
    }

    public java.util.List<java.util.Map<String, String>> listEngines() {
        java.util.List<java.util.Map<String, String>> items = new java.util.ArrayList<java.util.Map<String, String>>();
        for (EngineAdapter adapter : registry.list()) {
            java.util.Map<String, String> row = new java.util.LinkedHashMap<String, String>();
            row.put("code", adapter.code());
            row.put("description", adapter.description());
            items.add(row);
        }
        return items;
    }

    private static Object parseJsonSafely(String raw) {
        if (raw == null) {
            return null;
        }
        String txt = raw.trim();
        if (txt.isEmpty()) {
            return txt;
        }
        try {
            return JSON.parse(txt);
        } catch (Exception ignore) {
            return txt;
        }
    }

}
