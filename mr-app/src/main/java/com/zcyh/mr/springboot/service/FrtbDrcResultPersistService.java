package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * FRTB DRC 结果落库服务。
 * 统一写入 TB_OUT_TRADE_DRC_RESULT，按 DECOMP_FLAG + AGG_LEVEL 区分结果语义。
 */
@Service
public class FrtbDrcResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(FrtbDrcResultPersistService.class);
    private static final int MAX_INVALID_ROW_LOG = 10;
    private static final int DEFAULT_BATCH_SIZE = 500;

    private static final String FLAG_DRC_VALUE = "DRC_VALUE";
    private static final String FLAG_DECOMP_LEGAL_ENTITY = "DECOMP_LEGALENTITY";

    private static final String INSERT_SQL =
            "INSERT INTO TB_OUT_TRADE_DRC_RESULT ("
                    + "REQUEST_ID, JOB_ID, BATCH_ID, DATA_DATE, "
                    + "DECOMP_FLAG, AGG_LEVEL, DRC_TYPE, DRC_BUCKET, LEGAL_ENTITY, DRC_VALUE, CREATED_AT"
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbcTemplate;

    public FrtbDrcResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 删除指定批次与估值日的历史 DRC 汇总结果。
     */
    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM TB_OUT_TRADE_DRC_RESULT WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, dataDate);
        if (deleted > 0) {
            log.info("清理 DRC 汇总历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
        }
    }

    /**
     * 将 DRC 计量结果写入单表。
     * 输入只使用 DRC_VALUE 与 DECOMP_LEGALENTITY 两类模块，聚合层级由核心模块输出。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(String requestId, String jobId, String batchId, String dataDate, JSONObject drcResult) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        if (drcResult == null) {
            throw new IllegalArgumentException("drcResult 不能为空");
        }

        deleteByBatchAndDataDate(safeBatchId, safeDataDate);

        List<ResultRow> rows = new ArrayList<ResultRow>();
        appendModuleRows(
                rows,
                drcResult.getJSONArray(FLAG_DRC_VALUE),
                FLAG_DRC_VALUE,
                "DRC_VALUE",
                safeBatchId, safeDataDate);
        appendModuleRows(
                rows,
                drcResult.getJSONArray(FLAG_DECOMP_LEGAL_ENTITY),
                FLAG_DECOMP_LEGAL_ENTITY,
                "CONTRIBUTION",
                safeBatchId, safeDataDate);

        if (rows.isEmpty()) {
            log.warn("DRC 结果为空，跳过落库: batchId={}, dataDate={}", safeBatchId, safeDataDate);
            return;
        }

        String now = ResultPersistTime.nowText();
        List<Object[]> batchArgs = new ArrayList<Object[]>();
        for (ResultRow row : rows) {
            batchArgs.add(new Object[]{
                    trimToNull(requestId),
                    trimToNull(jobId),
                    row.batchId,
                    row.dataDate,
                    row.decompFlag,
                    row.aggLevel,
                    row.drcType,
                    row.drcBucket,
                    row.legalEntity,
                    row.drcValue,
                    now
            });
            if (batchArgs.size() >= DEFAULT_BATCH_SIZE) {
                jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
                batchArgs.clear();
            }
        }
        if (!batchArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs);
        }
        log.info("DRC 汇总结果落库完成: batchId={}, dataDate={}, rows={}", safeBatchId, safeDataDate, rows.size());
    }

    private static void appendModuleRows(List<ResultRow> output,
                                         JSONArray moduleRows,
                                         String decompFlag,
                                         String valueField,
                                         String batchId,
                                         String dataDate) {
        if (moduleRows == null || moduleRows.isEmpty()) {
            return;
        }
        int invalidRowCount = 0;

        for (int i = 0; i < moduleRows.size(); i++) {
            JSONObject row = moduleRows.getJSONObject(i);
            if (row == null) {
                invalidRowCount++;
                logInvalidRow(batchId, dataDate, decompFlag, i, "row_is_null", null, invalidRowCount);
                continue;
            }
            String drcType = trimToNull(row.getString("DRC_TYPE"));
            String legalEntity = trimToNull(row.getString("LEGAL_ENTITY"));
            String drcBucket = trimToNull(row.getString("DRC_BUCKET"));
            String aggLevel = trimToNull(row.getString("AGG_LEVEL"));
            if (aggLevel == null) {
                aggLevel = "LEGAL_ENTITY";
            }
            BigDecimal value = toBigDecimal(row.get(valueField));
            String missingFields = buildMissingFields(drcType, legalEntity, drcBucket, aggLevel, value);
            if (missingFields != null) {
                invalidRowCount++;
                logInvalidRow(batchId, dataDate, decompFlag, i, missingFields, row, invalidRowCount);
                continue;
            }

            output.add(ResultRow.of(
                    batchId, dataDate,
                    decompFlag, aggLevel,
                    drcType, drcBucket, legalEntity, value));
        }

        if (invalidRowCount > 0) {
            log.error("DRC 结果存在无效行: batchId={}, dataDate={}, decompFlag={}, invalidRows={}, loggedRows={}",
                    batchId, dataDate, decompFlag, invalidRowCount, Math.min(invalidRowCount, MAX_INVALID_ROW_LOG));
            throw new IllegalStateException("DRC 结果存在无效行，已拒绝落库: decompFlag=" + decompFlag + ", invalidRows=" + invalidRowCount);
        }
    }

    private static String buildMissingFields(String drcType,
                                             String legalEntity,
                                             String drcBucket,
                                             String aggLevel,
                                             BigDecimal value) {
        List<String> missing = new ArrayList<String>();
        if (drcType == null) {
            missing.add("DRC_TYPE");
        }
        if (legalEntity == null) {
            missing.add("LEGAL_ENTITY");
        }
        if (drcBucket == null) {
            missing.add("DRC_BUCKET");
        }
        if (aggLevel == null) {
            missing.add("AGG_LEVEL");
        }
        if (value == null) {
            missing.add("VALUE");
        }
        if (missing.isEmpty()) {
            return null;
        }
        return String.join(",", missing);
    }

    private static void logInvalidRow(String batchId,
                                      String dataDate,
                                      String decompFlag,
                                      int rowIndex,
                                      String reason,
                                      JSONObject row,
                                      int invalidRowCount) {
        if (invalidRowCount <= MAX_INVALID_ROW_LOG) {
            log.error("DRC 结果行无效: batchId={}, dataDate={}, decompFlag={}, rowIndex={}, reason={}, row={}",
                    batchId, dataDate, decompFlag, rowIndex, reason, row);
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 单表输出行结构。
     */
    private static class ResultRow {
        String batchId;
        String dataDate;
        String decompFlag;
        String aggLevel;
        String drcType;
        String drcBucket;
        String legalEntity;
        BigDecimal drcValue;

        static ResultRow of(String batchId,
                            String dataDate,
                            String decompFlag,
                            String aggLevel,
                            String drcType,
                            String drcBucket,
                            String legalEntity,
            BigDecimal drcValue) {
            ResultRow row = new ResultRow();
            row.batchId = batchId;
            row.dataDate = dataDate;
            row.decompFlag = decompFlag;
            row.aggLevel = aggLevel;
            row.drcType = drcType;
            row.drcBucket = drcBucket;
            row.legalEntity = legalEntity;
            row.drcValue = drcValue;
            return row;
        }
    }
}
