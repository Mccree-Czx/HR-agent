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
        return candidate(snapshot, "r" + System.nanoTime());
    }

    private Candidate candidate(String snapshot, String storedResumeId) {
        Candidate c = new Candidate();
        c.setResumeId(storedResumeId);
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
        // 快照同时含推荐节点 talentId(enresId) 与搜索节点 resume_id → 详情必须用 resume_id 读取
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\","
                + "\"talentId\":\"talent-enres-id\",\"resume_id\":\"res-1\"}");
        when(commandService.resume(any(), eq("res-1"), any())).thenReturn(Optional.of(
                objectMapper.readTree("{\"want_title\":\"软件工程师\",\"expectation_evidence\":{"
                        + "\"source\":\"resumeDetailVo.jobWant.jobTitleNames\","
                        + "\"entries\":[{\"title\":\"软件工程师\"}]}}")));

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(1, scored);
        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("PASS", after.getPassStatus(), "补齐期望后应可 PASS");
        assertTrue(after.getSnapshot().contains("expectation_evidence"), "简历详情期望字段应合并写回快照");
        assertTrue(after.getSnapshot().contains("\"talentId\":\"talent-enres-id\""), "合并不得丢失既有字段");
        verify(commandService).resume(any(), eq("res-1"), any());
        verify(commandService, never()).resume(any(), eq("talent-enres-id"), any());
    }

    @Test
    void scorePendingKeepsUnknownWhenResumeDetailEmpty() {
        createNormalAccount();
        Candidate c = candidate("{\"name\":\"张三\",\"resume_id\":\"res-1\"}");
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
        Candidate a = candidate("{\"name\":\"甲\",\"resume_id\":\"res-a\"}");
        Candidate b = candidate("{\"name\":\"乙\",\"resume_id\":\"res-b\"}");
        when(commandService.resume(any(), eq("res-a"), any()))
                .thenThrow(new CliException(CliException.Type.FAILED, "详情读取失败"));
        when(commandService.resume(any(), eq("res-b"), any())).thenReturn(Optional.of(
                objectMapper.readTree("{\"want_title\":\"软件工程师\"}")));

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(2, scored, "详情失败不得中断整批(仍写出待确认记录)");
        assertEquals("PENDING", candidateMapper.selectById(a.getId()).getPassStatus(), "详情失败保持待确认");
        assertEquals("PASS", candidateMapper.selectById(b.getId()).getPassStatus());
    }

    /** snapshot 无 resume_id → 回退 candidate.resume_id 主列读详情。 */
    @Test
    void scorePendingUsesStoredResumeIdWhenSnapshotHasNoResumeId() throws Exception {
        createNormalAccount();
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":75,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate c = candidate("{\"name\":\"张三\",\"talentId\":\"talent-enres-id\"}", "stored-res-id");
        when(commandService.resume(any(), eq("stored-res-id"), any())).thenReturn(Optional.of(
                objectMapper.readTree("{\"want_title\":\"软件工程师\"}")));

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(1, scored);
        assertEquals("PASS", candidateMapper.selectById(c.getId()).getPassStatus());
        verify(commandService).resume(any(), eq("stored-res-id"), any());
    }

    /** snapshot 与主列均无有效简历标识 → 跳过详情读取(enresId/talentId 不作为详情标识)。 */
    @Test
    void scorePendingSkipsResumeDetailWhenNoResumeIdAvailable() {
        createNormalAccount();
        Candidate c = candidate("{\"name\":\"张三\",\"talentId\":\"talent-enres-id\"}", "");

        int scored = scoringEngine.scorePending(jd.getId());

        assertEquals(1, scored, "无有效简历标识仍写待确认(不中断批量)");
        assertEquals("PENDING", candidateMapper.selectById(c.getId()).getPassStatus());
        verify(commandService, never()).resume(any(), any(), any());
        verify(aiClient, never()).chat(anyString(), anyString());
    }

    // ---------- I3①:期望数据内容指纹变化后对「职能待确认」重评(不依赖 updated_at) ----------

    /**
     * 真实写入路径(不手工改 updated_at):首轮详情读取失败 → 落「职能待确认」记录(携带当时期望指纹);
     * 快照补齐期望字段(模拟 enrich 成功写回)→ 下一轮 scorePending 指纹变化触发重评并可 PASS。
     */
    @Test
    void scorePendingReevaluatesWhenExpectationFingerprintChanges() {
        createNormalAccount();
        // 首轮:详情读取返回空(等价读取失败/无期望)→ UNKNOWN,记录携带「空期望」指纹
        when(commandService.resume(any(), eq("res-1"), any())).thenReturn(Optional.empty());
        Candidate c = candidate("{\"name\":\"张三\",\"salary\":\"20-30K\",\"resume_id\":\"res-1\"}");

        assertEquals(1, scoringEngine.scorePending(jd.getId()), "首轮应写入待确认记录");
        assertEquals("PENDING", candidateMapper.selectById(c.getId()).getPassStatus());
        ScoreRecord first = scoreRecordMapper.selectOne(new LambdaQueryWrapper<>());
        assertTrue(first.getReason().contains("fingerprint:"), "待确认记录应携带期望指纹");

        // 模拟 enrich 成功写回:仅通过真实更新路径补齐快照期望字段(不触碰 updated_at)
        Candidate loaded = candidateMapper.selectById(c.getId());
        loaded.setSnapshot("{\"name\":\"张三\",\"salary\":\"20-30K\",\"resume_id\":\"res-1\","
                + "\"want_title\":\"软件工程师\"}");
        candidateMapper.updateById(loaded);

        // 次轮:期望指纹变化 → 重评 → PASS
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":80,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        assertEquals(1, scoringEngine.scorePending(jd.getId()), "期望指纹变化后应重评一次");
        assertEquals("PASS", candidateMapper.selectById(c.getId()).getPassStatus());
        assertEquals(2, scoreRecordMapper.selectCount(null), "重评沿用既有 insert 追加历史记录");
    }

    /** 期望指纹无变化 → 不重试:第二轮既不再读详情,也不调 AI,也不追加记录。 */
    @Test
    void scorePendingSkipsPendingConclusionWhenFingerprintUnchanged() {
        createNormalAccount();
        when(commandService.resume(any(), eq("res-1"), any())).thenReturn(Optional.empty());
        candidate("{\"name\":\"张三\",\"resume_id\":\"res-1\"}");

        scoringEngine.scorePending(jd.getId());           // 首轮:写待确认记录
        int second = scoringEngine.scorePending(jd.getId()); // 次轮:期望指纹未变 → 跳过

        assertEquals(0, second, "期望指纹未变化不得重评");
        verify(commandService, times(1)).resume(any(), eq("res-1"), any());
        verify(aiClient, never()).chat(anyString(), anyString());
        assertEquals(1, scoreRecordMapper.selectCount(null), "无变化不追加记录");
    }

    /** 详情读取仍失败(快照无期望)→ 仍 PENDING,行为与修复前一致(不重试、不 PASS)。 */
    @Test
    void scorePendingKeepsPendingWhenDetailStillMissing() {
        createNormalAccount();
        when(commandService.resume(any(), eq("res-1"), any())).thenReturn(Optional.empty());
        Candidate c = candidate("{\"name\":\"张三\",\"resume_id\":\"res-1\"}");

        scoringEngine.scorePending(jd.getId());
        scoringEngine.scorePending(jd.getId());

        Candidate after = candidateMapper.selectById(c.getId());
        assertEquals("PENDING", after.getPassStatus());
        assertNotEquals("PASS", after.getPassStatus());
        assertTrue(after.getSnapshot().contains("resume_id"), "快照保持原样(未猜期望)");
        verify(aiClient, never()).chat(anyString(), anyString());
        ScoreRecord record = scoreRecordMapper.selectOne(new LambdaQueryWrapper<>());
        assertTrue(record.getReason().contains("职能待确认"), "记录仍标记职能待确认");
    }

    /** 指纹稳定性:JSON 字段顺序不同但内容一致 → 同指纹;期望内容变化/补齐 → 不同指纹。 */
    @Test
    void expectationFingerprintStableAcrossKeyOrderAndChangesWithContent() {
        Candidate a = candidate("{\"name\":\"张三\",\"want_title\":\"软件工程师\","
                + "\"expectation_evidence\":{\"source\":\"resumeDetailVo.jobWant.jobTitleNames\","
                + "\"entries\":[{\"title\":\"软件工程师\"}]}}");
        Candidate b = candidate("{\"expectation_evidence\":{\"entries\":[{\"title\":\"软件工程师\"}],"
                + "\"source\":\"resumeDetailVo.jobWant.jobTitleNames\"},\"want_title\":\"软件工程师\",\"name\":\"张三\"}");
        Candidate changed = candidate("{\"name\":\"张三\",\"want_title\":\"人力资源总监\"}");
        Candidate empty = candidate("{\"name\":\"张三\"}");

        assertEquals(scoringEngine.expectationFingerprint(a), scoringEngine.expectationFingerprint(b),
                "字段顺序不同但期望内容相同 → 指纹一致");
        assertNotEquals(scoringEngine.expectationFingerprint(a), scoringEngine.expectationFingerprint(changed),
                "期望内容变化 → 指纹不同");
        assertNotEquals(scoringEngine.expectationFingerprint(a), scoringEngine.expectationFingerprint(empty),
                "期望缺失与补齐 → 指纹不同");
    }

    // ---------- I3②:单候选独立事务,前序已提交结果不被后续致命异常回滚 ----------

    @Test
    void scoredCandidateCommitSurvivesFatalFailureOfLaterCandidate() {
        createNormalAccount();
        when(aiClient.chat(anyString(), anyString()))
                .thenReturn("{\"score\":80,\"pass\":true,\"summary\":\"ok\",\"reasons\":[\"a\"]}");
        Candidate earlier = candidate("{\"name\":\"甲\",\"want_title\":\"软件工程师\",\"resume_id\":\"res-a\"}");
        candidate("{\"name\":\"乙\",\"resume_id\":\"res-b\"}");
        when(commandService.resume(any(), eq("res-b"), any()))
                .thenThrow(new CliException(CliException.Type.RISK_CONTROL, "安全验证"));

        assertThrows(CliException.class, () -> scoringEngine.scorePending(jd.getId()));

        Candidate after = candidateMapper.selectById(earlier.getId());
        assertEquals("PASS", after.getPassStatus(), "前一位已提交结果不得被后续致命异常整批回滚");
        assertEquals(1, scoreRecordMapper.selectCount(new LambdaQueryWrapper<ScoreRecord>()
                .eq(ScoreRecord::getCandidateId, earlier.getId())));
    }
}
