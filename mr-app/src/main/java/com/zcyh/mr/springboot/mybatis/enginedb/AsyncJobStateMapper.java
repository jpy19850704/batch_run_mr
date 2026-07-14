package com.zcyh.mr.springboot.mybatis.enginedb;

import com.zcyh.mr.springboot.service.AsyncJobEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 异步任务状态 Mapper。
 */
@Mapper
public interface AsyncJobStateMapper {

    @Select("SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at FROM MR_ASYNC_JOB WHERE 1=0")
    List<Map<String, Object>> verifyJobSchema();

    @Insert("INSERT INTO MR_ASYNC_JOB (job_id, request_id, engine_code, payload_json, status, created_at, updated_at, idempotency_key, trace_id, client_id, user_id, user_name, source_system, cancel_requested, owner_node) "
            + "VALUES (#{create.jobId}, #{create.requestId}, #{create.engineCode}, #{create.payloadJson}, #{create.status}, #{create.createdAt}, #{create.updatedAt}, #{create.idempotencyKey}, #{create.traceId}, #{create.clientId}, #{create.userId}, #{create.userName}, #{create.sourceSystem}, 0, #{nodeId})")
    int insertJob(@Param("create") AsyncJobEntity create, @Param("nodeId") String nodeId);

    @Insert("INSERT INTO MR_ASYNC_JOB (job_id, request_id, engine_code, payload_json, status, created_at, started_at, finished_at, elapsed_ms, success_flag, error_code, error_message, idempotency_key, trace_id, client_id, user_id, user_name, source_system, cancel_requested, owner_node, updated_at) "
            + "VALUES (#{create.jobId}, #{create.requestId}, #{create.engineCode}, #{create.payloadJson}, #{create.status}, #{create.createdAt}, #{create.startedAt}, #{create.finishedAt}, #{create.elapsedMs}, #{create.successFlag}, #{create.errorCode}, #{create.errorMessage}, #{create.idempotencyKey}, #{create.traceId}, #{create.clientId}, #{create.userId}, #{create.userName}, #{create.sourceSystem}, 0, #{nodeId}, #{create.updatedAt})")
    int insertFailedJob(@Param("create") AsyncJobEntity create, @Param("nodeId") String nodeId);

    @Select("SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at FROM MR_ASYNC_JOB WHERE job_id=#{jobId}")
    List<AsyncJobEntity> findByJobId(@Param("jobId") String jobId);

    @Select("SELECT job_id,request_id,engine_code,payload_json,status,created_at,started_at,finished_at,elapsed_ms,success_flag,error_code,error_message,idempotency_key,trace_id,client_id,user_id,user_name,source_system,cancel_requested,owner_node,updated_at FROM MR_ASYNC_JOB WHERE idempotency_key=#{idempotencyKey}")
    List<AsyncJobEntity> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT COUNT(1) FROM MR_ASYNC_JOB WHERE status=#{pendingStatus}")
    Integer countPendingJobs(@Param("pendingStatus") String pendingStatus);

    @Select("SELECT job_id FROM MR_ASYNC_JOB WHERE status=#{pendingStatus} AND cancel_requested=0 AND (owner_node=#{nodeId} OR owner_node IS NULL OR owner_node='' OR updated_at<=#{staleCutoff}) ORDER BY created_at ASC LIMIT #{claimLimit}")
    List<String> listPendingJobs(
            @Param("pendingStatus") String pendingStatus,
            @Param("nodeId") String nodeId,
            @Param("staleCutoff") long staleCutoff,
            @Param("claimLimit") int claimLimit
    );

    @Update("UPDATE MR_ASYNC_JOB SET owner_node=#{nodeId}, updated_at=#{now} WHERE job_id=#{jobId} AND status=#{pendingStatus} AND cancel_requested=0 AND (owner_node=#{nodeId} OR owner_node IS NULL OR owner_node='' OR updated_at<=#{staleCutoff})")
    int claimPendingJob(
            @Param("jobId") String jobId,
            @Param("staleCutoff") long staleCutoff,
            @Param("now") long now,
            @Param("nodeId") String nodeId,
            @Param("pendingStatus") String pendingStatus
    );

    @Update("UPDATE MR_ASYNC_JOB SET status=#{failedStatus}, finished_at=#{now}, elapsed_ms=CASE WHEN started_at IS NULL THEN 0 ELSE #{now} - started_at END, success_flag=0, error_code='OWNER_TIMEOUT', error_message='任务执行超时未完成，系统自动回收', updated_at=#{now} WHERE status=#{runningStatus} AND updated_at<=#{cutoff} AND (owner_node IS NULL OR owner_node<>#{nodeId})")
    int recoverStaleRunningJobs(
            @Param("now") long now,
            @Param("cutoff") long cutoff,
            @Param("nodeId") String nodeId,
            @Param("runningStatus") String runningStatus,
            @Param("failedStatus") String failedStatus
    );

