package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.output.file.ScenarioSetPathResolver;

import com.zcyh.mr.springboot.batch.BatchRunTask;
import com.zcyh.mr.springboot.batch.BatchRunWorkflowContext;

import com.zcyh.mr.scenario.model.ScenarioGeneratedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

    private final ScenarioSetPathResolver pathResolver;

    public BatchScenarioFileTask(ScenarioSetPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    @Override
    public void execute(BatchRunWorkflowContext context) {
        if (!context.isScenarioMode()) {
            return;
        }
        if (context.isLocalRerun()) {
            return;
        }
        List<ScenarioGeneratedRecord> records = context.getScenarioRecords();
        if (records == null || records.isEmpty()) {
            throw new IllegalStateException("情景文件写出前缺少情景生成记录，batchId=" + context.getBatchId());
        }

        try {
            String dataDate = context.getDataDate().toString();
            Path batchDir = prepareBatchDirectory(dataDate, context.getBatchId());

            Set<String> requestedScenarioIds = mergeScenarioIds(
                    context.getRegularScenarioIdList(),
                    context.getVarScenarioIdList(),
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
                Path filePath = pathResolver.resolveScenarioFile(
                        dataDate, context.getBatchId(), scenarioId);
                writeScenarioCsvGzip(filePath, scenarioRecords);
                log.info("情景文件已写入: {}, SCENARIO_ID={}, 记录数={}",
                        filePath, scenarioId, scenarioRecords.size());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("写入情景文件失败: " + ex.getMessage(), ex);
        }
    }

    private Path prepareBatchDirectory(String dataDate, String batchId) throws IOException {
        Path batchDir = pathResolver.resolveBatchDirectory(dataDate, batchId);
        deleteRecursively(batchDir);
        Files.createDirectories(batchDir);
        return batchDir;
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
        return value == null ? null : value.toString();
    }

    private static String trimToNull(String txt) {
        if (txt == null) {
            return null;
        }
        String value = txt.trim();
        return value.isEmpty() ? null : value;
    }
}
