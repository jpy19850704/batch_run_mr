package com.zcyh.mr.springboot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 批量情景文件写出任务。
 */
@Component
public class BatchScenarioFileTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchScenarioFileTask.class);

    private final String scenarioSetRootDir;

    public BatchScenarioFileTask(@Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir) {
        this.scenarioSetRootDir = scenarioSetRootDir == null ? "" : scenarioSetRootDir.trim();
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isScenarioMode()) {
            return;
        }
        if (trimToNull(scenarioSetRootDir) == null) {
            throw new IllegalStateException("SCENARIO 模式要求已配置 mr.calc.scenario-set.root-dir，用于生成并落地情景文件");
        }
        String scenarioJson = trimToNull(context.getScenarioJson());
        if (scenarioJson == null) {
            throw new IllegalStateException("情景文件写出前缺少 scenarioJson，batchId=" + context.getBatchId());
        }
        try {
            java.nio.file.Path rootDir = java.nio.file.Paths.get(scenarioSetRootDir);
            java.nio.file.Files.createDirectories(rootDir);

            String mainFileName = context.getScenarioIdList() + "_" + context.getDataDate() + "_" + context.getBatchId() + ".json";
            java.nio.file.Path mainFilePath = rootDir.resolve(mainFileName);
            java.nio.file.Files.writeString(mainFilePath, scenarioJson, java.nio.charset.StandardCharsets.UTF_8);
            log.info("主场景文件已写入: {}, 记录数={}", mainFilePath, context.getScenarioData().size());

            String decompFileName = "DECOMP_" + context.getScenarioIdList() + "_" + context.getDataDate()
                    + "_" + context.getBatchId() + ".json";
            java.nio.file.Path decompFilePath = rootDir.resolve(decompFileName);
            java.nio.file.Files.writeString(decompFilePath, scenarioJson, java.nio.charset.StandardCharsets.UTF_8);
            log.info("Decomp 场景文件已写入: {}", decompFilePath);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("写入情景文件失败: " + ex.getMessage(), ex);
        }
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
