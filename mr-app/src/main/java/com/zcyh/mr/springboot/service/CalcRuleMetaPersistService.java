package com.zcyh.mr.springboot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 计算规则元数据持久化服务。
 * 在每次计算结果落库时，将完整的规则 JSON 同步写入 TB_OUT_CALC_RULE_META，
 * 支持从结果反查当时使用的 filterTree、build_order 等规则配置。
 */
@Service
public class CalcRuleMetaPersistService {
    private static final Logger log = LoggerFactory.getLogger(CalcRuleMetaPersistService.class);
    private static final String TARGET_TABLE = "TB_OUT_CALC_RULE_META";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,CALC_TYPE,RULE_ID,RULE_JSON,CREATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public CalcRuleMetaPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                      DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    /**
     * 删除指定批次与计算类型的规则元数据历史记录。
     */
    public void deleteByBatchAndCalcType(String batchId, String dataDate, String calcType) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM TB_OUT_CALC_RULE_META WHERE BATCH_ID=? AND DATA_DATE=? AND CALC_TYPE=?",
                batchId, dataDate, calcType);
        if (deleted > 0) {
            log.info("清理规则元数据: batchId={}, dataDate={}, calcType={}, deleted={}",
                    batchId, dataDate, calcType, deleted);
        }
    }

    /**
     * 持久化单条规则元数据。
     *
     * @param batchId  批次ID
     * @param dataDate 数据日期
     * @param calcType 计算类型（VAR / FRTB_SBA / IMA）
     * @param ruleId   规则ID
     * @param ruleJson 完整规则原始 JSON
     */
    public void persist(String batchId, String dataDate, String calcType, String ruleId, String ruleJson) {
        String safeBatchId = trimToNull(batchId);
        String safeDataDate = trimToNull(dataDate);
        String safeCalcType = trimToNull(calcType);
        String safeRuleId = trimToNull(ruleId);
        if (safeBatchId == null || safeDataDate == null || safeCalcType == null || safeRuleId == null) {
            log.warn("规则元数据参数不完整，跳过持久化: batchId={}, dataDate={}, calcType={}, ruleId={}",
                    batchId, dataDate, calcType, ruleId);
            return;
        }
        if (ruleJson == null || ruleJson.isBlank()) {
            log.warn("规则 JSON 为空，跳过持久化: batchId={}, ruleId={}", safeBatchId, safeRuleId);
            return;
        }

        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "calc_rule_meta_" + safeBatchId + "_" + safeCalcType + "_" + safeRuleId,
                100);
        buffer.appendRow(
                safeBatchId,
                safeDataDate,
                safeCalcType,
                safeRuleId,
                ruleJson,
                now
        );
        buffer.flush();
        log.info("规则元数据落库完成: batchId={}, dataDate={}, calcType={}, ruleId={}",
                safeBatchId, safeDataDate, safeCalcType, safeRuleId);
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }
}
