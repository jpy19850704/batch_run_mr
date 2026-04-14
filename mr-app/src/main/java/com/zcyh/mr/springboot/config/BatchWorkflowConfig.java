package com.zcyh.mr.springboot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 批次工作流执行器配置。
 */
@Configuration
public class BatchWorkflowConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService batchRunWorkflowExecutor(
            @Value("${mr.batch.workflow-executor.core-size:2}") int coreSize,
            @Value("${mr.batch.workflow-executor.max-size:4}") int maxSize,
            @Value("${mr.batch.workflow-executor.queue-capacity:128}") int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(1, coreSize),
                Math.max(Math.max(1, coreSize), maxSize),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue< Runnable >(Math.max(1, queueCapacity)),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
