package com.zcyh.mr.springboot.output.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;

/**
 * 情景文件目录解析器。
 */
@Component
public class ScenarioSetPathResolver {
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final String scenarioSetRootDir;

    public ScenarioSetPathResolver(@Value("${mr.calc.scenario-set.root-dir:}") String scenarioSetRootDir) {
        this.scenarioSetRootDir = scenarioSetRootDir == null ? "" : scenarioSetRootDir.trim();
    }

    public Path resolveBatchDirectory(String dataDate, String batchId) {
        Path rootDir = resolveRootDirectory();
        Path dataDateDir = rootDir.resolve(normalizeDataDate(dataDate)).normalize();
        Path batchDir = dataDateDir.resolve(toSafePathName(batchId, "batch_id")).normalize();
        if (!batchDir.startsWith(dataDateDir)) {
            throw new IllegalArgumentException("非法情景批次目录: " + batchDir);
        }
        return batchDir;
    }

    public Path resolveScenarioFile(String dataDate, String batchId, String scenarioId) {
        Path batchDir = resolveBatchDirectory(dataDate, batchId);
        Path filePath = batchDir.resolve(toSafePathName(scenarioId, "SCENARIO_ID") + ".csv.gz").normalize();
        if (!filePath.startsWith(batchDir)) {
            throw new IllegalArgumentException("非法情景文件路径: " + filePath);
        }
        return filePath;
    }

    public void resetBatchDirectory(String dataDate, String batchId) {
        if (scenarioSetRootDir.isEmpty()) {
            return;
        }
        Path batchDirectory = resolveBatchDirectory(dataDate, batchId);
        try {
            if (!Files.exists(batchDirectory)) {
                return;
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(batchDirectory)) {
                Path[] paths = stream.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : paths) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("清理情景批次目录失败: " + batchDirectory, ex);
        }
    }

    private Path resolveRootDirectory() {
        if (scenarioSetRootDir.isEmpty()) {
            throw new IllegalArgumentException("缺少 scenario 数据目录，请配置 mr.calc.scenario-set.root-dir");
        }
        Path rootDir = Paths.get(scenarioSetRootDir).toAbsolutePath().normalize();
        if (rootDir.getParent() == null) {
            throw new IllegalArgumentException("scenario 数据根目录不能是磁盘根目录");
        }
        String normalized = rootDir.toString().replace('\\', '/').toLowerCase();
        if (normalized.contains("/src/main/resources")) {
            throw new IllegalArgumentException("scenario 数据目录不能指向源码资源目录: " + rootDir);
        }
        return rootDir;
    }

    private static String normalizeDataDate(String dataDate) {
        String safe = trimToNull(dataDate);
        if (safe == null) {
            throw new IllegalArgumentException("data_date 为空，无法定位情景文件");
        }
        try {
            return LocalDate.parse(safe, DATE_8_FORMATTER).format(DATE_8_FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("data_date 格式错误，仅支持 yyyyMMdd: " + dataDate);
        }
    }

    private static String toSafePathName(String value, String fieldName) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalArgumentException(fieldName + " 为空，无法定位情景文件");
        }
        if (safe.contains("/") || safe.contains("\\") || safe.contains(":")
                || safe.contains("*") || safe.contains("?") || safe.contains("\"")
                || safe.contains("<") || safe.contains(">") || safe.contains("|")
                || safe.contains("..")) {
            throw new IllegalArgumentException(fieldName + " 包含非法文件名字符: " + safe);
        }
        return safe;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
