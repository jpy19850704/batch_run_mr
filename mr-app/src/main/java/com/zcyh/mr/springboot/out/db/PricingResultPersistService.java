package com.zcyh.mr.springboot.out.db;

import static com.zcyh.mr.springboot.out.db.CalcResultPersistSupport.trimToNull;

import com.zcyh.mr.springboot.engine.MrCalcEngineAdapter;
import com.zcyh.mr.springboot.model.EngineRunResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * MR 异步结果落库服务。
 * 负责将成功任务的结果拆分写入交易结果、情景结果、FRTB 敏感性和 DRC 明细表。
 */
@Service
public class PricingResultPersistService {
    private final JdbcTemplate jdbcTemplate;
    private final CalcPersistContextFactory contextFactory;
    private final TradeResultWriter tradeResultWriter;
    private final MarketDataResultWriter marketDataResultWriter;
    private final FrtbSensitivityDetailWriter frtbSensitivityDetailWriter;
    private final DrcDetailWriter drcDetailWriter;
    private final TradeScenarioResultWriter tradeScenarioResultWriter;
    private final TradeScenarioVarResultWriter tradeScenarioVarResultWriter;
    private final ImaScenarioPnlWriter imaScenarioPnlWriter;
    private final Object schemaVerifyLock = new Object();
    private volatile boolean requiredSchemaVerified = false;

    public PricingResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       CalcPersistContextFactory contextFactory,
                                       TradeResultWriter tradeResultWriter,
                                       MarketDataResultWriter marketDataResultWriter,
                                       FrtbSensitivityDetailWriter frtbSensitivityDetailWriter,
                                       DrcDetailWriter drcDetailWriter,
                                       TradeScenarioResultWriter tradeScenarioResultWriter,
                                       TradeScenarioVarResultWriter tradeScenarioVarResultWriter,
                                       ImaScenarioPnlWriter imaScenarioPnlWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.contextFactory = contextFactory;
        this.tradeResultWriter = tradeResultWriter;
        this.marketDataResultWriter = marketDataResultWriter;
        this.frtbSensitivityDetailWriter = frtbSensitivityDetailWriter;
        this.drcDetailWriter = drcDetailWriter;
        this.tradeScenarioResultWriter = tradeScenarioResultWriter;
        this.tradeScenarioVarResultWriter = tradeScenarioVarResultWriter;
        this.imaScenarioPnlWriter = imaScenarioPnlWriter;
    }

    /**
     * 系统启动后一次性校验结果表结构，运行期不重复触发表字段探测。
     */
    @PostConstruct
    public void verifyRequiredSchemaOnStartup() {
        ensureRequiredOutputSchema();
    }

    /**
     * 按任务覆盖写入结果明细。
     * 写入失败由异步任务终态处理回写为失败状态。
     */
    @Transactional(transactionManager = "engineResultDbTransactionManager", rollbackFor = Exception.class)
    public void persistJobResult(String requestId, String jobId, String payloadJson, EngineRunResult runResult) {
        if (runResult == null || !runResult.isSuccess()) {
            return;
        }
        if (!MrCalcEngineAdapter.CODE.equalsIgnoreCase(trimToNull(runResult.getEngineCode()))) {
            return;
        }
        boolean varTableExists = true;

        CalcPersistContext context = contextFactory.build(requestId, jobId, payloadJson, runResult);
        if (context == null) {
            return;
        }

        tradeResultWriter.write(context);
        // 市场数据优先落库，避免后续敏感性/DRC异常导致 market_data 被一并跳过。
        marketDataResultWriter.write(context);
        drcDetailWriter.write(context);
        frtbSensitivityDetailWriter.write(context);
        tradeScenarioResultWriter.write(context, context.scenarioResults, context.baseTradeIndex, varTableExists);
        imaScenarioPnlWriter.writeModellableRows(context, context.imaModellableScenarioResults);
    }

    /**
     * 严格校验输出表列契约，缺列/改名时在写入前快速失败。
     */
    private void ensureRequiredOutputSchema() {
        if (requiredSchemaVerified) {
            return;
        }
        synchronized (schemaVerifyLock) {
            if (requiredSchemaVerified) {
                return;
            }
            verifyTableColumns(tradeResultWriter.tableName(), tradeResultWriter.writeColumns());
            verifyTableColumns(tradeScenarioResultWriter.tableName(), tradeScenarioResultWriter.writeColumns());
            verifyTableColumns(tradeScenarioVarResultWriter.tableName(), tradeScenarioVarResultWriter.writeColumns());
            verifyTableColumns(frtbSensitivityDetailWriter.tableName(), frtbSensitivityDetailWriter.writeColumns());
            verifyTableColumns(drcDetailWriter.tableName(), drcDetailWriter.writeColumns());
            verifyTableColumns(marketDataResultWriter.tableName(), marketDataResultWriter.writeColumns());
            requiredSchemaVerified = true;
        }
    }

    private void verifyTableColumns(String tableName, List<String> columns) {
        verifyTableColumns(tableName, String.join(", ", columns));
    }

    private void verifyTableColumns(String tableName, String columns) {
        String sql = "SELECT " + columns + " FROM " + tableName + " WHERE 1=0";
        try {
            jdbcTemplate.queryForList(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("输出结果表结构校验失败: " + tableName + "，原因=" + ex.getMessage(), ex);
        }
    }
}
