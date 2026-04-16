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

import java.util.Collections;

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

    private static final String INSERT_SQL =
            "INSERT INTO TB_OUT_IMA_CAPITAL_RESULT ("
            + "BATCH_ID, DATA_DATE, "
            + "IMCC, SES, AMBER_SURCHARGE_RATIO, ACR_TOTAL, "
            + "RESULT_JSON, CREATED_AT, UPDATED_AT"
            + ") VALUES (?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbcTemplate;

    public ImaCapitalResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

        Object[] row = new Object[]{
                result.getBatchId(),
                result.getDataDate(),
                result.getImccResult() != null ? result.getImccResult().getImcc() : null,
                result.getSesResult() != null ? result.getSesResult().getSes() : null,
                result.getAmberSurchargeRatio(),
                result.getAcrTotal(),
                resultJson,
                now, now
        };
        jdbcTemplate.batchUpdate(INSERT_SQL, Collections.singletonList(row));

        log.info("IMA 资本结果落库完成: batchId={}, acrTotal={}",
                result.getBatchId(), result.getAcrTotal());
    }
}
