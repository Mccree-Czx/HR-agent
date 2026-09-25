package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hragent.entity.Candidate;
import com.hragent.entity.GreetingRecord;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.ResumeFile;
import com.hragent.entity.ScoreRecord;
import com.hragent.executor.CliException;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.GreetingRecordMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.ResumeFileMapper;
import com.hragent.repository.ScoreRecordMapper;
import com.hragent.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * ChatPollService 来信轮询(被动通道)测试。
 *
 * <p>外部边界(commandService)全部 mock;候选/招呼/附件/评分记录用真实 mapper(H2)+ 真实
 * ResumeCollectService/StorageService,以验证「附件校验入库、去重、下轮重试」等落库语义。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChatPollServiceTest {

    private static final byte[] PDF = "%PDF-1.4\nmock resume body".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ChatPollService chatPollService;

    @Autowired
    private CandidateMapper candidateMapper;

    @Autowired
    private LiepinAccountMapper accountMapper;

    @Autowired
    private GreetingRecordMapper greetingMapper;

    @Autowired
    private ResumeFileMapper resumeFileMapper;

    @Autowired
    private ScoreRecordMapper scoreRecordMapper;

    @Autowired
    private JdMapper jdMapper;

    @Autowired
    private StorageService storageService;

    @MockitoBean
    private LiepinCommandService commandService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LiepinAccount account;

    @BeforeEach
    void setUp() {
        resumeFileMapper.delete(new LambdaQueryWrapper<>());
        greetingMapper.delete(new LambdaQueryWrapper<>());
        scoreRecordMapper.delete(new LambdaQueryWrapper<>());
        candidateMapper.delete(new LambdaQueryWrapper<>());
        accountMapper.delete(new LambdaQueryWrapper<>());
        jdMapper.delete(new LambdaQueryWrapper<>());

        account = new LiepinAccount();
        account.setName("测试账号");
        account.setLoginStatus("NORMAL");
        account.setCircuitBreaker(false);
        account.setGreetMode("AUTO");
        accountMapper.insert(account);
    }

    // ---------- 附件:下载成功 → 校验入库(不看分数/门槛) ----------

    @Test
    void attachmentDownloadedAndStoredEvenWithoutScoreOrThreshold() throws Exception {
        // 来源岗位未确认门槛 + 候选人评分 FAIL:附件仍应入库(设计 3.2「不受分数限制」)
        Jd jd = unconfirmedJd("招聘主管", "88888");
        Candidate candidate = knownCandidate("im1", "张三");
        candidate.setJdId(jd.getId());
        candidate.setPassStatus("FAIL");
        candidateMapper.updateById(candidate);
        greeting(candidate);

        stubChatlist("{\"im_id\":\"im1\",\"name\":\"张三\",\"direction\":\"1\"}");
        stubChatmsg("im1",
                "{\"message_id\":\"m1\",\"sender\":\"对方\",\"opposite_im_id\":\"im1\","
                        + "\"payload\":{\"bodies\":[{\"type\":\"file\",\"fileId\":\"f1\",\"filename\":\"简历.pdf\"}]}}");
        Path pdf = writePdf("resume.pdf");
        when(commandService.attachDownload(any(), eq("im1"), anyString(), any()))
                .thenReturn(Optional.of(downloadResult(pdf)));

        int processed = chatPollService.pollOnce(account);

        assertEquals(1, processed);
        List<ResumeFile> files = resumeFileMapper.selectList(new LambdaQueryWrapper<ResumeFile>()
                .eq(ResumeFile::getCandidateId, candidate.getId()));
        assertEquals(1, files.size(), "附件应入库");
        assertEquals("pdf", files.get(0).getFormat());
        assertEquals(PDF.length, files.get(0).getSize());
        assertTrue(storageService.exists(files.get(0).getObjectKey()), "附件应写入存储");
        verify(commandService).attachDownload(any(), eq("im1"), anyString(), any());
    }

    // ---------- 附件:下载失败 → 不入库、无半状态、下轮重试 ----------

    @Test
    void attachmentFailureLeavesNoHalfStateAndRetriesNextRound() throws Exception {
        Candidate candidate = knownCandidate("im1", "张三");
        greeting(candidate);

        stubChatlist("{\"im_id\":\"im1\",\"direction\":\"1\"}");
        stubChatmsg("im1",
                "{\"payload\":{\"bodies\":[{\"type\":\"file\",\"fileId\":\"f1\",\"filename\":\"简历.pdf\"}]}}");
        when(commandService.attachDownload(any(), eq("im1"), anyString(), any()))
                .thenThrow(new CliException(CliException.Type.FAILED, "浏览器下载已被取消"));

        int first = chatPollService.pollOnce(account);
        int second = chatPollService.pollOnce(account);

        assertEquals(0, first);
        assertEquals(0, second);
        assertEquals(0, resumeFileMapper.selectCount(new LambdaQueryWrapper<>()), "下载失败不得入库(无半状态)");
        verify(commandService, times(2)).attachDownload(any(), eq("im1"), anyString(), any());
    }

    // ---------- 文本回复:门槛未确认 → 不索要 ----------

    @Test
    void textReplyWithUnconfirmedThresholdDoesNotRequest() throws Exception {
        Jd jd = unconfirmedJd("未确认门槛岗位", "77777");
        Candidate candidate = knownCandidate("im1", "张三");
        candidate.setJdId(jd.getId());
        candidateMapper.updateById(candidate);
        GreetingRecord record = greeting(candidate);

        stubChatlist("{\"im_id\":\"im1\",\"user_id\":\"u1\",\"direction\":\"1\"}");
        stubChatmsg("im1", "{\"payload\":{\"bodies\":[{\"type\":\"txt\",\"msg\":\"您好\"}]}}");

        chatPollService.pollOnce(account);

        verify(commandService, never()).requestResume(any(), anyString(), any());
        assertEquals("SENT", greetingMapper.selectById(record.getId()).getStatus(), "未确认门槛不得改状态");
    }

    // ---------- 文本回复:门槛确认 + PASS → 索要 ----------

    @Test
    void textReplyPassConfirmedThresholdRequestsResume() throws Exception {
        Jd jd = confirmedJd("招聘主管", "88888");
        Candidate candidate = knownCandidate("im1", "张三");
        candidate.setJdId(jd.getId());
        candidateMapper.updateById(candidate);
        GreetingRecord record = greeting(candidate);

        stubChatlist("{\"im_id\":\"im1\",\"user_id\":\"u1\",\"direction\":\"1\"}");
        stubChatmsg("im1", "{\"payload\":{\"bodies\":[{\"type\":\"txt\",\"msg\":\"您好\"}]}}");
        when(commandService.requestResume(any(), eq("r-im1"), any()))
                .thenReturn(Optional.of(objectMapper.readTree("{\"success\":true,\"confirmed\":true}")));

        chatPollService.pollOnce(account);

        verify(commandService).requestResume(any(), eq("r-im1"), any());
        assertEquals("REQUESTED", greetingMapper.selectById(record.getId()).getStatus());
    }

    // ---------- 陌生来话:提取成功 + 可关联岗位 → 建候选人并走三态门禁 ----------

    @Test
    void strangerWithResumeCardAndJobCreatesCandidateWithJd() throws Exception {
        Jd jd = confirmedJd("招聘主管", "88888");
        stubChatlist("{\"im_id\":\"stranger1\",\"name\":\"李四\",\"direction\":\"1\"}");
        stubChatmsg("stranger1",
                "{\"message_id\":\"m1\",\"sender\":\"对方\",\"opposite_im_id\":\"stranger1\","
                        + "\"payload\":{\"bodies\":[{\"type\":\"resume\",\"enresId\":\"enres-1\",\"ejobId\":\"88888\"}]}}");

        int processed = chatPollService.pollOnce(account);

        assertEquals(1, processed);
        List<Candidate> created = candidateMapper.selectList(new LambdaQueryWrapper<>());
        assertEquals(1, created.size(), "陌生来话应建候选人(不猜身份:仅用卡片提取的标识)");
        Candidate c = created.get(0);
        assertEquals("enres-1", c.getResumeId());
        assertEquals("李四", c.getName());
        assertEquals(jd.getId(), c.getJdId(), "能确定岗位则关联");
        assertEquals("PENDING", c.getPassStatus(), "无快照期望证据 → 待确认,绝不 PASS");
        assertEquals(1, scoreRecordMapper.selectCount(new LambdaQueryWrapper<ScoreRecord>()
                .eq(ScoreRecord::getCandidateId, c.getId())), "有关联岗位才走评分门禁");
    }

    // ---------- 陌生来话:提取成功但无法关联岗位 → 待分配,不评分 ----------

    @Test
    void strangerWithResumeCardWithoutJobCreatesUnassignedCandidate() throws Exception {
        stubChatlist("{\"im_id\":\"stranger2\",\"name\":\"王五\",\"direction\":\"1\"}");
        stubChatmsg("stranger2",
                "{\"payload\":{\"bodies\":[{\"type\":\"resume\",\"enresId\":\"enres-2\"}]}}");

        chatPollService.pollOnce(account);

        List<Candidate> created = candidateMapper.selectList(new LambdaQueryWrapper<>());
        assertEquals(1, created.size());
        assertEquals("enres-2", created.get(0).getResumeId());
        assertNull(created.get(0).getJdId(), "无关联岗位 → 待分配(jdId 为空)");
        assertEquals(0, scoreRecordMapper.selectCount(new LambdaQueryWrapper<>()), "无岗位不评分");
        verify(commandService, never()).requestResume(any(), anyString(), any());
    }

    // ---------- 陌生来话:提取失败 → 不建/不猜/不评分 ----------

    @Test
    void strangerWithoutResumeCardDoesNotCreateOrScore() throws Exception {
        stubChatlist("{\"im_id\":\"stranger3\",\"name\":\"赵六\",\"direction\":\"1\"}");
        stubChatmsg("stranger3", "{\"payload\":{\"bodies\":[{\"type\":\"txt\",\"msg\":\"你好\"}]}}");

        int processed = chatPollService.pollOnce(account);

        assertEquals(0, processed);
        assertEquals(0, candidateMapper.selectCount(new LambdaQueryWrapper<>()), "提取失败不猜测,不建候选人");
        assertEquals(0, scoreRecordMapper.selectCount(new LambdaQueryWrapper<>()), "不调用评分");
        verify(commandService, never()).requestResume(any(), anyString(), any());
    }

    // ---------- 风控异常 → 上抛中断本轮 ----------

    @Test
    void riskControlPropagatesToStopRound() throws Exception {
        Candidate candidate = knownCandidate("im1", "张三");
        greeting(candidate);

        stubChatlist("{\"im_id\":\"im1\",\"direction\":\"1\"}");
        stubChatmsg("im1",
                "{\"payload\":{\"bodies\":[{\"type\":\"file\",\"fileId\":\"f1\",\"filename\":\"简历.pdf\"}]}}");
        when(commandService.attachDownload(any(), eq("im1"), anyString(), any()))
                .thenThrow(new CliException(CliException.Type.RISK_CONTROL, "安全验证"));

        assertThrows(CliException.class, () -> chatPollService.pollOnce(account));
    }

    // ---------- 会话列表拉取瞬断 → 跳过来信但不中断本轮(非风控) ----------

    @Test
    void chatlistTransientFailureSkipsInboundWithoutBreakingRound() throws Exception {
        when(commandService.chatlist(any(), any()))
                .thenThrow(new CliException(CliException.Type.FAILED, "liepin-cli 执行超时"));

        int processed = chatPollService.pollOnce(account);

        assertEquals(0, processed);
        verify(commandService, never()).chatmsg(any(), anyString(), any());
    }

    // ---------- 单会话失败不中断其余会话 ----------

    @Test
    void singleSessionFailureDoesNotBlockOthers() throws Exception {
        Candidate a = knownCandidate("imA", "甲");
        greeting(a);
        Candidate b = knownCandidate("imB", "乙");
        greeting(b);

        when(commandService.chatlist(any(), any())).thenReturn(List.of(
                objectMapper.readTree("{\"im_id\":\"imA\",\"direction\":\"1\"}"),
                objectMapper.readTree("{\"im_id\":\"imB\",\"direction\":\"1\"}")));
        when(commandService.chatmsg(any(), eq("imA"), any()))
                .thenThrow(new RuntimeException("会话 A 读取失败"));
        stubChatmsg("imB",
                "{\"payload\":{\"bodies\":[{\"type\":\"file\",\"fileId\":\"fB\",\"filename\":\"乙.pdf\"}]}}");
        Path pdf = writePdf("b.pdf");
        when(commandService.attachDownload(any(), eq("imB"), anyString(), any()))
                .thenReturn(Optional.of(downloadResult(pdf)));

        int processed = chatPollService.pollOnce(account);

        assertEquals(1, processed, "A 失败不阻断 B");
        assertEquals(1, resumeFileMapper.selectCount(new LambdaQueryWrapper<ResumeFile>()
                .eq(ResumeFile::getCandidateId, b.getId())), "B 的附件仍应入库");
        verify(commandService, never()).attachDownload(any(), eq("imA"), anyString(), any());
    }

    // ---------- 辅助 ----------

    private void stubChatlist(String... sessions) throws Exception {
        List<JsonNode> list = new java.util.ArrayList<>();
        for (String session : sessions) {
            list.add(objectMapper.readTree(session));
        }
        when(commandService.chatlist(any(), any())).thenReturn(list);
    }

    private void stubChatmsg(String imId, String... messages) throws Exception {
        List<JsonNode> list = new java.util.ArrayList<>();
        for (String message : messages) {
            list.add(objectMapper.readTree(message));
        }
        when(commandService.chatmsg(any(), eq(imId), any())).thenReturn(list);
    }

    private Candidate knownCandidate(String imId, String name) {
        Candidate c = new Candidate();
        c.setResumeId("r-" + imId);
        c.setName(name);
        c.setSnapshot("{\"im_id\":\"" + imId + "\",\"name\":\"" + name + "\"}");
        c.setPassStatus("PASS");
        candidateMapper.insert(c);
        return c;
    }

    private GreetingRecord greeting(Candidate candidate) {
        GreetingRecord record = new GreetingRecord();
        record.setCandidateId(candidate.getId());
        record.setAccountId(account.getId());
        record.setStatus("SENT");
        record.setMode("AUTO");
        greetingMapper.insert(record);
        return record;
    }

    private Jd confirmedJd(String title, String liepinJobId) {
        Jd jd = baseJd(title, liepinJobId);
        jd.setScoreThreshold(60);
        jd.setThresholdConfirmedAt(LocalDateTime.now());
        jdMapper.insert(jd);
        return jd;
    }

    private Jd unconfirmedJd(String title, String liepinJobId) {
        Jd jd = baseJd(title, liepinJobId);
        jdMapper.insert(jd);
        return jd;
    }

    private Jd baseJd(String title, String liepinJobId) {
        Jd jd = new Jd();
        jd.setTitle(title);
        jd.setLiepinJobId(liepinJobId);
        jd.setStatus("ACTIVE");
        return jd;
    }

    private Path writePdf(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, PDF);
        return path;
    }

    private JsonNode downloadResult(Path pdf) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("success", true);
        node.put("file", pdf.toAbsolutePath().toString());
        node.put("bytes", PDF.length);
        node.put("sha256", "deadbeef");
        node.put("sourceOrigin", "https://tdoss.liepin.com");
        return node;
    }
}
