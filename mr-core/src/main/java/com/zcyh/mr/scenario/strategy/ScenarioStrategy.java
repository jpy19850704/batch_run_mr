package com.zcyh.mr.scenario.strategy;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import com.zcyh.mr.scenario.model.ScenarioTaskRequest;

import java.util.List;

/**
 * 情景生成策略接口
 */
public interface ScenarioStrategy {

    /**
     * 生成情景
     * 
     * @param task       标准化后的场景任务
     * @param user       用户
     * @return 情景数据列表
     */
    List<ScenarioGeneratedRecord> generate(
            ScenarioTaskRequest task,
            String user);
}

