package com.zcyh.mr.springboot.batch;

import com.zcyh.mr.springboot.batch.model.JobStatus;

/**
 * 异步任务状态机。
 */
final class AsyncTaskStateMachine {

    enum Event {
        START,
        SUCCEED,
        FAIL,
        PARTIAL_FAIL,
        CANCEL,
        REOPEN
    }

    private AsyncTaskStateMachine() {
    }

    static JobStatus transition(JobStatus current, Event event) {
        if (current == null) {
            throw new IllegalStateException("异步任务当前状态不能为空");
        }
        if (event == null) {
            throw new IllegalArgumentException("异步任务状态事件不能为空");
        }
        switch (event) {
            case START:
                return requireCurrent(current, JobStatus.PENDING, JobStatus.RUNNING, event);
            case SUCCEED:
                return requireCurrent(current, JobStatus.RUNNING, JobStatus.SUCCESS, event);
            case FAIL:
                if (current == JobStatus.PENDING || current == JobStatus.RUNNING) {
                    return JobStatus.FAILED;
                }
                break;
            case PARTIAL_FAIL:
                return requireCurrent(current, JobStatus.RUNNING, JobStatus.PARTIAL_FAILED, event);
            case CANCEL:
                if (current == JobStatus.PENDING || current == JobStatus.RUNNING) {
                    return JobStatus.CANCELLED;
                }
                break;
            case REOPEN:
                if (current.isTerminal()) {
                    return JobStatus.PENDING;
                }
                break;
            default:
                break;
        }
        throw illegalTransition(current, event);
    }

    private static JobStatus requireCurrent(
            JobStatus current,
            JobStatus expected,
            JobStatus target,
            Event event) {
        if (current != expected) {
            throw illegalTransition(current, event);
        }
        return target;
    }

    private static IllegalStateException illegalTransition(JobStatus current, Event event) {
        return new IllegalStateException("非法异步任务状态迁移: current=" + current + ", event=" + event);
    }
}
