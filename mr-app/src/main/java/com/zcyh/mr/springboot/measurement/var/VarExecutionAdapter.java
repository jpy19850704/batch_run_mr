package com.zcyh.mr.springboot.measurement.var;

import com.zcyh.mr.springboot.execution.ExecutionAdapter;

/**
 * VaR 执行适配器。
 */
public class VarExecutionAdapter implements ExecutionAdapter {
    public static final String CODE = "var";

    public VarExecutionAdapter() {
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
    public String execute(String inputJson) {
        throw new IllegalArgumentException("VaR 规则计算请使用 /api/summary/var 或 /api/trial/var");
    }
}
