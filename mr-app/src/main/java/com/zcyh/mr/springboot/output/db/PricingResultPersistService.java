package com.zcyh.mr.springboot.output.db;

import static com.zcyh.mr.springboot.output.db.CalcResultPersistSupport.trimToNull;

import com.zcyh.mr.springboot.measurement.valuation.ValuationExecutionAdapter;
import com.zcyh.mr.springboot.execution.MeasurementExecutionResult;
import com.zcyh.mr.springboot.output.file.FileCsvRowWriterFactory;
import com.zcyh.mr.springboot.support.DorisStreamLoadService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MR 异步结果落库服务。
 * 负责将成功任务的结果拆分写入交易结果、情景结果、FRTB 敏感性和 DRC 明细表。
 */
@Service
public class PricingResultPersistService {
    private static final Logger log = LoggerFactory.getLogger(PricingResultPersistService.class);
    private final JdbcTemplate jdbcTemplate;
    private final CalcPersistContextFactory contextFactory;
    private final TradeResultWriter tradeResultWriter;
    private final MarketDataResultWriter marketDataResultWriter;
    private final FrtbSensitivityDetailWriter frtbSensitivityDetailWriter;
    private final DrcDetailWriter drcDetailWriter;
    private final TradeScenarioPnlWriter tradeScenarioPnlWriter;
    private final TradeScenarioVarResultWriter tradeScenarioVarResultWriter;
    private final ImaScenarioPnlWriter imaScenarioPnlWriter;
    private final DorisStreamLoadService dorisStreamLoadService;
    private final Object schemaVerifyLock = new Object();
    private volatile boolean requiredSchemaVerified = false;

    public PricingResultPersistService(@Qualifier("engineResultDbJdbcTemplate") JdbcTemplate jdbcTemplate,
                                       CalcPersistContextFactory contextFactory,
                                       TradeResultWriter tradeResultWriter,
                                       MarketDataResultWriter marketDataResultWriter,
                                       FrtbSensitivityDetailWriter frtbSensitivityDetailWriter,
                                       DrcDetailWriter drcDetailWriter,
                                       TradeScenarioPnlWriter tradeScenarioPnlWriter,
                                       TradeScenarioVarResultWriter tradeScenarioVarResultWriter,
                                       ImaScenarioPnlWriter imaScenarioPnlWriter,
                                       DorisStreamLoadService dorisStreamLoadService) {
        this.jdbcTemplate = jdbcTemplate;
        this.contextFactory = contextFactory;
        this.tradeResultWriter = tradeResultWriter;
        this.marketDataResultWriter = marketDataResultWriter;
        this.frtbSensitivityDetailWriter = frtbSensitivityDetailWriter;
        this.drcDetailWriter = drcDetailWriter;
        this.tradeScenarioPnlWriter = tradeScenarioPnlWriter;
        this.tradeScenarioVarResultWriter = tradeScenarioVarResultWriter;
        this.imaScenarioPnlWriter = imaScenarioPnlWriter;
        this.dorisStreamLoadService = dorisStreamLoadService;
    }

    /**
     * 系统启动后一次性校验结果表结构，运行期不重复触发表字段探测。
     */
    @PostConstruct
    public void verifyRequiredSchemaOnStartup() {
        ensureRequiredOutputSchema();
    }

    /**
     * 将单个计量分片转换为结果 CSV。
     * 写入失败由异步任务终态处理回写为失败状态。
     */
    public void writeJobResultCsv(Path directory,
                                  String requestId,
                                  String jobId,
                                  String payloadJson,
                                  MeasurementExecutionResult runResult) {
        if (runResult == null || !runResult.isSuccess()) {
            return;
        }
        if (!ValuationExecutionAdapter.CODE.equalsIgnoreCase(trimToNull(runResult.getEngineCode()))) {
            return;
        }
        boolean varTableExists = true;

        CalcPersistContext context = contextFactory.build(requestId, jobId, payloadJson, runResult);
        if (context == null) {
            return;
        }

        long totalStart = System.nanoTime();
        try (FileCsvRowWriterFactory writerFactory =
                     new FileCsvRowWriterFactory(directory, dorisStreamLoadService)) {
            for (StagedCsvTable table : stagedCsvTables()) {
                writerFactory.create(table.tableName, table.columns, table.tableName, 0);
            }
            tradeResultWriter.write(context, writerFactory);
            drcDetailWriter.write(context, writerFactory);
            frtbSensitivityDetailWriter.write(context, writerFactory);
            tradeScenarioPnlWriter.write(
                    context, context.scenarioResults, context.baseTradeIndex, varTableExists, writerFactory);
            imaScenarioPnlWriter.writeModellableRows(
                    context, context.imaModellableScenarioResults, writerFactory);
        } catch (IOException ex) {
            throw new IllegalStateException("关闭分片结果CSV失败: jobId=" + jobId, ex);
        }
        log.info("分片结果CSV生成完成: batchId={}, jobId={}, elapsedMs={}",
                context.batchId, context.jobId, elapsedMs(totalStart));
    }

    public List<StagedCsvTable> stagedCsvTables() {
        List<StagedCsvTable> tables = new ArrayList<StagedCsvTable>();
        tables.add(table(tradeResultWriter.tableName(), tradeResultWriter.writeColumns()));
        tables.add(table(tradeScenarioPnlWriter.tableName(), tradeScenarioPnlWriter.writeColumns()));
        tables.add(table(tradeScenarioVarResultWriter.tableName(), tradeScenarioVarResultWriter.writeColumns()));
        tables.add(table(frtbSensitivityDetailWriter.tableName(), frtbSensitivityDetailWriter.writeColumns()));
        tables.add(table(drcDetailWriter.tableName(), drcDetailWriter.writeColumns()));
        tables.add(new StagedCsvTable(
                imaScenarioPnlWriter.modellableTableName(), imaScenarioPnlWriter.modellableColumns()));
        tables.add(new StagedCsvTable(
                imaScenarioPnlWriter.nmrfTableName(), imaScenarioPnlWriter.nmrfColumns()));
        return Collections.unmodifiableList(tables);
    }

    private static StagedCsvTable table(String tableName, List<String> columns) {
        return new StagedCsvTable(tableName, String.join(",", columns));
    }

    public static final class StagedCsvTable {
        private final String tableName;
        private final String columns;

        private StagedCsvTable(String tableName, String columns) {
            this.tableName = tableName;
            this.columns = columns;
        }

        public String getTableName() {
            return tableName;
        }

        public String getColumns() {
            return columns;
        }

        public String getFileName() {
            return FileCsvRowWriterFactory.fileName(tableName);
        }
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0d;
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
            verifyTableColumns(tradeScenarioPnlWriter.tableName(), tradeScenarioPnlWriter.writeColumns());
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
