package com.zcyh.mr.springboot.output.file;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.zcyh.mr.springboot.execution.MeasurementExecutionResult;
import com.zcyh.mr.springboot.output.db.PricingResultPersistService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 管理批量计量分片结果的 CSV 暂存目录。
 */
@Service
public class BatchResultStageService {
    public static final String EXECUTION_TYPE_BATCH = "BATCH";
    public static final String EXECUTION_TYPE_PATCH = "PATCH";
    public static final String BATCH_EXECUTION_ID = "batch";
    public static final String META_EXECUTION_TYPE = "execution_type";
    public static final String META_EXECUTION_ID = "execution_id";

    private final Path stageRoot;
    private final PricingResultPersistService pricingResultPersistService;

    public BatchResultStageService(
            PricingResultPersistService pricingResultPersistService,
            @Value("${mr.batch.result.stage-dir:./data/batch-result-stage}") String stageDir) {
        this.pricingResultPersistService = pricingResultPersistService;
        this.stageRoot = Paths.get(trimToNull(stageDir) == null
                ? "./data/batch-result-stage" : stageDir.trim()).toAbsolutePath().normalize();
        if (stageRoot.getParent() == null) {
            throw new IllegalArgumentException("批量结果暂存目录不能是磁盘根目录");
        }
    }

    public void stage(String jobId,
                      String requestId,
                      String payloadJson,
                      MeasurementExecutionResult runResult) {
        JSONObject payload = parsePayload(payloadJson);
        JSONObject batchMeta = requireBatchMeta(payload);
        String batchId = requireText(batchMeta.getString("batch_id"), "batch_id 不能为空");
        String executionType = normalizeExecutionType(batchMeta.getString(META_EXECUTION_TYPE));
        String executionId = requireText(batchMeta.getString(META_EXECUTION_ID), "execution_id 不能为空");
        Path executionDir = resolveExecutionDirectory(batchId, executionType, executionId);
        String safeJobId = safeToken(requireText(jobId, "jobId 不能为空"));
        Path writing = executionDir.resolve(safeJobId + ".writing").normalize();
        Path ready = executionDir.resolve(safeJobId + ".ready").normalize();
        ensureChild(executionDir, writing);
        ensureChild(executionDir, ready);
        try {
            Files.createDirectories(executionDir);
            deleteRecursively(writing);
            deleteRecursively(ready);
            Files.createDirectories(writing);
            pricingResultPersistService.writeJobResultCsv(
                    writing, requestId, jobId, payloadJson, runResult);
            moveAtomically(writing, ready);
        } catch (IOException ex) {
            throw new IllegalStateException("暂存分片结果CSV失败: batchId=" + batchId
                    + ", executionId=" + executionId + ", jobId=" + jobId, ex);
        }
    }

    public List<Path> listReady(String batchId, String executionType, String executionId) {
        Path directory = resolveExecutionDirectory(batchId, executionType, executionId);
        List<Path> result = new ArrayList<Path>();
        if (!Files.isDirectory(directory)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.ready")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    result.add(path);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取分片结果暂存目录失败: " + directory, ex);
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    public long countCompleted(String batchId, String executionType, String executionId) {
        return listCompleted(batchId, executionType, executionId).size();
    }

    public List<Path> listCompleted(String batchId, String executionType, String executionId) {
        Path completed = resolveExecutionDirectory(batchId, executionType, executionId).resolve("completed");
        List<Path> result = new ArrayList<Path>();
        if (!Files.isDirectory(completed)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(completed, "*.ready")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    result.add(path);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取已完成分片目录失败: " + completed, ex);
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    public void markCompleted(Path readyDirectory) {
        Path executionDirectory = readyDirectory.getParent();
        Path completedDirectory = executionDirectory.resolve("completed").normalize();
        Path completed = completedDirectory.resolve(readyDirectory.getFileName()).normalize();
        ensureChild(executionDirectory, completed);
        try {
            Files.createDirectories(completedDirectory);
            deleteRecursively(completed);
            moveAtomically(readyDirectory, completed);
        } catch (IOException ex) {
            throw new IllegalStateException("归档已写入分片结果失败: " + readyDirectory, ex);
        }
    }

    public void resetBatch(String batchId) {
        Path batchDirectory = stageRoot.resolve(safeToken(requireText(batchId, "batchId 不能为空"))).normalize();
        ensureChild(stageRoot, batchDirectory);
        try {
            deleteRecursively(batchDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("清理批次结果暂存目录失败: " + batchDirectory, ex);
        }
    }

    private Path resolveExecutionDirectory(String batchId, String executionType, String executionId) {
        Path batchDirectory = stageRoot.resolve(safeToken(requireText(batchId, "batchId 不能为空"))).normalize();
        ensureChild(stageRoot, batchDirectory);
        String type = normalizeExecutionType(executionType);
        Path result = EXECUTION_TYPE_BATCH.equals(type)
                ? batchDirectory.resolve("batch")
                : batchDirectory.resolve("patch").resolve(safeToken(requireText(executionId, "executionId 不能为空")));
        result = result.normalize();
        ensureChild(batchDirectory, result);
        return result;
    }

    private static JSONObject parsePayload(String payloadJson) {
        JSONObject payload = JSON.parseObject(payloadJson);
        if (payload == null) {
            throw new IllegalArgumentException("任务payload不能为空");
        }
        return payload;
    }

    private static JSONObject requireBatchMeta(JSONObject payload) {
        JSONObject batchMeta = payload.getJSONObject("batch_meta");
        if (batchMeta == null) {
            throw new IllegalArgumentException("批量任务缺少batch_meta");
        }
        return batchMeta;
    }

    private static String normalizeExecutionType(String executionType) {
        String value = requireText(executionType, "execution_type 不能为空").toUpperCase(Locale.ROOT);
        if (!EXECUTION_TYPE_BATCH.equals(value) && !EXECUTION_TYPE_PATCH.equals(value)) {
            throw new IllegalArgumentException("execution_type仅支持BATCH或PATCH: " + executionType);
        }
        return value;
    }

    private static String safeToken(String value) {
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("目录标识包含非法字符: " + value);
        }
        return value;
    }

    private static void ensureChild(Path parent, Path child) {
        if (!child.startsWith(parent)) {
            throw new IllegalArgumentException("非法暂存目录: " + child);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            Path[] paths = stream.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static String requireText(String value, String message) {
        String result = trimToNull(value);
        if (result == null) {
            throw new IllegalArgumentException(message);
        }
        return result;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
