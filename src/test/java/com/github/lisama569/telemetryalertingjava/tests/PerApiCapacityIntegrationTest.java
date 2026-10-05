package com.github.lisama569.telemetryalertingjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：运行期按接口设置 / 查询 / 清除保留上限的 HTTP 行为。
 * 上下文默认上限 telemetry.max-samples-per-api=10，用于验证"未覆盖接口走默认值"。
 */
@SpringBootTest(properties = {
        "telemetry.window-millis=60000", "telemetry.bucket-millis=1000",
        "telemetry.max-samples-per-api=10"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PerApiCapacityIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(PerApiCapacityIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void perApiLimitCanBeSetQueriedAndClearedAtRuntime() throws Exception {
        // 初始：未覆盖，走默认值 10
        String initial = mockMvc.perform(get("/telemetry/metrics/rt-api/max-samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(10))
                .andExpect(jsonPath("$.defaultMaxSamplesPerApi").value(10))
                .andExpect(jsonPath("$.overridden").value(false))
                .andReturn().getResponse().getContentAsString();
        log.info("初始生效上限（默认）: {}", initial);

        // 运行期设置为 3，立即生效
        String set = mockMvc.perform(put("/telemetry/metrics/rt-api/max-samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSamples\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(3))
                .andExpect(jsonPath("$.overridden").value(true))
                .andReturn().getResponse().getContentAsString();
        log.info("运行期设置上限为 3: {}", set);

        // 写 5 条：按新上限只保留 3 条，淘汰统计带出该接口自己的上限值
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/telemetry/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"api\":\"rt-api\",\"status\":\"SUCCESS\",\"latencyMillis\":1}"))
                    .andExpect(status().isOk());
        }
        String evictions = mockMvc.perform(get("/telemetry/metrics/rt-api/evictions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(3))
                .andExpect(jsonPath("$.retainedSamples").value(3))
                .andExpect(jsonPath("$.evictedSamples").value(2))
                .andReturn().getResponse().getContentAsString();
        log.info("写 5 条后容量统计（cap=3）: {}", evictions);

        // 无效上限（0 / 负数）明确拒绝为 400，且已生效上限不被改动
        String rejected = mockMvc.perform(put("/telemetry/metrics/rt-api/max-samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSamples\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_MAX_SAMPLES"))
                .andReturn().getResponse().getContentAsString();
        log.info("设置 0 被拒绝: {}", rejected);
        mockMvc.perform(put("/telemetry/metrics/rt-api/max-samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSamples\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_MAX_SAMPLES"));
        mockMvc.perform(get("/telemetry/metrics/rt-api/max-samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(3))
                .andExpect(jsonPath("$.overridden").value(true));
        log.info("拒绝后生效上限仍为 3");

        // 清除覆盖：恢复默认值 10
        String cleared = mockMvc.perform(delete("/telemetry/metrics/rt-api/max-samples"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true))
                .andExpect(jsonPath("$.maxSamplesPerApi").value(10))
                .andExpect(jsonPath("$.overridden").value(false))
                .andReturn().getResponse().getContentAsString();
        log.info("清除覆盖后恢复默认: {}", cleared);
    }
}
