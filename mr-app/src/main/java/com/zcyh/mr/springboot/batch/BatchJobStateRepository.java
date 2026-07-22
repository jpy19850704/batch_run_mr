package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.runtime.ExecutionContext;
import com.zcyh.mr.springboot.batch.model.JobStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 批量任务状态仓储。
 */
@Repository
class BatchJobStateRepository {
    private static final RowMapper<BatchJobRow> BATCH_JOB_ROW_MAPPER = new RowMapper<BatchJobRow>() {
        @Override
        public BatchJobRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BatchJobRow row = new BatchJobRow();
            row.batchId = rs.getString("batch_id");
            row.requestId = rs.getString("request_id");
            row.engineCode = rs.getString("engine_code");
            row.traceId = rs.getString("trace_id");
            row.clientId = rs.getString("client_id");
            row.userId = rs.getString("user_id");
            row.userName = rs.getString("user_name");
            row.sourceSystem = rs.getString("source_system");
            row.opCode = rs.getString("op_code");
            Date dataDate = rs.getDate("data_date");
            row.dataDate = dataDate == null ? null : dataDate.toLocalDate();
            row.portfolio = rs.getString("portfolio");
            row.desk = rs.getString("desk");
            row.totalTrades = rs.getInt("total_trades");
            row.totalJobs = rs.getInt("total_jobs");
            row.weightBudget = rs.getInt("chunk_size");
            row.status = JobStatus.parse(rs.getString("status"));
            row.pendingJobs = rs.getInt("pending_jobs");
            row.runningJobs = rs.getInt("running_jobs");
            row.successJobs = rs.getInt("success_jobs");
            row.failedJobs = rs.getInt("failed_jobs");
            row.cancelledJobs = rs.getInt("cancelled_jobs");
            row.message = rs.getString("message");
            row.createdAt = rs.getLong("created_at");
            row.updatedAt = rs.getLong("updated_at");
            return row;
        }
    };

    private static final RowMapper<BatchItemRow> BATCH_ITEM_ROW_MAPPER = new RowMapper<BatchItemRow>() {
        @Override
        public BatchItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            BatchItemRow row = new BatchItemRow();
            row.seqNo = rs.getInt("seq_no");
            row.jobId = rs.getString("job_id");
            row.tradeCount = rs.getInt("trade_count");
            row.productMixJson = rs.getString("product_mix_json");
            String jobStatus = rs.getString("job_status");
            row.jobStatus = jobStatus == null ? null : JobStatus.parse(jobStatus);
            row.errorCode = rs.getString("error_code");
            row.errorMessage = rs.getString("error_message");
            return row;
        }
    };

    private final JdbcTemplate jdbcTemplate;

    BatchJobStateRepository(@Qualifier("engineDbJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void verifySchema() {
        jdbcTemplate.queryForList(
                "SELECT batch_id,request_id,engine_code,trace_id,client_id,user_id,user_name,source_system,op_code,data_date,portfolio,desk,total_trades,total_jobs,chunk_size,status,pending_jobs,running_jobs,success_jobs,failed_jobs,cancelled_jobs,message,created_at,updated_at "
                        + "FROM MR_ASYNC_BATCH_JOB WHERE 1=0");
        jdbcTemplate.queryForList(
                "SELECT id,batch_id,seq_no,job_id,trade_count,product_mix_json,created_at "
                        + "FROM MR_ASYNC_BATCH_ITEM WHERE 1=0");
    }

    int countActiveBatchItems(String batchId) {
        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM MR_ASYNC_BATCH_ITEM i "
                        + "LEFT JOIN MR_ASYNC_JOB j ON j.job_id=i.job_id "
                        + "WHERE i.batch_id=? AND (j.status IS NULL OR j.status IN ('PENDING','RUNNING'))",
                Integer.class,
                batchId);
        return active == null ? 0 : active;
    }

    List<String> findOtherActiveBatchIds(String batchId) {
        return jdbcTemplate.queryForList(
                "SELECT batch_id FROM MR_ASYNC_BATCH_JOB "
                        + "WHERE batch_id<>? AND status IN ('PENDING','RUNNING')",
                String.class,
                batchId);
    }

    List<BatchJobRow> findActiveBatchRows() {
        String sql = "SELECT batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, "
                + "source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, "
                + "status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, "
                + "created_at, updated_at FROM MR_ASYNC_BATCH_JOB "
                + "WHERE status IN ('PENDING','RUNNING') ORDER BY created_at";
        return jdbcTemplate.query(sql, BATCH_JOB_ROW_MAPPER);
    }

    void clearExistingBatchData(String batchId) {
        List<String> oldJobIds = jdbcTemplate.queryForList(
                "SELECT job_id FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=? ORDER BY seq_no",
                String.class,
                batchId);
        jdbcTemplate.update("DELETE FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=?", batchId);
        jdbcTemplate.update("DELETE FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?", batchId);
        if (oldJobIds == null) {
            return;
        }
        for (String jobId : oldJobIds) {
            if (trimToNull(jobId) != null) {
                jdbcTemplate.update("DELETE FROM MR_ASYNC_JOB WHERE job_id=?", jobId);
            }
        }
    }

    boolean batchExists(String batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?",
                Integer.class,
                batchId);
        return count != null && count > 0;
    }

    void insertBatchJob(
            String batchId,
            String requestId,
            String engineCode,
            String opCode,
            LocalDate dataDate,
            String portfolio,
            String desk,
            int totalTrades,
            int totalJobs,
            int weightBudget,
            long now,
            ExecutionContext context) {
        String sql = "INSERT INTO MR_ASYNC_BATCH_JOB (batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?)";
        jdbcTemplate.update(
                sql,
                batchId,
                requestId,
                engineCode,
                context == null ? null : trimToNull(context.getTraceId()),
                context == null ? null : trimToNull(context.getClientId()),
                context == null ? null : trimToNull(context.getUserId()),
                context == null ? null : trimToNull(context.getUserName()),
                context == null ? null : trimToNull(context.getSourceSystem()),
                opCode,
                Date.valueOf(dataDate),
                portfolio,
                desk,
                totalTrades,
                totalJobs,
                weightBudget,
                JobStatus.PENDING.name(),
                totalJobs,
                "批量任务创建完成",
                now,
                now);
    }

    void updateBatchDefinition(
            String batchId,
            String requestId,
            String engineCode,
            String opCode,
            LocalDate dataDate,
            String portfolio,
            String desk,
            int totalTrades,
            int totalJobs,
            int weightBudget,
            long now) {
        String sql = "UPDATE MR_ASYNC_BATCH_JOB "
                + "SET request_id=?, engine_code=?, op_code=?, data_date=?, portfolio=?, desk=?, "
                + "total_trades=?, total_jobs=?, chunk_size=?, pending_jobs=?, running_jobs=0, success_jobs=0, failed_jobs=0, cancelled_jobs=0, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(
                sql,
                requestId,
                engineCode,
                opCode,
                Date.valueOf(dataDate),
                portfolio,
                desk,
                totalTrades,
                totalJobs,
                weightBudget,
                totalJobs,
                "批量任务准备提交",
                now,
                batchId);
    }

    void insertBatchItem(
            String batchId,
            int seqNo,
            String jobId,
            int tradeCount,
            String productMixJson,
            long now) {
        String sql = "INSERT INTO MR_ASYNC_BATCH_ITEM "
                + "(batch_id, seq_no, job_id, trade_count, product_mix_json, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, batchId, seqNo, jobId, tradeCount, productMixJson, now);
    }

    void updateBatchProgress(
            String batchId,
            int totalJobs,
            int pendingJobs,
            int runningJobs,
            int successJobs,
            int failedJobs,
            int cancelledJobs,
            long updatedAt,
            String message) {
        String sql = "UPDATE MR_ASYNC_BATCH_JOB "
                + "SET total_jobs=?, pending_jobs=?, running_jobs=?, success_jobs=?, failed_jobs=?, cancelled_jobs=?, message=?, updated_at=? "
                + "WHERE batch_id=?";
        jdbcTemplate.update(
                sql,
                totalJobs,
                pendingJobs,
                runningJobs,
                successJobs,
                failedJobs,
                cancelledJobs,
                message,
                updatedAt,
                batchId);
    }

    boolean transitionBatchStatus(
            String batchId,
            JobStatus expectedStatus,
            JobStatus targetStatus,
            int pendingJobs,
            int runningJobs,
            int successJobs,
            int failedJobs,
            int cancelledJobs,
            long updatedAt,
            String message) {
        String sql = "UPDATE MR_ASYNC_BATCH_JOB "
                + "SET status=?, pending_jobs=?, running_jobs=?, success_jobs=?, failed_jobs=?, cancelled_jobs=?, message=?, updated_at=? "
                + "WHERE batch_id=? AND status=?";
        return jdbcTemplate.update(
                sql,
                targetStatus.name(),
                pendingJobs,
                runningJobs,
                successJobs,
                failedJobs,
                cancelledJobs,
                message,
                updatedAt,
                batchId,
                expectedStatus.name()) > 0;
    }

    BatchJobRow findBatchRow(String batchId) {
        String sql = "SELECT batch_id, request_id, engine_code, trace_id, client_id, user_id, user_name, source_system, op_code, data_date, portfolio, desk, total_trades, total_jobs, chunk_size, status, pending_jobs, running_jobs, success_jobs, failed_jobs, cancelled_jobs, message, created_at, updated_at "
                + "FROM MR_ASYNC_BATCH_JOB WHERE batch_id=?";
        List<BatchJobRow> rows = jdbcTemplate.query(sql, BATCH_JOB_ROW_MAPPER, batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<BatchItemRow> loadBatchItems(String batchId) {
        String sql = "SELECT i.seq_no, i.job_id, i.trade_count, i.product_mix_json, "
                + "j.status AS job_status, j.error_code, j.error_message "
                + "FROM MR_ASYNC_BATCH_ITEM i "
                + "LEFT JOIN MR_ASYNC_JOB j ON j.job_id=i.job_id "
                + "WHERE i.batch_id=? ORDER BY i.seq_no";
        return jdbcTemplate.query(sql, BATCH_ITEM_ROW_MAPPER, batchId);
    }

    int nextSeqNo(String batchId) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq_no) FROM MR_ASYNC_BATCH_ITEM WHERE batch_id=?",
                Integer.class,
                batchId);
        return maxSeq == null ? 1 : maxSeq + 1;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.isEmpty() ? null : value;
    }

    static final class BatchJobRow {
        String batchId;
        String requestId;
        String engineCode;
        String traceId;
        String clientId;
        String userId;
        String userName;
        String sourceSystem;
        String opCode;
        LocalDate dataDate;
        String portfolio;
        String desk;
        int totalTrades;
        int totalJobs;
        int weightBudget;
        JobStatus status;
        int pendingJobs;
        int runningJobs;
        int successJobs;
        int failedJobs;
        int cancelledJobs;
        String message;
        long createdAt;
        long updatedAt;
    }

    static final class BatchItemRow {
        int seqNo;
        String jobId;
        int tradeCount;
        String productMixJson;
        JobStatus jobStatus;
        String errorCode;
        String errorMessage;
    }
}
