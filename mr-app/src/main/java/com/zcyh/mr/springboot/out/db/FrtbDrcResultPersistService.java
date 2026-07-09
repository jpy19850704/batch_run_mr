package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.toBigDecimal;
import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.trimToNull;

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
 * 统一写入 TB_OUT_TRADE_DRC_RESULT，按 CAPITAL_TYPE + AGG_LEVEL 区分结果语义。
 */
@Service
public class FrtbDrcResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(FrtbDrcResultPersistService.class);
    private static final int MAX_INVALID_ROW_LOG = 10;
    private static final int DEFAULT_BATCH_SIZE = 5000;

    private static final String FLAG_DRC_VALUE = "DRC_VALUE";
    private static final String FLAG_DECOMP_LEGAL_ENTITY = "DECOMP_LEGALENTITY";
    private static final String CAPITAL_TYPE_NONADDITIVE = "NONADDITIVE";
    private static final String CAPITAL_TYPE_ADDITIVE = "ADDITIVE";
    private static final String TARGET_TABLE = "TB_OUT_TRADE_DRC_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "REQUEST_ID,JOB_ID,BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,CAPITAL_TYPE,AGG_LEVEL,DRC_TYPE,DRC_BUCKET,LEGAL_ENTITY,DRC_VALUE,CREATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public FrtbDrcResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
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
     * DRC_VALUE 写为 NONADDITIVE，DECOMP_LEGALENTITY 写为 ADDITIVE，聚合层级由核心模块输出。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(String requestId, String jobId, String batchId, String dataDate, String ruleId, JSONObject drcResult) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        String safeRuleId = trimToNull(ruleId);
        if (safeBatchId == null) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        if (safeDataDate == null) {
            throw new IllegalArgumentException("dataDate 不能为空");
        }
        if (safeRuleId == null) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        if (drcResult == null) {
            throw new IllegalArgumentException("drcResult 不能为空");
        }

        List<ResultRow> rows = new ArrayList<ResultRow>();
        appendModuleRows(
                rows,
                drcResult.getJSONArray(FLAG_DRC_VALUE),
                CAPITAL_TYPE_NONADDITIVE,
                "DRC_VALUE",
                safeBatchId, safeDataDate, safeRuleId);
        appendModuleRows(
                rows,
                drcResult.getJSONArray(FLAG_DECOMP_LEGAL_ENTITY),
                CAPITAL_TYPE_ADDITIVE,
                "CONTRIBUTION",
                safeBatchId, safeDataDate, safeRuleId);

        if (rows.isEmpty()) {
            log.warn("DRC 结果为空，跳过落库: batchId={}, dataDate={}", safeBatchId, safeDataDate);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "frtb_drc_" + safeBatchId + "_" + safeDataDate,
                DEFAULT_BATCH_SIZE);
        for (ResultRow row : rows) {
            buffer.appendRow(
                    trimToNull(requestId),
                    trimToNull(jobId),
                    row.batchId,
                    row.dataDate,
                    row.ruleId,
                    row.groupType,
                    row.groupValue,
                    row.capitalType,
                    row.aggLevel,
                    row.drcType,
                    row.drcBucket,
                    row.legalEntity,
                    DorisCsvStreamLoadBuffer.decimalText(row.drcValue),
                    now
            );
        }
        buffer.flush();
        log.info("DRC 汇总结果落库完成: batchId={}, dataDate={}, rows={}", safeBatchId, safeDataDate, rows.size());
    }

    private static void appendModuleRows(List<ResultRow> output,
                                         JSONArray moduleRows,
                                         String capitalType,
                                         String valueField,
                                         String batchId,
                                         String dataDate,
                                         String ruleId) {
        if (moduleRows == null || moduleRows.isEmpty()) {
            return;
        }
        int invalidRowCount = 0;

        for (int i = 0; i < moduleRows.size(); i++) {
            JSONObject row = moduleRows.getJSONObject(i);
            if (row == null) {
                invalidRowCount++;
                logInvalidRow(batchId, dataDate, capitalType, i, "row_is_null", null, invalidRowCount);
                continue;
            }
            String drcType = trimToNull(row.getString("DRC_TYPE"));
            String legalEntity = trimToNull(row.getString("LEGAL_ENTITY"));
            String drcBucket = trimToNull(row.getString("DRC_BUCKET"));
            String aggLevel = trimToNull(row.getString("AGG_LEVEL"));
            String rowRuleId = trimToNull(row.getString("RULE_ID"));
            String groupType = trimToNull(row.getString("GROUP_TYPE"));
            String groupValue = trimToNull(row.getString("GROUP_VALUE"));
            if (aggLevel == null) {
                aggLevel = "LEGAL_ENTITY";
            }
            BigDecimal value = toBigDecimal(row.get(valueField));
            String missingFields = buildMissingFields(rowRuleId, groupType, groupValue, drcType, legalEntity, drcBucket, aggLevel, value);
            if (missingFields != null) {
                invalidRowCount++;
                logInvalidRow(batchId, dataDate, capitalType, i, missingFields, row, invalidRowCount);
                continue;
            }

            output.add(ResultRow.of(
                    batchId, dataDate,
                    rowRuleId, groupType, groupValue,
                    capitalType, aggLevel,
                    drcType, drcBucket, legalEntity, value));
        }

        if (invalidRowCount > 0) {
            log.error("DRC 结果存在无效行: batchId={}, dataDate={}, capitalType={}, invalidRows={}, loggedRows={}",
                    batchId, dataDate, capitalType, invalidRowCount, Math.min(invalidRowCount, MAX_INVALID_ROW_LOG));
            throw new IllegalStateException("DRC 结果存在无效行，已拒绝落库: capitalType=" + capitalType + ", invalidRows=" + invalidRowCount);
        }
    }

    private static String buildMissingFields(String ruleId,
                                             String groupType,
                                             String groupValue,
                                             String drcType,
                                             String legalEntity,
                                             String drcBucket,
                                             String aggLevel,
                                             BigDecimal value) {
        List<String> missing = new ArrayList<String>();
        if (ruleId == null) {
            missing.add("RULE_ID");
        }
        if (groupType == null) {
            missing.add("GROUP_TYPE");
        }
        if (groupValue == null) {
            missing.add("GROUP_VALUE");
        }
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
                                      String capitalType,
                                      int rowIndex,
                                      String reason,
                                      JSONObject row,
                                      int invalidRowCount) {
        if (invalidRowCount <= MAX_INVALID_ROW_LOG) {
            log.error("DRC 结果行无效: batchId={}, dataDate={}, capitalType={}, rowIndex={}, reason={}, row={}",
                    batchId, dataDate, capitalType, rowIndex, reason, row);
        }
    }

    /**
     * 单表输出行结构。
     */
    private static class ResultRow {
        String batchId;
        String dataDate;
        String ruleId;
        String groupType;
        String groupValue;
        String capitalType;
        String aggLevel;
        String drcType;
        String drcBucket;
        String legalEntity;
        BigDecimal drcValue;

        static ResultRow of(String batchId,
                            String dataDate,
                            String ruleId,
                            String groupType,
                            String groupValue,
                            String capitalType,
                            String aggLevel,
                            String drcType,
                            String drcBucket,
                            String legalEntity,
            BigDecimal drcValue) {
            ResultRow row = new ResultRow();
            row.batchId = batchId;
            row.dataDate = dataDate;
            row.ruleId = ruleId;
            row.groupType = groupType;
            row.groupValue = groupValue;
            row.capitalType = capitalType;
            row.aggLevel = aggLevel;
            row.drcType = drcType;
            row.drcBucket = drcBucket;
            row.legalEntity = legalEntity;
            row.drcValue = drcValue;
            return row;
        }
    }
}
