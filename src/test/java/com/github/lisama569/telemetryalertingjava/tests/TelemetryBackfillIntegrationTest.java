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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：历史补报、按时间点回看、容量淘汰统计三个新端点的 HTTP 行为。
 * 上下文启用 telemetry.max-samples-per-api=5 以覆盖容量上限的装配。
 */
@SpringBootTest(properties = {
        "telemetry.window-millis=60000", "telemetry.bucket-millis=1000",
        "telemetry.max-samples-per-api=5"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TelemetryBackfillIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(TelemetryBackfillIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void backfillLookbackAndEvictionsOverHttp() throws Exception {
        long now = System.currentTimeMillis();
        // 乱序补报 4 条合法（时间戳倒序）+ 1 条缺时间戳 + 1 条状态非法
        String body = """
                {"events":[
                  {"api":"hf-api","status":"SUCCESS","latencyMillis":10,"timestampMillis":%d},
                  {"api":"hf-api","status":"FAILURE","latencyMillis":20,"timestampMillis":%d},
                  {"api":"hf-api","status":"SUCCESS","latencyMillis":30,"timestampMillis":%d},
                  {"api":"hf-api","status":"FAILURE","latencyMillis":40,"timestampMillis":%d},
                  {"api":"hf-api","status":"SUCCESS","latencyMillis":50},
                  {"api":"hf-api","status":"WEIRD","latencyMillis":60,"timestampMillis":%d}
                ]}""".formatted(now - 500, now - 2_000, now - 4_000, now - 3_000, now - 1_000);
        log.info("补报请求体: {}", body);

        String report = mockMvc.perform(post("/telemetry/events/backfill")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(6))
                .andExpect(jsonPath("$.accepted").value(4))
                .andExpect(jsonPath("$.rejections.length()").value(2))
                .andExpect(jsonPath("$.rejections[0].reason").value("NULL_TIMESTAMP"))
                .andExpect(jsonPath("$.rejections[1].reason").value("NULL_STATUS"))
                .andReturn().getResponse().getContentAsString();
        log.info("补报回执: {}", report);

        // 当前窗口聚合：补报的 4 条已计入
        String snapshot = mockMvc.perform(get("/telemetry/metrics/hf-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(4))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andReturn().getResponse().getContentAsString();
        log.info("补报后当前窗口聚合: {}", snapshot);

        // 按时间点回看：t=now-2500 时只有落在 <= 该时刻的 2 条（-4000 与 -3000）
        String lookback = mockMvc.perform(get("/telemetry/metrics/hf-api/at")
                        .param("timestampMillis", String.valueOf(now - 2_500)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andReturn().getResponse().getContentAsString();
        log.info("回看 t=now-2500 聚合: {}", lookback);

        // 再写入新数据后回看同一时刻，结果稳定
        mockMvc.perform(post("/telemetry/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"api\":\"hf-api\",\"status\":\"FAILURE\",\"latencyMillis\":99}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/telemetry/metrics/hf-api/at")
                        .param("timestampMillis", String.valueOf(now - 2_500)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(1));
        log.info("追加实时写入后回看 t=now-2500 结果不变（callCount=2）");

        // 容量上限 5：当前共 5 条，再实时写 3 条触发容量淘汰
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/telemetry/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"api\":\"hf-api\",\"status\":\"SUCCESS\",\"latencyMillis\":1}"))
                    .andExpect(status().isOk());
        }
        String evictions = mockMvc.perform(get("/telemetry/metrics/hf-api/evictions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxSamplesPerApi").value(5))
                .andExpect(jsonPath("$.retainedSamples").value(5))
                .andExpect(jsonPath("$.evictedSamples").value(3))
                .andReturn().getResponse().getContentAsString();
        log.info("容量淘汰统计: {}", evictions);

        String capped = mockMvc.perform(get("/telemetry/metrics/hf-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(5))
                .andReturn().getResponse().getContentAsString();
        log.info("容量到顶后的窗口聚合（callCount=5）: {}", capped);
    }
}
