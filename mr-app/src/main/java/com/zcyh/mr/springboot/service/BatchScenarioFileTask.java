package com.zcyh.mr.springboot.service;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * 批量情景文件写出任务。
 */
@Component
public class BatchScenarioFileTask implements BatchRunTask {
    private static final Logger log = LoggerFactory.getLogger(BatchScenarioFileTask.class);
    private static final DateTimeFormatter DATE_8_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String[] CSV_HEADERS = new String[]{
            "SCENARIO_ID",
            "SUBSCENARIO_ID",
            "SCENARIO_NAME",
            "SCENARIO_TYPE",
            "CURVE_TYPE",
            "CURVE_CODE",
            "TERM_CODE",
            "TERM_DAYS",
            "DIMENSION2",
            "ORIGINAL_VALUE",
            "CHANGED_RATE",
            "SHIFT_VALUE",
            "SHIFT_RULE",
            "MODIFIER",
            "DATA_DATE"
    };

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
        List<ScenarioGeneratedRecord> records = context.getScenarioRecords();
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("情景文件写出前缺少情景生成记录，batchId=" + context.getBatchId());
        }

        try {
            Path rootDir = prepareRootDir();
            Path batchDir = rootDir.resolve(toSafePathName(context.getBatchId(), "batch_id")).normalize();
            Files.createDirectories(batchDir);

            Set<String> requestedScenarioIds = mergeScenarioIds(
                    context.getRegularScenarioIdList(),
                    context.getRiskClassDecompScenarioIdList(),
                    context.getNormalFullScenarioIdList(),
                    context.getNormalReducedScenarioIdList(),
                    context.getStressReducedScenarioIdList(),
                    context.getNmrfScenarioIdList());
            Map<String, List<ScenarioGeneratedRecord>> grouped = groupByScenarioId(records);

            for (String scenarioId : requestedScenarioIds) {
                List<ScenarioGeneratedRecord> scenarioRecords = grouped.get(scenarioId);
                if (scenarioRecords == null || scenarioRecords.isEmpty()) {
                    throw new IllegalStateException("情景生成结果缺少指定 SCENARIO_ID: " + scenarioId
                            + ", batchId=" + context.getBatchId());
                }
                Path filePath = batchDir.resolve(toSafePathName(scenarioId, "SCENARIO_ID") + ".csv.gz").normalize();
                if (!filePath.startsWith(batchDir)) {
                    throw new IllegalStateException("非法情景文件路径: " + filePath);
                }
                writeScenarioCsvGzip(filePath, scenarioRecords);
                log.info("情景文件已写入: {}, SCENARIO_ID={}, 记录数={}",
                        filePath, scenarioId, scenarioRecords.size());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("写入情景文件失败: " + ex.getMessage(), ex);
        }
    }

    private Path prepareRootDir() throws IOException {
        Path rootDir = Paths.get(scenarioSetRootDir).toAbsolutePath().normalize();
        validateRootDir(rootDir);
        Files.createDirectories(rootDir);
        clearChildren(rootDir);
        return rootDir;
    }

    private void validateRootDir(Path rootDir) {
        if (rootDir == null || rootDir.getParent() == null) {
            throw new IllegalStateException("scenario 数据根目录不能是磁盘根目录");
        }
        String normalized = rootDir.toString().replace('\\', '/').toLowerCase();
        if (normalized.contains("/src/main/resources")) {
            throw new IllegalStateException("scenario 数据目录不能指向源码资源目录: " + rootDir);
        }
    }

    private void clearChildren(Path rootDir) throws IOException {
        if (!Files.exists(rootDir)) {
            return;
        }
        List<Path> children = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(rootDir)) {
            stream.forEach(children::add);
        }
        for (Path child : children) {
            deleteRecursively(child);
        }
    }

    private void deleteRecursively(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(target)) {
            List<Path> paths = new ArrayList<Path>();
            stream.sorted(Comparator.reverseOrder()).forEach(paths::add);
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Set<String> mergeScenarioIds(String... scenarioIdLists) {
        Set<String> result = new LinkedHashSet<String>();
        if (scenarioIdLists == null) {
            return result;
        }
        for (String scenarioIdList : scenarioIdLists) {
            String safe = trimToNull(scenarioIdList);
            if (safe == null) {
                continue;
            }
            for (String part : safe.split(",")) {
                String scenarioId = trimToNull(part);
                if (scenarioId != null) {
                    result.add(scenarioId);
                }
            }
        }
        return result;
    }

    private Map<String, List<ScenarioGeneratedRecord>> groupByScenarioId(List<ScenarioGeneratedRecord> records) {
        Map<String, List<ScenarioGeneratedRecord>> result =
                new LinkedHashMap<String, List<ScenarioGeneratedRecord>>();
        for (ScenarioGeneratedRecord record : records) {
            if (record == null) {
                continue;
            }
            String scenarioId = trimToNull(record.getScenarioId());
            if (scenarioId == null) {
                throw new IllegalStateException("情景生成记录缺少 SCENARIO_ID");
            }
            result.computeIfAbsent(scenarioId, k -> new ArrayList<ScenarioGeneratedRecord>()).add(record);
        }
        return result;
    }

    private String toSafePathName(String value, String fieldName) {
        String safe = trimToNull(value);
        if (safe == null) {
            throw new IllegalStateException(fieldName + " 为空，无法生成情景文件路径");
        }
        if (safe.contains("/") || safe.contains("\\") || safe.contains(":")
                || safe.contains("*") || safe.contains("?") || safe.contains("\"")
                || safe.contains("<") || safe.contains(">") || safe.contains("|")
                || safe.contains("..")) {
            throw new IllegalStateException(fieldName + " 包含非法文件名字符: " + safe);
        }
        return safe;
    }

    private void writeScenarioCsvGzip(Path filePath, List<ScenarioGeneratedRecord> records) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(filePath));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
            writer.write(String.join(",", CSV_HEADERS));
            writer.newLine();
            for (ScenarioGeneratedRecord record : records) {
                writer.write(toCsvLine(record));
                writer.newLine();
            }
        }
    }

    private String toCsvLine(ScenarioGeneratedRecord record) {
        String[] values = new String[]{
                record.getScenarioId(),
                record.getSubScenarioId(),
                record.getScenarioName(),
                record.getScenarioType(),
                record.getCurveType(),
                record.getCurveCode(),
                record.getTermCode(),
                record.getTermDays() == null ? null : String.valueOf(record.getTermDays()),
                record.getDimension2(),
                decimalText(record.getOriginalValue()),
                decimalText(record.getChangedValue()),
                decimalText(record.getShiftValue()),
                record.getShiftRule(),
                record.getModifier(),
                dateText(record.getDataDate())
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvEscape(values[i]));
        }
        return sb.toString();
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String dateText(LocalDate value) {
        return value == null ? null : value.format(DATE_8_FORMATTER);
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
