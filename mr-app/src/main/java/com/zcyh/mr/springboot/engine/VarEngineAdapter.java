package com.zcyh.mr.springboot.engine;

/**
 * VaR 引擎适配器。
 */
public class VarEngineAdapter implements EngineAdapter {
    public static final String CODE = "var";

    public VarEngineAdapter() {
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "VaR 规则计算独立接口提示适配器";
    }

    @Override
    public String calculate(String inputJson) {
        throw new IllegalArgumentException("VaR 规则计算请使用 /api/summary/var 或 /api/trial/var");
    }
}
