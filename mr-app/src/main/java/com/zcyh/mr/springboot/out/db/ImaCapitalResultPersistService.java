package com.zcyh.mr.springboot.out.db;

import com.zcyh.mr.springboot.support.DorisCsvStreamLoadBuffer;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import com.zcyh.mr.springboot.support.ResultPersistTime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.model.ImccResult;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * IMA 最终资本结果落库服务。
 * 将 ImaCapitalResult 写入 TB_OUT_IMA_CAPITAL_RESULT（Doris 结果表）。
 *
 * <p>当前将核心指标（IMCC / SES / amber 系数 / 总资本）写入结果表，
 * 资本计算明细以 JSON 形式存入 DETAIL_JSON 字段。
 */
@Service
public class ImaCapitalResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(ImaCapitalResultPersistService.class);

    private static final String TARGET_TABLE = "TB_OUT_IMA_CAPITAL_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,RULE_ID,GROUP_TYPE,GROUP_VALUE,GROUP_ORDER,IMCC,SES,AMBER_SURCHARGE_RATIO,ACR_TOTAL,"
                    + "IMCC_ALL,IMCC_IR,IMCC_CS,IMCC_FX,IMCC_EQ,IMCC_COMM,DETAIL_JSON,CREATED_AT,UPDATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public ImaCapitalResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void deleteByBatchAndDataDate(String batchId, String dataDate) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("IMA 资本结果清理缺少 BATCH_ID");
        }
        if (dataDate == null || dataDate.trim().isEmpty()) {
            throw new IllegalArgumentException("IMA 资本结果清理缺少 DATA_DATE");
        }
        int deleted = jdbcTemplate.update("DELETE FROM " + TARGET_TABLE + " WHERE BATCH_ID=? AND DATA_DATE=?",
                batchId, dataDate);
        log.info("清理 IMA 资本历史结果: batchId={}, dataDate={}, deleted={}", batchId, dataDate, deleted);
    }

    public void deleteByBatchDataDateAndRuleIds(String batchId, String dataDate, List<String> ruleIds) {
        int deleted = RuleScopedDeleteSupport.deleteByRuleIds(
                jdbcTemplate, TARGET_TABLE, batchId, dataDate, ruleIds);
        log.info("按规则清理 IMA 资本历史结果: batchId={}, dataDate={}, ruleIds={}, deleted={}",
                batchId, dataDate, ruleIds, deleted);
    }

    /**
     * 写入 IMA 最终资本结果。
     *
     * @param result ImaCapitalCalculator 输出
     */
    public void persist(ImaCapitalResult result) {
        persist(result == null ? Collections.<ImaCapitalResult>emptyList() : Collections.singletonList(result));
    }

    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(List<ImaCapitalResult> results) {
        if (results == null || results.isEmpty()) {
            log.warn("IMA 资本结果为空，跳过落库");
            return;
        }
        String batchId = requireBatchId(results.get(0));
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "ima_capital_" + batchId,
                100);
        for (ImaCapitalResult result : results) {
            String resultBatchId = requireBatchId(result);
            if (!batchId.equals(resultBatchId)) {
                throw new IllegalArgumentException("IMA 资本结果批次不一致: " + resultBatchId);
            }
            ImccResult imccResult = result.getImccResult();
            String detailJson = JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
            buffer.appendRow(
                    resultBatchId,
                    result.getDataDate(),
                    result.getRuleId(),
                    result.getGroupType(),
                    result.getGroupValue(),
                    result.getGroupOrder(),
                    DorisCsvStreamLoadBuffer.decimalText(result.getImccResult() != null ? result.getImccResult().getImcc() : null),
                    DorisCsvStreamLoadBuffer.decimalText(result.getSesResult() != null ? result.getSesResult().getSes() : null),
                    DorisCsvStreamLoadBuffer.decimalText(result.getAmberSurchargeRatio()),
                    DorisCsvStreamLoadBuffer.decimalText(result.getAcrTotal()),
                    DorisCsvStreamLoadBuffer.decimalText(imccResult == null ? null : imccResult.getImccConstrained()),
                    DorisCsvStreamLoadBuffer.decimalText(riskClassImcc(imccResult, "IR")),
                    DorisCsvStreamLoadBuffer.decimalText(riskClassImcc(imccResult, "CS")),
                    DorisCsvStreamLoadBuffer.decimalText(riskClassImcc(imccResult, "FX")),
                    DorisCsvStreamLoadBuffer.decimalText(riskClassImcc(imccResult, "EQ")),
                    DorisCsvStreamLoadBuffer.decimalText(riskClassImcc(imccResult, "COMM")),
                    detailJson,
                    now,
                    now);
        }
        buffer.flush();

        log.info("IMA 资本结果落库完成: batchId={}, rows={}", batchId, results.size());
    }

    private static java.math.BigDecimal riskClassImcc(ImccResult result, String riskClass) {
        if (result == null || result.getRiskClassEs() == null) {
            return null;
        }
        return result.getRiskClassEs().get(riskClass);
    }

    private static String requireBatchId(ImaCapitalResult result) {
        if (result == null || result.getBatchId() == null || result.getBatchId().trim().isEmpty()) {
            throw new IllegalArgumentException("IMA 资本结果缺少 BATCH_ID");
        }
        return result.getBatchId();
    }
}
