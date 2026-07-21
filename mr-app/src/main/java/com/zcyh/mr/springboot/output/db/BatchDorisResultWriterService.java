package com.zcyh.mr.springboot.output.db;

import com.zcyh.mr.springboot.output.file.BatchResultStageService;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 将当前批次执行的分片 CSV 统一写入 Doris。
 */
@Service
public class BatchDorisResultWriterService {
    private final BatchResultStageService stageService;
    private final PricingResultPersistService pricingResultPersistService;
    private final MrCalcDetailCleanupService cleanupService;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final int streamLoadBatchSize;

    public BatchDorisResultWriterService(
            BatchResultStageService stageService,
            PricingResultPersistService pricingResultPersistService,
            MrCalcDetailCleanupService cleanupService,
            DorisStreamLoadService dorisStreamLoadService,
            @Value("${mr.doris.result.batch-size:50000}") int streamLoadBatchSize) {
        this.stageService = stageService;
        this.pricingResultPersistService = pricingResultPersistService;
        this.cleanupService = cleanupService;
        this.dorisStreamLoadService = dorisStreamLoadService;
        this.streamLoadBatchSize = Math.max(1000, streamLoadBatchSize);
    }

    public void persistExecution(String batchId,
                                 LocalDate dataDate,
                                 String executionType,
                                 String executionId,
                                 int expectedJobs,
                                 List<String> patchInstrumentIds) {
        List<Path> readyDirectories = stageService.listReady(batchId, executionType, executionId);
        List<Path> completedDirectories = stageService.listCompleted(batchId, executionType, executionId);
        if (readyDirectories.isEmpty() && completedDirectories.size() == expectedJobs) {
            return;
        }
        if (expectedJobs <= 0 || readyDirectories.size() + completedDirectories.size() != expectedJobs) {
            throw new IllegalStateException("分片结果CSV数量不完整: batchId=" + batchId
                    + ", executionId=" + executionId
                    + ", expectedJobs=" + expectedJobs
                    + ", readyJobs=" + readyDirectories.size()
                    + ", completedJobs=" + completedDirectories.size());
        }

        if (BatchResultStageService.EXECUTION_TYPE_PATCH.equalsIgnoreCase(executionType)) {
            cleanupService.cleanupInstruments(batchId, dataDate, patchInstrumentIds);
        } else {
            cleanupService.cleanupBatchDetails(batchId, dataDate);
        }

        List<Path> sourceDirectories = new ArrayList<Path>(completedDirectories.size() + readyDirectories.size());
        sourceDirectories.addAll(completedDirectories);
        sourceDirectories.addAll(readyDirectories);
        String attemptId = Long.toString(System.currentTimeMillis());
        for (PricingResultPersistService.StagedCsvTable table
                : pricingResultPersistService.stagedCsvTables()) {
            StagedCsvLoadBuffer buffer = new StagedCsvLoadBuffer(
                    dorisStreamLoadService,
                    table.getTableName(),
                    table.getColumns(),
                    table.getTableName().toLowerCase() + "_" + executionId + "_" + attemptId,
                    streamLoadBatchSize);
            for (Path sourceDirectory : sourceDirectories) {
                Path csvFile = sourceDirectory.resolve(table.getFileName());
                if (!Files.isRegularFile(csvFile)) {
                    throw new IllegalStateException("分片结果缺少CSV文件: " + csvFile);
                }
                buffer.append(csvFile);
            }
            buffer.flush();
        }
        for (Path readyDirectory : readyDirectories) {
            stageService.markCompleted(readyDirectory);
        }
    }

    private static final class StagedCsvLoadBuffer {
        private final DorisStreamLoadService dorisStreamLoadService;
        private final String tableName;
        private final String columns;
        private final String labelPrefix;
        private final int batchSize;
        private final StringBuilder csv = new StringBuilder(1024 * 1024);
        private int rowCount;
        private int chunkNo;

        private StagedCsvLoadBuffer(DorisStreamLoadService dorisStreamLoadService,
                                    String tableName,
                                    String columns,
                                    String labelPrefix,
                                    int batchSize) {
            this.dorisStreamLoadService = dorisStreamLoadService;
            this.tableName = tableName;
            this.columns = columns;
            this.labelPrefix = labelPrefix;
            this.batchSize = batchSize;
        }

        private void append(Path csvFile) {
            try (BufferedReader reader = Files.newBufferedReader(csvFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    csv.append(line).append('\n');
                    rowCount++;
                    if (rowCount >= batchSize) {
                        flush();
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException("读取分片结果CSV失败: " + csvFile, ex);
            }
        }

        private void flush() {
            if (rowCount == 0) {
                return;
            }
            chunkNo++;
            dorisStreamLoadService.loadCsv(
                    tableName,
                    columns,
                    csv.toString().getBytes(StandardCharsets.UTF_8),
                    labelPrefix + "_chunk" + chunkNo);
            csv.setLength(0);
            rowCount = 0;
        }
    }
}
