package com.zcyh.mr.springboot.engine;

/**
 * VaR 引擎适配器。
 */
public class VarEngineAdapter implements EngineAdapter {
    public static final String CODE = "var";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String description() {
        return "VaR engine adapter for db_inline mode";
    }

    @Override
    public String calculate(String inputJson) {
        throw new IllegalArgumentException("var 引擎仅支持 source_type=db_inline 调用");
    }
}

