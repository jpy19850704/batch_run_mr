package com.zcyh.mr.springboot.engine;

/**
 * 引擎适配接口。
 */
public interface EngineAdapter {
    /**
     * 引擎唯一编码。
     */
    String code();

    /**
     * 引擎说明。
     */
    String description();

    /**
     * 执行引擎计算。
     *
     * @param inputJson 输入 JSON
     * @return 输出 JSON
     */
    String calculate(String inputJson);
}

