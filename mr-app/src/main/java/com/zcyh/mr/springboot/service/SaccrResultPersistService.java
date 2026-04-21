package com.zcyh.mr.springboot.service;

import com.zcyh.mr.saccr.model.SaccrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * SA-CCR 计算结果落库服务。
 *
 * <p>写入目标：engine_result_db.TB_OUT_SACCR_RESULT（Doris）。
 * <p>策略：先按 job_id 全量 DELETE，再批量 INSERT（500 行/批）。
 */
@Service
public class SaccrResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(SaccrResultPersistService.class);

    private static final int BATCH_SIZE = 5000;

    private static final String DELETE_SQL =
            "DELETE FROM TB_OUT_SACCR_RESULT WHERE JOB_ID = ?";
    private static final String TARGET_TABLE = "TB_OUT_SACCR_RESULT";
    private static final String STREAM_LOAD_COLUMNS =
            "JOB_ID,DATA_DATE,NETTING_SET_ID,COUNTERPARTY_ID,IS_MARGINED,IS_CLEARED,IS_QCCP,SUM_MTM,COLLATERAL_C,"
                    + "RC,ADDON_IR,ADDON_FX,ADDON_CREDIT,ADDON_EQUITY,ADDON_COMMODITY,ADDON_AGGREGATE,MULTIPLIER,PFE,EAD,"
                    + "RISK_WEIGHT,RWA_CCR,CAPITAL_CCR,CREATE_TIME";

    private final JdbcTemplate engineResultJdbc;
    private final DorisStreamLoadService dorisStreamLoadService;

    public SaccrResultPersistService(
            @Qualifier("engineResultDbJdbcTemplate") JdbcTemplate engineResultJdbc,
            DorisStreamLoadService dorisStreamLoadService) {
        this.engineResultJdbc = engineResultJdbc;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    /**
     * 持久化 SA-CCR 计算结果。
     *
     * @param jobId     任务 ID
     * @param dataDate  计算基准日期
     * @param results   SA-CCR 计算结果列表
     * @param nsMetaMap key = nettingSetId，value = NettingSetMeta（补充 isMargined 等字段）
     */
    public void persist(String jobId, LocalDate dataDate, List<SaccrResult> results,
                        java.util.Map<String, NettingSetMeta> nsMetaMap) {
        // 1. 先删
        engineResultJdbc.update(DELETE_SQL, jobId);
        log.info("SA-CCR 结果落库：删除 jobId={} 旧数据完成", jobId);

        // 2. 批量插入
        String now = ResultPersistTime.nowText();
        DorisCsvStreamLoadBuffer buffer = new DorisCsvStreamLoadBuffer(
                dorisStreamLoadService,
                TARGET_TABLE,
                STREAM_LOAD_COLUMNS,
                "saccr_" + jobId,
                BATCH_SIZE);

        for (SaccrResult r : results) {
            NettingSetMeta meta = nsMetaMap != null
                    ? nsMetaMap.getOrDefault(r.nettingSetId, new NettingSetMeta())
                    : new NettingSetMeta();

            buffer.appendRow(
                    jobId,
                    dataDate.toString(),
                    r.nettingSetId,
                    r.counterpartyId,
                    meta.isMargined ? 1 : 0,
                    meta.isCleared ? 1 : 0,
                    meta.isQccp ? 1 : 0,
                    r.sumMtm,
                    r.collateralC,
                    r.rc,
                    r.addonIr,
                    r.addonFx,
                    r.addonCredit,
                    r.addonEquity,
                    r.addonCommodity,
                    r.addonAggregate,
                    r.multiplier,
                    r.pfe,
                    r.ead,
                    r.riskWeight,
                    r.rwaCcr,
                    r.capitalCcr,
                    now
            );
        }
        buffer.flush();

        log.info("SA-CCR 结果落库完成：jobId={}，共 {} 条", jobId, results.size());
    }

    /**
     * 净额结算集合元数据，用于补充落库字段。
     */
    public static class NettingSetMeta {
        public boolean isMargined;
        public boolean isCleared;
        public boolean isQccp;
    }
}
