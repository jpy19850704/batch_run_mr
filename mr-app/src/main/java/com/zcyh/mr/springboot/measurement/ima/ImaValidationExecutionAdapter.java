package com.zcyh.mr.springboot.measurement.ima;

import com.zcyh.mr.springboot.execution.ExecutionAdapter;

import com.zcyh.mr.springboot.measurement.ima.ImaValidationService;
import org.springframework.stereotype.Component;

/**
 * IMA 校验执行适配器。
 */
@Component
public class ImaValidationExecutionAdapter implements ExecutionAdapter {
    public static final String CODE = "ima_validation";

    private final ImaValidationService imaValidationService;

    public ImaValidationExecutionAdapter(ImaValidationService imaValidationService) {
        this.imaValidationService = imaValidationService;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "IMA 返回检验与 KS 检验";
    }

    @Override
    public String execute(String inputJson) {
        return imaValidationService.calculate(inputJson);
    }
}
