package com.zcyh.mr.springboot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcyh.mr.springboot.MrSpringBootApplication;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异步任务接口自动化测试。
 */
@SpringBootTest(classes = MrSpringBootApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:mrjobtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "mr.job.store.jdbc.init-schema=true",
        "mr.job.store.node-id=test-node",
        "mr.job.executor.core-size=1",
        "mr.job.executor.max-size=2",
        "mr.job.executor.queue-capacity=100",
        "mr.job.store.cleanup.every-submit=1",
        "mr.job.store.cleanup.retention-days=30",
        "mr.job.client.poll-after-ms=120"
})
public class MrJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void submitAndGetResult_shouldWork() throws Exception {
        String payload = "{"
                + "\"requestId\":\"it-job-001\","
                + "\"engineCode\":\"mr_calc\","
                + "\"idempotencyKey\":\"it-idem-001\","
                + "\"payload\":{}"
                + "}";

        MvcResult submitRes = mockMvc.perform(post("/api/v1/jobs/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode submitJson = objectMapper.readTree(submitRes.getResponse().getContentAsString());
        String jobId = submitJson.at("/data/jobId").asText();
        Assertions.assertFalse(jobId.isEmpty(), "jobId 不能为空");
        Assertions.assertEquals("/api/v1/jobs/" + jobId, submitJson.at("/data/detailUrl").asText(), "detailUrl 应匹配");
        Assertions.assertEquals("/api/v1/jobs/" + jobId + "/result", submitJson.at("/data/resultUrl").asText(), "resultUrl 应匹配");
        Assertions.assertEquals("/api/v1/jobs/" + jobId + "/cancel", submitJson.at("/data/cancelUrl").asText(), "cancelUrl 应匹配");
        Assertions.assertEquals(120L, submitJson.at("/data/pollAfterMs").asLong(), "轮询间隔应匹配配置");

        JsonNode detailJson = waitUntilDone(jobId, 40, 150L);
        Assertions.assertTrue(detailJson.at("/data/done").asBoolean(), "任务应在轮询窗口内完成");
        Assertions.assertEquals(120L, detailJson.at("/data/pollAfterMs").asLong(), "任务详情轮询间隔应匹配配置");

        MvcResult resultRes = mockMvc.perform(get("/api/v1/jobs/{jobId}/result", jobId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resultJson = objectMapper.readTree(resultRes.getResponse().getContentAsString());
        Assertions.assertTrue(resultJson.at("/success").asBoolean(), "接口响应应成功");
        Assertions.assertEquals("mr_calc", resultJson.at("/data/engineCode").asText(), "引擎编码应匹配");
        Assertions.assertEquals("it-job-001", resultJson.at("/data/requestId").asText(), "requestId 应匹配");
    }

    @Test
    void submitWithSameIdempotencyKey_shouldReuseJob() throws Exception {
        String firstPayload = "{"
                + "\"requestId\":\"it-job-002\","
                + "\"engineCode\":\"mr_calc\","
                + "\"idempotencyKey\":\"it-idem-002\","
                + "\"payload\":{}"
                + "}";
        String secondPayload = "{"
                + "\"requestId\":\"it-job-003\","
                + "\"engineCode\":\"mr_calc\","
                + "\"idempotencyKey\":\"it-idem-002\","
                + "\"payload\":{}"
                + "}";

        MvcResult firstRes = mockMvc.perform(post("/api/v1/jobs/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPayload))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult secondRes = mockMvc.perform(post("/api/v1/jobs/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondPayload))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(firstRes.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(secondRes.getResponse().getContentAsString());
        String firstJobId = firstJson.at("/data/jobId").asText();
        String secondJobId = secondJson.at("/data/jobId").asText();
        Assertions.assertEquals(firstJobId, secondJobId, "相同幂等键应返回相同 jobId");
        Assertions.assertTrue(secondJson.at("/data/reused").asBoolean(), "第二次提交应命中幂等复用");
    }

    @Test
    void cancelTerminalJob_shouldKeepStatusAndNotMarkCancelRequested() throws Exception {
        String payload = "{"
                + "\"requestId\":\"it-job-004\","
                + "\"engineCode\":\"mr_calc\","
                + "\"idempotencyKey\":\"it-idem-004\","
                + "\"payload\":{}"
                + "}";

        MvcResult submitRes = mockMvc.perform(post("/api/v1/jobs/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode submitJson = objectMapper.readTree(submitRes.getResponse().getContentAsString());
        String jobId = submitJson.at("/data/jobId").asText();

        JsonNode beforeCancel = waitUntilDone(jobId, 40, 150L);
        Assertions.assertTrue(beforeCancel.at("/data/done").asBoolean(), "任务应先进入终态");
        String terminalStatus = beforeCancel.at("/data/status").asText();

        MvcResult cancelRes = mockMvc.perform(post("/api/v1/jobs/{jobId}/cancel", jobId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode cancelJson = objectMapper.readTree(cancelRes.getResponse().getContentAsString());
        Assertions.assertEquals(terminalStatus, cancelJson.at("/data/status").asText(), "终态任务取消后状态不应变化");
        Assertions.assertFalse(cancelJson.at("/data/cancelRequested").asBoolean(), "终态任务不应写入取消标记");
    }

    /**
     * 轮询任务状态直到完成。
     */
    private JsonNode waitUntilDone(String jobId, int maxRounds, long sleepMillis) throws Exception {
        JsonNode latest = null;
        for (int i = 0; i < maxRounds; i++) {
            MvcResult detailRes = mockMvc.perform(get("/api/v1/jobs/{jobId}", jobId))
                    .andExpect(status().isOk())
                    .andReturn();
            latest = objectMapper.readTree(detailRes.getResponse().getContentAsString());
            if (latest.at("/data/done").asBoolean()) {
                return latest;
            }
            Thread.sleep(sleepMillis);
        }
        return latest;
    }
}