    @Update("UPDATE MR_ASYNC_JOB SET updated_at=#{now}, owner_node=#{nodeId} WHERE job_id=#{jobId} AND status=#{runningStatus}")
    int touchRunningHeartbeat(
            @Param("jobId") String jobId,
            @Param("now") long now,
            @Param("nodeId") String nodeId,
            @Param("runningStatus") String runningStatus
    );

    @Update("UPDATE MR_ASYNC_JOB SET status=#{runningStatus}, started_at=#{start}, updated_at=#{start}, owner_node=#{nodeId} WHERE job_id=#{jobId} AND status=#{pendingStatus} AND cancel_requested=0")
    int markRunning(
            @Param("jobId") String jobId,
            @Param("start") long start,
            @Param("nodeId") String nodeId,
            @Param("runningStatus") String runningStatus,
            @Param("pendingStatus") String pendingStatus
    );

    @Update("UPDATE MR_ASYNC_JOB SET status=#{finalStatus}, finished_at=#{finish}, elapsed_ms=#{elapsed}, success_flag=#{successFlag}, error_code=#{errorCode}, error_message=#{errorMessage}, updated_at=#{finish} WHERE job_id=#{jobId} AND status=#{runningStatus}")
    int persistRunResult(
            @Param("jobId") String jobId,
            @Param("runningStatus") String runningStatus,
            @Param("finalStatus") String finalStatus,
            @Param("finish") long finish,
            @Param("elapsed") long elapsed,
            @Param("successFlag") int successFlag,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Update("UPDATE MR_ASYNC_JOB SET status=#{failedStatus}, finished_at=#{finish}, elapsed_ms=#{elapsed}, success_flag=0, error_code=#{errorCode}, error_message=#{errorMessage}, updated_at=#{finish} WHERE job_id=#{jobId} AND status=#{runningStatus}")
    int persistRunFailure(
            @Param("jobId") String jobId,
            @Param("runningStatus") String runningStatus,
            @Param("failedStatus") String failedStatus,
            @Param("finish") long finish,
            @Param("elapsed") long elapsed,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Update("UPDATE MR_ASYNC_JOB SET status=#{failedStatus}, finished_at=#{now}, elapsed_ms=0, success_flag=0, error_code='QUEUE_REJECTED', error_message=#{reason}, updated_at=#{now} WHERE job_id=#{jobId} AND status=#{pendingStatus}")
    int markRejected(
            @Param("jobId") String jobId,
            @Param("pendingStatus") String pendingStatus,
            @Param("failedStatus") String failedStatus,
            @Param("now") long now,
            @Param("reason") String reason
    );

    @Update("UPDATE MR_ASYNC_JOB SET cancel_requested=1, updated_at=#{now} WHERE job_id=#{jobId} AND status IN (#{pendingStatus}, #{runningStatus})")
    int markCancelRequested(
            @Param("jobId") String jobId,
            @Param("now") long now,
            @Param("pendingStatus") String pendingStatus,
            @Param("runningStatus") String runningStatus
    );

    @Update("UPDATE MR_ASYNC_JOB SET status=#{cancelledStatus}, finished_at=#{now}, elapsed_ms=#{elapsed}, success_flag=0, error_code='CANCELLED', error_message='任务已取消', updated_at=#{now} WHERE job_id=#{jobId} AND status=#{expectedStatus}")
    int markCancelled(
            @Param("jobId") String jobId,
            @Param("expectedStatus") String expectedStatus,
            @Param("cancelledStatus") String cancelledStatus,
            @Param("now") long now,
            @Param("elapsed") long elapsed
    );

    @Update("DELETE FROM MR_ASYNC_JOB WHERE status IN ('SUCCESS','FAILED','CANCELLED') AND finished_at IS NOT NULL AND finished_at < #{cutoff}")
    int deleteOldTerminalJobs(@Param("cutoff") long cutoff);

    @Update("UPDATE MR_ASYNC_JOB SET status=#{failedStatus}, success_flag=0, error_code=#{errorCode}, error_message=#{errorMessage}, updated_at=#{now} WHERE job_id=#{jobId} AND status=#{successStatus}")
    int markResultPersistFailed(
            @Param("jobId") String jobId,
            @Param("successStatus") String successStatus,
            @Param("failedStatus") String failedStatus,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("now") long now
    );
}
