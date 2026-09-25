package com.hragent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.entity.Jd;
import com.hragent.entity.OpLog;
import com.hragent.repository.JdMapper;
import com.hragent.repository.OpLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JdControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpLogMapper opLogMapper;

    @Autowired
    private JdMapper jdMapper;

    private String token() throws Exception {
        return TestAuthHelper.loginAsAdmin(mockMvc, objectMapper);
    }

    @Test
    void createJd() throws Exception {
        String token = token();
        Map<String, Object> body = Map.of(
                "title", "Java 后端工程师",
                "externalJd", "负责后端服务开发",
                "internalNotes", "关键词:Java SpringBoot",
                "salaryMin", 20000,
                "salaryMax", 35000);

        mockMvc.perform(post("/api/jd")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void createJdWritesAuditLog() throws Exception {
        String token = token();
        long before = opLogMapper.selectCount(
                new LambdaQueryWrapper<OpLog>().eq(OpLog::getTargetType, "jd"));

        Map<String, Object> body = Map.of("title", "审计测试岗位");
        mockMvc.perform(post("/api/jd")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        long after = opLogMapper.selectCount(
                new LambdaQueryWrapper<OpLog>().eq(OpLog::getTargetType, "jd"));
        assertEquals(before + 1, after, "创建岗位应写入审计日志");
    }

    @Test
    void jdFullCrudFlow() throws Exception {
        String token = token();

        // create
        String createBody = mockMvc.perform(post("/api/jd")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "CRUD 流程岗位"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(createBody).path("data").path("id").asLong();

        // get
        mockMvc.perform(get("/api/jd/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("CRUD 流程岗位"));

        // update
        mockMvc.perform(put("/api/jd/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "更新后的岗位", "status", "CLOSED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("更新后的岗位"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        // delete
        mockMvc.perform(delete("/api/jd/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // get after delete -> 404
        mockMvc.perform(get("/api/jd/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void pageReturnsPagedResult() throws Exception {
        String token = token();
        mockMvc.perform(get("/api/jd")
                        .header("Authorization", "Bearer " + token)
                        .param("pageNo", "1")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    /**
     * 评审 Critical-1:通用 POST/PUT /api/jd 不得写入门禁字段,
     * 否则任一登录用户可绕过 /threshold/confirm 直接注入门槛放行自动外发。
     */
    @Test
    void genericJdEndpointsCannotInjectThresholdConfirmation() throws Exception {
        String token = token();
        Map<String, Object> injected = Map.of(
                "title", "门槛绕过测试岗位",
                "scoreThreshold", 10,
                "thresholdSuggestion", "建议10分:绕过尝试",
                "thresholdConfirmedBy", 1,
                "thresholdConfirmedAt", "2026-09-25T10:00:00");

        // POST 注入
        String createBody = mockMvc.perform(post("/api/jd")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(injected)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(createBody).path("data").path("id").asLong();

        Jd created = jdMapper.selectById(id);
        assertNull(created.getScoreThreshold(), "通用创建接口不得写入门槛值");
        assertNull(created.getThresholdSuggestion(), "通用创建接口不得写入建议文本");
        assertNull(created.getThresholdConfirmedBy(), "通用创建接口不得写入确认人");
        assertNull(created.getThresholdConfirmedAt(), "confirmedAt 仍为 null(门禁 fail-closed)");

        // PUT 注入
        mockMvc.perform(put("/api/jd/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(injected)))
                .andExpect(status().isOk());

        Jd updated = jdMapper.selectById(id);
        assertNull(updated.getScoreThreshold(), "通用更新接口不得写入门槛值");
        assertNull(updated.getThresholdSuggestion(), "通用更新接口不得写入建议文本");
        assertNull(updated.getThresholdConfirmedBy(), "通用更新接口不得写入确认人");
        assertNull(updated.getThresholdConfirmedAt(), "confirmedAt 仍为 null(门禁 fail-closed)");
    }

    /** 已确认门槛不得被后续通用 PUT 覆盖或清除(updateById 忽略 null 字段) */
    @Test
    void genericUpdatePreservesConfirmedThreshold() throws Exception {
        String token = token();
        String createBody = mockMvc.perform(post("/api/jd")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "门槛保护测试岗位"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(createBody).path("data").path("id").asLong();

        mockMvc.perform(put("/api/jd/" + id + "/threshold/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("threshold", 75))))
                .andExpect(status().isOk());

        // 通用 PUT 携带伪造门槛字段:既不得覆盖也不得清除既有确认
        mockMvc.perform(put("/api/jd/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "门槛保护测试岗位",
                                "scoreThreshold", 10,
                                "thresholdConfirmedAt", "2026-09-25T10:00:00"))))
                .andExpect(status().isOk());

        Jd after = jdMapper.selectById(id);
        assertEquals(75, after.getScoreThreshold(), "已确认门槛不得被通用接口覆盖");
        assertNotNull(after.getThresholdConfirmedAt(), "已确认状态不得被通用接口清除");
    }
}
