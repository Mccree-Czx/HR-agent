package com.hragent.scoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.ai.AiClient;
import com.hragent.config.HrAgentProperties;
import com.hragent.entity.Candidate;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.ScoreRecord;
import com.hragent.executor.CliException;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.ScoreRecordMapper;
import com.hragent.service.LiepinCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScoringEngine#scorePending(Long)} 批量补评分测试(终审 C1 在线简历详情读取 + I3 重评/独立事务)。
 *
 * <p>刻意<b>不使用 {@code @Transactional}</b>:单候选人评分经 {@link CandidateScoringExecutor} 的
 * {@code REQUIRES_NEW} 独立事务落库,若测试外层存在未提交事务,内层新事务既看不到候选人
 * (MVCC 不可见),又会因行锁冲突而失败。故本类以显式 {@code setUp} 清理数据保证隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScoringEnginePendingTest {

    @Autowired
    private ScoringEngine scoringEngine;

    @Autowired
    private CandidateMapper candidateMapper;

    @Autowired
    private JdMapper jdMapper;

    @Autowired
    private ScoreRecordMapper scoreRecordMapper;

    @Autowired
    private LiepinAccountMapper accountMapper;

    @Autowired
    private HrAgentProperties properties;

    @MockitoBean
    private AiClient aiClient;

    @MockitoBean
    private LiepinCommandService commandService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Jd jd;

    @BeforeEach
    void setUp() {
        scoreRecordMapper.delete(new LambdaQueryWrapper<>());
        candidateMapper.delete(new LambdaQueryWrapper<>());
        accountMapper.delete(new LambdaQueryWrapper<>());
        jdMapper.delete(new LambdaQueryWrapper<>());
        // 详情读取间隔置 0,避免用例等待
        properties.getAutoRecruit().setResumeDetailBatchLimit(20);
        properties.getAutoRecruit().setResumeDetailIntervalMillis(0);

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

    private LiepinAccount createNormalAccount() {
        LiepinAccount account = new LiepinAccount();
        account.setName("测试账号");
        account.setLoginStatus("NORMAL");
        account.setCircuitBreaker(false);
        accountMapper.insert(account);
        return account;
    }

    // ---------- 基线:批量补评分 ----------

    @Test
    void scorePendingScoresUnscoredCandidates() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":75,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        candidate("{\"name\":\"李四\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(2, scored);
        assertEquals(2, scoreRecordMapper.selectCount(null));
        verify(aiClient, times(2)).chat(anyString(), anyString());
    }

    @Test
    void scorePendingSkipsCandidatesWithExistingNonPendingRecord() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":75,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        // A:已有非「职能待确认」评分记录 → 不重复评分
        Candidate a = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        ScoreRecord existing = new ScoreRecord();
        existing.setCandidateId(a.getId());
        existing.setJdId(jd.getId());
        existing.setScore(70);
        existing.setReason("AI 已评");
        scoreRecordMapper.insert(existing);
        // B:无记录 → 应被补评分
        candidate("{\"name\":\"李四\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(1, scored, "已有记录的候选人应跳过");
        verify(aiClient, times(1)).chat(anyString(), anyString());
        assertEquals(2, scoreRecordMapper.selectCount(null), "不新增重复评分记录");
    }

    @Test
    void scorePendingOnlySelectsPendingStatus() {
        Candidate pass = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        pass.setPassStatus("PASS");
        candidateMapper.updateById(pass);
        Candidate fail = candidate("{\"name\":\"李四\",\"salary\":\"20-30K\",\"want_title\":\"软件工程师\"}");
        fail.setPassStatus("FAIL");
        candidateMapper.updateById(fail);

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(0, scored, "非 PENDING 候选人不应被补评分");
        verify(aiClient, never()).chat(anyString(), anyString());
    }

    // ---------- C1:评分前读取在线简历详情并合并期望字段 ----------

    @Test
    void scorePendingFetchesResumeDetailAndScoresWhenExpectationMissing() throws Exception {
        createNormalAccount();
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":75,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"talentId\":\"res-1\"}");
        when(commandService.resume(any(), eq("res-1"), any())).thenReturn(Optional.of(
                objectMapper.readTree("{\"want_title\":\"软件工程师\",\"expectation_evidence\":{"
                        + "\"source\":\"resumeDetailVo.jobWant.jobTitleNames\","
                        + "\"entries\":[{\"title\":\"软件工程师\"}]}}")));

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(1, scored);
        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("PASS", after.getPassStatus(), "补齐期望后应可 PASS");
        assertTrue(after.getSnapshot().contains("expectation_evidence"), "简历详情期望字段应合并写回快照");
        assertTrue(after.getSnapshot().contains("\"talentId\":\"res-1\""), "合并不得丢失既有字段");
        verify(commandService).resume(any(), eq("res-1"), any());
    }

    @Test
    void scorePendingKeepsUnknownWhenResumeDetailEmpty() {
        createNormalAccount();
        Candidate c = candidate("{\"name\":\"张三\",\"talentId\":\"res-1\"}");
        when(commandService.resume(any(), eq("res-1"), any())).thenReturn(Optional.empty());

        scoringEngine.scorePending(jd.getId());

        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("PENDING", after.getPassStatus());
        assertNotEquals("PASS", after.getPassStatus());
        verify(aiClient, never()).chat(anyString(), anyString());
    }

    @Test
    void scorePendingKeepsUnknownWhenResumeDetailFailsWithoutInterruptingBatch() throws Exception {
        createNormalAccount();
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":80,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate a = candidate("{\"name\":\"甲\",\"talentId\":\"res-a\"}");
        Candidate b = candidate("{\"name\":\"乙\",\"talentId\":\"res-b\"}");
        when(commandService.resume(any(), eq("res-a"), any()))
                .thenThrow(new CliException(CliException.Type.FAILED, "详情读取失败"));
        when(commandService.resume(any(), eq("res-b"), any())).thenReturn(Optional.of(
                objectMapper.readTree("{\"want_title\":\"软件工程师\"}")));

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(2, scored, "详情失败不得中断整批(仍写出待确认记录)");
        assertEquals("PENDING", candidateMapper.selectById(a.getId()).getPassStatus(), "详情失败保持待确认");
        assertEquals("PASS", candidateMapper.selectById(b.getId()).getPassStatus());
    }

    // ---------- I3①:快照更新后对「职能待确认」重评一次 ----------

    @Test
    void scorePendingReevaluatesPendingConclusionAfterSnapshotUpdate() {
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":80,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate c = candidate("{\"name\":\"张三\",\"want_title\":\"软件工程师\"}");
        ScoreRecord stale = new ScoreRecord();
        stale.setCandidateId(c.getId());
        stale.setJdId(jd.getId());
        stale.setScore(0);
        stale.setReason("职能待确认: 缺少可靠的求职期望或字段格式异常");
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        scoreRecordMapper.insert(stale);
        // 模拟快照更新:候选人 updated_at 晚于遗留「职能待确认」记录
        c.setSnapshot("{\"name\":\"张三\",\"want_title\":\"软件工程师\"}");
        c.setUpdatedAt(LocalDateTime.now());
        candidateMapper.updateById(c);

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(1, scored, "快照更新后应重评一次");
        assertEquals("PASS", candidateMapper.selectById(c.getId()).getPassStatus());
        assertEquals(2, scoreRecordMapper.selectCount(null), "重评应追加新记录");
    }

    @Test
    void scorePendingSkipsPendingConclusionWithoutSnapshotUpdate() {
        Candidate c = candidate("{\"name\":\"张三\"}");
        ScoreRecord stale = new ScoreRecord();
        stale.setCandidateId(c.getId());
        stale.setJdId(jd.getId());
        stale.setScore(0);
        stale.setReason("职能待确认: 缺少可靠的求职期望或字段格式异常");
        // 记录晚于候选人更新时间 → 视为快照未更新
        stale.setCreatedAt(LocalDateTime.now().plusMinutes(5));
        scoreRecordMapper.insert(stale);

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(0, scored, "快照未更新不得重评");
        verify(aiClient, never()).chat(anyString(), anyString());
    }

    // ---------- I3②:单候选独立事务,前序已提交结果不被后续致命异常回滚 ----------

    @Test
    void scoredCandidateCommitSurvivesFatalFailureOfLaterCandidate() {
        createNormalAccount();
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":80,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate earlier = candidate("{\"name\":\"甲\",\"want_title\":\"软件工程师\",\"talentId\":\"res-a\"}");
        candidate("{\"name\":\"乙\",\"talentId\":\"res-b\"}");
        when(commandService.resume(any(), eq("res-b"), any()))
                .thenThrow(new CliException(CliException.Type.RISK_CONTROL, "安全验证"));

        assertThrows(CliException.class, () -> scoringEngine.scorePending(jd.getId()));

        Candidate after = candidateMapper.selectById(earlier.getId());
        assertEquals("PASS", after.getPassStatus(), "前一位已提交结果不得被后续致命异常整批回滚");
        assertEquals(1, scoreRecordMapper.selectCount(new LambdaQueryWrapper<ScoreRecord>()
                .eq(ScoreRecord::getCandidateId, earlier.getId())));
    }
}
