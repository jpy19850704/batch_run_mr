package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.engine.ImaCapitalEngineAdapter;
import com.zcyh.mr.springboot.model.RuleSummaryRequest;
import org.springframework.stereotype.Service;

/**
 * IMA 资本汇总服务。
 */
@Service
public class ImaCapitalSummaryService {
    private final ImaCapitalEngineAdapter imaCapitalEngineAdapter;

    public ImaCapitalSummaryService(ImaCapitalEngineAdapter imaCapitalEngineAdapter) {
        this.imaCapitalEngineAdapter = imaCapitalEngineAdapter;
    }

    public JSONObject summarize(RuleSummaryRequest request) {
        return imaCapitalEngineAdapter.summarize(request);
    }
}
