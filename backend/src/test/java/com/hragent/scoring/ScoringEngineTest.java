package com.hragent.scoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hragent.ai.AiClient;
import com.hragent.common.BizException;
import com.hragent.entity.Candidate;
import com.hragent.entity.Jd;
import com.hragent.entity.ScoreRecord;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.ScoreRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScoringEngineTest {

    @Autowired
    private ScoringEngine scoringEngine;

    @Autowired
    private CandidateMapper candidateMapper;

    @Autowired
    private JdMapper jdMapper;

    @Autowired
    private ScoreRecordMapper scoreRecordMapper;

    @MockitoBean
    private AiClient aiClient;

    private Jd jd;

    @BeforeEach
    void setUp() {
        candidateMapper.delete(new LambdaQueryWrapper<>());
        jdMapper.delete(new LambdaQueryWrapper<>());
        scoreRecordMapper.delete(new LambdaQueryWrapper<>());

        jd = new Jd();
        jd.setTitle("软件工程师");
        jd.setExternalJd("负责后端服务开发,熟悉 SpringBoot/MySQL");
        jd.setSalaryMin(20000);
        jd.setSalaryMax(35000);
        jdMapper.insert(jd);
    }

    private Candidate candidate(String snapshot) {
        Candidate c = new Candidate();
        c.setResumeId("r" + System.nanoTime());
        c.setName("测试候选人");
        c.setSnapshot(snapshot);
        c.setPassStatus("PENDING");
        c.setJdId(jd.getId());
        candidateMapper.insert(c);
        return c;
    }

    @Test
    void scoreAndSavePasses() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":75,\"pass\":true,\"summary\":\"匹配良好\",\"reasons\":[\"技能吻合\",\"薪资合适\"]}");

        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"city\":\"北京\",\"experience\":\"5年\",\"want_title\":\"软件工程师\"}");
        ScoreRecord record = scoringEngine.scoreAndSave(c.getId());

        assertEquals(75, record.getScore());
        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("PASS", after.getPassStatus());
        assertEquals(75, after.getScore());
        assertEquals(1, scoreRecordMapper.selectCount(null));
    }

    @Test
    void parseFailureRetriesThenSucceeds() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("这不是 JSON")
                .thenReturn("{\"score\":60,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");

        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        scoringEngine.scoreAndSave(c.getId());

        verify(aiClient, times(2)).chat(anyString(), anyString());
        assertEquals("PASS", candidateMapper.selectById(c.getId()).getPassStatus());
    }

    @Test
    void parseFailureExhaustsRetryThenFails() {
        when(aiClient.chat(anyString(), anyString())).thenReturn("永远不是 JSON");

        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        assertThrows(BizException.class, () -> scoringEngine.scoreAndSave(c.getId()));
        // 1 次原始 + maxParseRetry 次重试 = 3 次
        verify(aiClient, times(3)).chat(anyString(), anyString());
        assertEquals("PENDING", candidateMapper.selectById(c.getId()).getPassStatus());
    }

    @Test
    void preFilterSkipsModelWhenSalaryTooHigh() {
        // 期望薪资下限 45K > 35K * 1.5 = 52.5K? 45K 未超。用 60K
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"60-80K\",\"city\":\"北京\",\"want_title\":\"软件工程师\"}");
        scoringEngine.scoreAndSave(c.getId());

        verify(aiClient, never()).chat(anyString(), anyString());
        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("FAIL", after.getPassStatus());
        assertEquals(0, after.getScore());
    }

    @Test
    void preFilterAllowsNormalSalary() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":70,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"city\":\"北京\",\"want_title\":\"软件工程师\"}");
        scoringEngine.scoreAndSave(c.getId());
        verify(aiClient, times(1)).chat(anyString(), anyString());
    }

    @Test
    void parseSalaryMin() {
        assertEquals(45000, scoringEngine.parseSalaryMin("45-60K·16薪"));
        assertEquals(13000, scoringEngine.parseSalaryMin("13-15K"));
        assertEquals(30000, scoringEngine.parseSalaryMin("30K"));
        assertEquals(null, scoringEngine.parseSalaryMin("面议"));
        assertEquals(null, scoringEngine.parseSalaryMin(""));
    }

    @Test
    void jobMatchMismatchFailsWithoutCallingModel() {
        // 期望职能明确不匹配(hr) vs 目标(software) → 直接 FAIL,不调 AI
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"人力资源总监\"}");
        scoringEngine.scoreAndSave(c.getId());

        verify(aiClient, never()).chat(anyString(), anyString());
        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("FAIL", after.getPassStatus());
        assertEquals(0, after.getScore());
    }

    @Test
    void jobUnknownStaysPendingAndNeverPass() {
        // 缺期望字段 → 职能待确认 → PENDING 且绝不 PASS,不调 AI
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\"}");
        scoringEngine.scoreAndSave(c.getId());

        verify(aiClient, never()).chat(anyString(), anyString());
        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("PENDING", after.getPassStatus());
        assertNotEquals("PASS", after.getPassStatus());
        ScoreRecord record = scoreRecordMapper.selectOne(new LambdaQueryWrapper<>());
        assertTrue(record.getReason().contains("职能待确认"), "score_record.reason 应标记职能待确认");
    }

    @Test
    void jobMatchContinuesToAi() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":80,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        scoringEngine.scoreAndSave(c.getId());
        verify(aiClient, times(1)).chat(anyString(), anyString());
        assertEquals("PASS", candidateMapper.selectById(c.getId()).getPassStatus());
    }

    /** 评审 Important-2:模型 pass=true 但 score < 已确认门槛 → 最终 FAIL(门槛不可被模型绕过) */
    @Test
    void scoreBelowConfirmedThresholdDowngradesModelPassToFail() {
        jd.setScoreThreshold(80);
        jdMapper.updateById(jd);
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":70,\"pass\":true,\"summary\":\"模型认为可过\",\"reasons\":[\"a\"]}");

        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        ScoreRecord record = scoringEngine.scoreAndSave(c.getId());

        assertEquals(70, record.getScore());
        assertEquals("FAIL", candidateMapper.selectById(c.getId()).getPassStatus(),
                "score 70 低于门槛 80,模型 pass=true 也必须 FAIL");
    }

    /** 评审 Important-2:评分提示词必须显式携带已确认门槛 */
    @Test
    void userPromptCarriesConfirmedThreshold() {
        jd.setScoreThreshold(80);
        jdMapper.updateById(jd);
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":85,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");

        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        scoringEngine.scoreAndSave(c.getId());

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(anyString(), userPrompt.capture());
        assertTrue(userPrompt.getValue().contains("通过门槛：80 分"), "评分提示词须携带已确认门槛");
        assertEquals("PASS", candidateMapper.selectById(c.getId()).getPassStatus());
    }

    // scorePending(批量补评分,含在线简历详情读取与独立事务)见 ScoringEnginePendingTest:
    // 其使用 REQUIRES_NEW 独立事务,需在无外层测试事务的环境下验证。
}
