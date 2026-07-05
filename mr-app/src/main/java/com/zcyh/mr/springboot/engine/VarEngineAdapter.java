package com.zcyh.mr.springboot.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.service.VarDbRunnerService;

/**
 * VaR 引擎适配器。
 */
public class VarEngineAdapter implements EngineAdapter {
    public static final String CODE = "var";
    private final VarDbRunnerService dbRunnerService;

    public VarEngineAdapter() {
        this(null);
    }

    public VarEngineAdapter(VarDbRunnerService dbRunnerService) {
        this.dbRunnerService = dbRunnerService;
    }

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
        JSONObject req = JSON.parseObject(inputJson);
        if (req == null) {
            throw new IllegalArgumentException("payload 必须是 JSON 对象");
        }
        String sourceType = trimToNull(req.getString("source_type"));
        if ("db_inline".equalsIgnoreCase(sourceType)) {
            return requireDbRunner().calculateByInline(inputJson);
        }
        if (sourceType != null) {
            throw new IllegalArgumentException("var 不支持的 source_type: " + sourceType);
        }
        throw new IllegalArgumentException("var 引擎仅支持 source_type=db_inline 调用");
    }

    private VarDbRunnerService requireDbRunner() {
        if (dbRunnerService == null) {
            throw new IllegalStateException("var 数据库执行服务未配置");
        }
        return dbRunnerService;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
