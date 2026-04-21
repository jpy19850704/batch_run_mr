package com.zcyh.mr.springboot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.zcyh.mr.frtbima.model.ImaCapitalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IMA 最终资本结果落库服务。
 * 将 ImaCapitalResult 写入 TB_OUT_IMA_CAPITAL_RESULT（Doris，待建表）。
 *
 * <p>当前将核心指标（IMCC / SES / amber 系数 / 总资本）写入结果表，
 * 完整中间值以 JSON 形式存入 RESULT_JSON 字段。
 */
@Service
public class ImaCapitalResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(ImaCapitalResultPersistService.class);

    private static final String TARGET_TABLE = "TB_OUT_IMA_CAPITAL_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "BATCH_ID,DATA_DATE,IMCC,SES,AMBER_SURCHARGE_RATIO,ACR_TOTAL,RESULT_JSON,CREATED_AT,UPDATED_AT";

    private final JdbcTemplate jdbcTemplate;
    private final DorisStreamLoadService dorisStreamLoadService;

    public ImaCapitalResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
            DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    /**
     * 写入 IMA 最终资本结果。
     *
     * @param result ImaCapitalCalculator 输出
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persist(ImaCapitalResult result) {
        if (result == null) {
            log.warn("IMA 资本结果为空，跳过落库");
            return;
        }
        String now = ResultPersistTime.nowText();
        String resultJson = JSON.toJSONString(result, JSONWriter.Feature.WriteBigDecimalAsPlain);
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "ima_capital_" + result.getBatchId(),
                1);
        buffer.appendRow(
                result.getBatchId(),
                result.getDataDate(),
                DorisCsvStreamLoadBuffer.decimalText(result.getImccResult() != null ? result.getImccResult().getImcc() : null),
                DorisCsvStreamLoadBuffer.decimalText(result.getSesResult() != null ? result.getSesResult().getSes() : null),
                DorisCsvStreamLoadBuffer.decimalText(result.getAmberSurchargeRatio()),
                DorisCsvStreamLoadBuffer.decimalText(result.getAcrTotal()),
                resultJson,
                now,
                now);
        buffer.flush();

        log.info("IMA 资本结果落库完成: batchId={}, acrTotal={}",
                result.getBatchId(), result.getAcrTotal());
    }
}
