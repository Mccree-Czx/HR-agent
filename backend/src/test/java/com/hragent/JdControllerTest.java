package com.hragent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.entity.Jd;
import com.hragent.entity.OpLog;
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
}
