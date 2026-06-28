package com.zcyh.mr.springboot.engine;

import com.zcyh.mr.springboot.service.ImaValidationService;
import org.springframework.stereotype.Component;

/**
 * IMA 校验引擎适配器。
 */
@Component
public class ImaValidationEngineAdapter implements EngineAdapter {
    public static final String CODE = "ima_validation";

    private final ImaValidationService imaValidationService;

    public ImaValidationEngineAdapter(ImaValidationService imaValidationService) {
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
    public String calculate(String inputJson) {
        return imaValidationService.calculate(inputJson);
    }
}
