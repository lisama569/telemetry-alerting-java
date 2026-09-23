package com.github.lisama569.telemetryalertingjava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * 端到端：HTTP 埋点 -> 窗口聚合 -> 注册规则 -> 评估，以及非法输入的拒绝码。
 */
@SpringBootTest(properties = {"telemetry.window-millis=60000", "telemetry.bucket-millis=1000"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.MethodName.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TelemetryControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void recordsAggregatesRegistersRuleAndEvaluates() throws Exception {
        // 输入：3 成功 2 失败，延迟 10~50ms
        for (int i = 1; i <= 3; i++) {
            postEvent("int-api", "SUCCESS", i * 10L);
        }
        postEvent("int-api", "FAILURE", 40L);
        postEvent("int-api", "FAILURE", 50L);

        String body = mockMvc.perform(get("/telemetry/metrics/int-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callCount").value(5))
                .andExpect(jsonPath("$.failureCount").value(2))
                .andExpect(jsonPath("$.failureRate").value(0.4))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 聚合结果: {}", body);

        // 注册规则：failureRate >= 0.4，连续 1 次超标即 FIRING
        mockMvc.perform(post("/telemetry/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleId":"fr-rule","api":"int-api","metricType":"FAILURE_RATE",
                                 "operator":"GTE","threshold":0.4,
                                 "pendingRequired":1,"recoveryRequired":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true));

        String eval = mockMvc.perform(post("/telemetry/rules/fr-rule/evaluate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FIRING"))
                .andExpect(jsonPath("$.breached").value(true))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 状态判定: {}", eval);
    }

    @Test
    void rejectsIllegalEventsAndRulesOverHttp() throws Exception {
        String badEvent = mockMvc.perform(post("/telemetry/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"api":"bad","status":"WEIRD","latencyMillis":10}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NULL_STATUS"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 非法埋点被拒: {}", badEvent);

        String negLatency = mockMvc.perform(post("/telemetry/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"api":"bad","status":"SUCCESS","latencyMillis":-3}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NEGATIVE_LATENCY"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 负延迟被拒: {}", negLatency);

        String badRule = mockMvc.perform(post("/telemetry/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleId":"bad-rule","api":"x","metricType":"FAILURE_RATE",
                                 "operator":"GT","threshold":1.5,
                                 "pendingRequired":1,"recoveryRequired":1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("THRESHOLD_OUT_OF_RANGE"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 非法规则被拒: {}", badRule);

        String unknownMetric = mockMvc.perform(post("/telemetry/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ruleId":"um-rule","api":"never-seen","metricType":"CALL_COUNT",
                                 "operator":"GT","threshold":0,
                                 "pendingRequired":1,"recoveryRequired":1}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        log.info("规则注册成功（引用尚无数据的接口是允许的）: {}", unknownMetric);

        String evalUnknown = mockMvc.perform(post("/telemetry/rules/um-rule/evaluate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNKNOWN_METRIC"))
                .andReturn().getResponse().getContentAsString();
        log.info("HTTP 评估未知指标被拒: {}", evalUnknown);
    }

    private void postEvent(String api, String status, long latency) throws Exception {
        mockMvc.perform(post("/telemetry/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"api\":\"" + api + "\",\"status\":\"" + status
                        + "\",\"latencyMillis\":" + latency + "}"))
                .andExpect(status().isOk());
    }
}
