package com.zcyh.mr.springboot.execution;

/**
 * 计量执行适配接口。
 */
public interface ExecutionAdapter {
    /**
     * 执行类型唯一编码。
     */
    String code();

    /**
     * 执行类型说明。
     */
    String description();

    /**
     * 执行计量。
     *
     * @param inputJson 输入 JSON
     * @return 输出 JSON
     */
    String execute(String inputJson);
}
