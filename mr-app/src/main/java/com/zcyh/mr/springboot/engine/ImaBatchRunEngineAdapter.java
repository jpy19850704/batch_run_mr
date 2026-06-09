package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.springboot.model.BatchRunResult;
import com.zcyh.mr.springboot.model.ImaBatchRunRequest;
import com.zcyh.mr.springboot.service.ImaBatchRunService;
import org.springframework.stereotype.Component;

/**
 * IMA 批量计量工作流适配器。
 */
@Component
public class ImaBatchRunEngineAdapter implements EngineAdapter {
    private final ImaBatchRunService imaBatchRunService;

    public ImaBatchRunEngineAdapter(ImaBatchRunService imaBatchRunService) {
        this.imaBatchRunService = imaBatchRunService;
    }

    @Override
    public String code() {
        return ImaBatchRunService.ENGINE_CODE;
    }

    @Override
    public String description() {
        return "IMA 批量计量工作流：情景生成、RFET索引、IMA PnL、IMA资本";
    }

    @Override
    public String calculate(String inputJson) {
        ImaBatchRunRequest request = JSON.parseObject(inputJson, ImaBatchRunRequest.class);
        BatchRunResult result = imaBatchRunService.run(request);
        return JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
    }
}
