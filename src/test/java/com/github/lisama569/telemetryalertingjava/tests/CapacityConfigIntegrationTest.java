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
 * 端到端：按接口保留上限的设置/查询/清除 HTTP 行为。
 * 上下文默认上限 telemetry.max-samples-per-api=5，用于验证"未单独设置走默认值"。
 */
@SpringBootTest(properties = {
        "telemetry.window-millis=60000", "telemetry.bucket-millis=1000",
        "telemetry.max-samples-per-api=5"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CapacityConfigIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(CapacityConfigIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void perApiCapacityCanBeSetQueriedAndClearedOverHttp() throws Exception {
        // 未设置时查询：走默认值 5，overridden=false
        String initial = mockMvc.perform(get("/telemetry/metrics/hot-api/capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(5))
                .andExpect(jsonPath("$.defaultMaxSamplesPerApi").value(5))
                .andExpect(jsonPath("$.overridden").value(false))
                .andReturn().getResponse().getContentAsString();
        log.info("未单独设置时查询生效上限: {}", initial);

        // 运行期设置为 3，立即生效
        String set = mockMvc.perform(put("/telemetry/metrics/hot-api/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSamples\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(3))
                .andExpect(jsonPath("$.overridden").value(true))
                .andReturn().getResponse().getContentAsString();
        log.info("运行期设置上限为 3: {}", set);

        // 写 5 条，只保留 3 条，淘汰 2 条；淘汰统计按该接口自己的上限展示
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/telemetry/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"api\":\"hot-api\",\"status\":\"SUCCESS\",\"latencyMillis\":1}"))
                    .andExpect(status().isOk());
        }
        String evictions = mockMvc.perform(get("/telemetry/metrics/hot-api/evictions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(3))
                .andExpect(jsonPath("$.retainedSamples").value(3))
                .andExpect(jsonPath("$.evictedSamples").value(2))
                .andReturn().getResponse().getContentAsString();
        log.info("写 5 条后容量淘汰统计（cap=3）: {}", evictions);

        // 无效上限：0 / 负数 / 缺字段都返回 400 + INVALID_CAPACITY，生效值不变
        String rejected = mockMvc.perform(put("/telemetry/metrics/hot-api/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSamples\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CAPACITY"))
                .andReturn().getResponse().getContentAsString();
        log.info("设置 0 被拒绝: {}", rejected);
        mockMvc.perform(put("/telemetry/metrics/hot-api/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxSamples\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CAPACITY"));
        mockMvc.perform(put("/telemetry/metrics/hot-api/capacity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CAPACITY"));
        mockMvc.perform(get("/telemetry/metrics/hot-api/capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(3))
                .andExpect(jsonPath("$.overridden").value(true));
        log.info("无效设置后生效上限仍为 3（未被悄悄改掉）");

        // 清除单独设置，恢复默认值 5
        String cleared = mockMvc.perform(delete("/telemetry/metrics/hot-api/capacity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(5))
                .andExpect(jsonPath("$.overridden").value(false))
                .andReturn().getResponse().getContentAsString();
        log.info("清除单独上限后恢复默认: {}", cleared);
    }
}
