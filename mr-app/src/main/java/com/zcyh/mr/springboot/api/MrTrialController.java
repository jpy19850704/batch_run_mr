package com.zcyh.mr.springboot.api;

import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.model.ApiResponse;
import com.zcyh.mr.springboot.model.FrtbRuleTrialRequest;
import com.zcyh.mr.springboot.model.VarTrialRequest;
import com.zcyh.mr.springboot.service.FrtbDrcDbRunnerService;
import com.zcyh.mr.springboot.service.FrtbSbaDbRunnerService;
import com.zcyh.mr.springboot.service.VarDbRunnerService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 临时规则试算接口。
 */
@RestController
@RequestMapping("/api/trial")
public class MrTrialController {
    private final FrtbSbaDbRunnerService frtbSbaDbRunnerService;
    private final FrtbDrcDbRunnerService frtbDrcDbRunnerService;
    private final VarDbRunnerService varDbRunnerService;

    public MrTrialController(FrtbSbaDbRunnerService frtbSbaDbRunnerService,
                             FrtbDrcDbRunnerService frtbDrcDbRunnerService,
                             VarDbRunnerService varDbRunnerService) {
        this.frtbSbaDbRunnerService = frtbSbaDbRunnerService;
        this.frtbDrcDbRunnerService = frtbDrcDbRunnerService;
        this.varDbRunnerService = varDbRunnerService;
    }

    @PostMapping("/frtb/sba")
    public ApiResponse<Object> sba(@RequestBody JSONObject request) {
        FrtbRuleTrialRequest parsed = TrialRequestParser.parseSba(request);
        return ApiResponse.ok(frtbSbaDbRunnerService.calculate(
                parsed.getBatchId(),
                parsed.getDataDate(),
                parsed.getRuleDefinitions(),
                parsed.isNeedDecompose(),
                parsed.getThreadCount()));
    }

    @PostMapping("/frtb/drc")
    public ApiResponse<Object> drc(@RequestBody JSONObject request) {
        FrtbRuleTrialRequest parsed = TrialRequestParser.parseDrc(request);
        return ApiResponse.ok(frtbDrcDbRunnerService.calculate(
                parsed.getBatchId(),
                parsed.getDataDate(),
                parsed.getRuleDefinitions()));
    }

    @PostMapping("/var")
    public ApiResponse<Object> var(@RequestBody JSONObject request) {
        VarTrialRequest parsed = TrialRequestParser.parseVar(request);
        return ApiResponse.ok(varDbRunnerService.calculateTrial(
                parsed.getBatchId(),
                parsed.getDataDate(),
                parsed.getCalculations(),
                parsed.isIncludeDetail(),
                parsed.getRequestId()));
    }
}
