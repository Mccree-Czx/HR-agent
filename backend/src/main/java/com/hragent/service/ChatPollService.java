package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hragent.config.HrAgentProperties;
import com.hragent.entity.Candidate;
import com.hragent.entity.GreetingRecord;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.ResumeFile;
import com.hragent.executor.CliException;
import com.hragent.executor.JsonExtractor;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.GreetingRecordMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.ResumeFileMapper;
import com.hragent.scoring.ScoringEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 来信轮询与附件收集入口(被动通道,设计 §3.2)。
 *
 * <p>每轮由 {@code AutoRecruitScheduler} 在来信阶段调用一次;本轮内单账号串行,对每个会话:
 * <ol>
 *     <li>chatlist 拉会话(每会话带 {@code im_id}/{@code direction}/{@code name});仅处理
 *         {@code direction=1}(候选人最后发言)的会话——无新来信则既无附件也无回复。</li>
 *     <li><b>已知候选人</b>(候选人在 snapshot 中带有该 im_id):
 *         <ul>
 *             <li>最新消息含附件卡片(payload.bodies 含 {@code file}/{@code fileId})且
 *                 {@code resume_file} 无记录 → attach-download 下载到工作目录 → 校验返回 success →
 *                 {@code saveResumeFile} 入库(<b>不看分数/门槛</b>);下载或入库失败只记日志、不写半状态,
 *                 下轮自动重试(以「resume_file 是否已有该候选人记录」判重,不用内容哈希)。</li>
 *             <li>仅文本回复 → 门槛已确认 + 评分 PASS + 未 REQUESTED → 复用既有索要路径。</li>
 *         </ul></li>
 *     <li><b>陌生来话</b>(im_id 未匹配任何候选人):chatmsg 提取在线简历卡片的 {@code enresId};
 *         成功 → 建候选人(能由消息 job 字段映射到岗位则关联,否则 jd_id 为空=待分配)并走评分门禁;
 *         提取失败 → 只记日志,<b>不建、不猜、不评分</b>。</li>
 * </ol>
 *
 * <p>容错:单会话失败不中断其余会话;风控({@link CliException.Type#RISK_CONTROL})与登录失效
 * ({@link CliException.Type#NOT_LOGGED_IN})立即中断本轮并上抛,交由既有熔断链路处理。
 * 外发动作(附件下载、索要简历)与打招呼统一由 {@link AccountPaceGuard} 按账号维度节流(评审 I-2)。
 */
@Slf4j
@Service
public class ChatPollService {

    /** 附件临时下载目录(CLI 要求绝对路径;相对路径按 JVM 工作目录解析) */
    private static final String ATTACH_WORK_DIR = "runtime/attach-poll";
    /** 在线简历卡的简历标识字段 */
    private static final String RESUME_ID_FIELD = "enresId";
    /** 消息中关联岗位的候选字段(按序取首个非空) */
    private static final List<String> JOB_ID_FIELDS = List.of("ejobId", "jobId", "ejob_id");
    /** chatmsg 发送方标识:对方发来(我方=「我」;无法判定=「未知」,不猜) */
    private static final String SENDER_OTHER = "对方";
    /** 陌生来话占位 resume_id 前缀(明确占位,不冒充真实简历 ID) */
    private static final String PLACEHOLDER_RESUME_ID_PREFIX = "im:";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LiepinCommandService commandService;
    private final ResumeCollectService resumeCollectService;
    private final ScoringEngine scoringEngine;
    private final CandidateMapper candidateMapper;
    private final GreetingRecordMapper greetingMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final JdMapper jdMapper;
    private final LiepinAccountMapper accountMapper;
    private final HrAgentProperties properties;
    private final AccountPaceGuard paceGuard;

    public ChatPollService(LiepinCommandService commandService, ResumeCollectService resumeCollectService,
                           ScoringEngine scoringEngine, CandidateMapper candidateMapper,
                           GreetingRecordMapper greetingMapper, ResumeFileMapper resumeFileMapper,
                           JdMapper jdMapper, LiepinAccountMapper accountMapper,
                           HrAgentProperties properties, AccountPaceGuard paceGuard) {
        this.commandService = commandService;
        this.resumeCollectService = resumeCollectService;
        this.scoringEngine = scoringEngine;
        this.candidateMapper = candidateMapper;
        this.greetingMapper = greetingMapper;
        this.resumeFileMapper = resumeFileMapper;
        this.jdMapper = jdMapper;
        this.accountMapper = accountMapper;
        this.properties = properties;
        this.paceGuard = paceGuard;
    }

    /**
     * 轮询来信与附件(每轮总入口,内部单账号串行)。
     */
    public void poll() {
        LiepinAccount account = accountMapper.selectOne(new LambdaQueryWrapper<LiepinAccount>()
                .eq(LiepinAccount::getLoginStatus, "NORMAL")
                .orderByAsc(LiepinAccount::getId)
                .last("LIMIT 1"));
        if (account == null) {
            log.warn("来信轮询:无可用猎聘账号(login_status=NORMAL),本轮跳过");
            return;
        }
        int processed = pollOnce(account);
        log.info("来信轮询完成:账号 {},处理 {} 个会话", account.getId(), processed);
    }

    /**
     * 对指定账号执行一次来信轮询;返回本轮产生动作(附件入库/索要/建候选人)的会话数。
     */
    public int pollOnce(LiepinAccount account) {
        Duration timeout = Duration.ofMinutes(properties.getLiepin().getShortTimeoutMinutes());
        List<JsonNode> sessions;
        try {
            sessions = commandService.chatlist(account, timeout);
        } catch (CliException e) {
            // 风控/登录失效 → 中断本轮上抛;其余拉取失败只记日志,不阻断后续岗位处理
            if (e.getType() == CliException.Type.RISK_CONTROL
                    || e.getType() == CliException.Type.NOT_LOGGED_IN) {
                throw e;
            }
            log.warn("来信会话列表拉取失败,本轮跳过来信处理: {}", e.getMessage());
            return 0;
        }
        int processed = 0;
        for (JsonNode session : sessions) {
            try {
                if (handleSession(account, session, timeout)) {
                    processed++;
                }
            } catch (CliException e) {
                if (e.getType() == CliException.Type.RISK_CONTROL
                        || e.getType() == CliException.Type.NOT_LOGGED_IN) {
                    log.error("来信轮询遇账号级异常,立即停止本轮: {}", e.getMessage());
                    throw e;
                }
                log.warn("会话处理失败,跳过: {}", e.getMessage());
            } catch (Exception e) {
                // 单会话失败不中断其余会话
                log.warn("会话处理异常,跳过: {}", e.getMessage(), e);
            }
        }
        return processed;
    }

    /** 处理单个会话;返回是否已产生落库/外发动作 */
    private boolean handleSession(LiepinAccount account, JsonNode session, Duration timeout) {
        String imId = session.path("im_id").asText("").trim();
        if (imId.isEmpty()) {
            log.warn("会话缺少 im_id,跳过(不猜测)");
            return false;
        }
        String direction = session.path("direction").asText("");
        // 已知候选人匹配:优先 im_id,失败时回退 user_id(终审 I2:snapshot 缺 im_id 时不致断链)
        Candidate candidate = findCandidateByImId(imId);
        if (candidate == null) {
            String userId = session.path("user_id").asText("").trim();
            if (!userId.isEmpty()) {
                candidate = findCandidateByUserId(userId);
            }
        }
        if (candidate != null) {
            return handleKnownCandidate(account, candidate, imId, direction, timeout);
        }
        return handleStranger(account, session, imId, direction, timeout);
    }

    /** 已知候选人:附件优先入库(不看分数);否则文本回复按门槛+评分索要 */
    private boolean handleKnownCandidate(LiepinAccount account, Candidate candidate, String imId,
                                         String direction, Duration timeout) {
        // 仅处理「候选人最后发言」的会话,避免无谓拉消息
        if (!"1".equals(direction)) {
            return false;
        }
        // 去重键:resume_file 是否已有该候选人记录(服务端每次下载会重新生成 PDF,SHA-256 会变)
        if (hasResumeFile(candidate.getId())) {
            return false;
        }
        List<JsonNode> messages = commandService.chatmsg(account, imId, timeout);
        JsonNode attachment = findLatestAttachment(messages);
        if (attachment != null) {
            return downloadAndStore(account, candidate, imId, attachment, timeout);
        }
        return requestResumeForKnown(account, candidate, timeout);
    }

    /** 附件下载 → 校验 → 入库;任一步失败都只记日志、不写半状态(下轮重试) */
    private boolean downloadAndStore(LiepinAccount account, Candidate candidate, String imId,
                                     JsonNode attachment, Duration timeout) {
        if (hasResumeFile(candidate.getId())) {
            return false;
        }
        String fileName = attachment.path("filename").asText("").trim();
        if (fileName.isEmpty()) {
            fileName = "resume.pdf";
        }
        paceGuard.await(account);
        Optional<JsonNode> result = commandService.attachDownload(account, imId, attachWorkDir(), timeout);
        JsonNode node = result.orElse(null);
        if (node == null || !node.path("success").asBoolean(false)) {
            log.warn("附件下载未成功,留待下轮重试(候选人 {});不写半状态", candidate.getId());
            return false;
        }
        String filePath = node.path("file").asText("").trim();
        byte[] content;
        try {
            content = Files.readAllBytes(Paths.get(filePath));
        } catch (IOException | RuntimeException e) {
            log.warn("附件读取失败,留待下轮重试(候选人 {}): {}", candidate.getId(), e.getMessage());
            return false;
        }
        if (content.length == 0) {
            log.warn("附件内容为空,拒绝入库(候选人 {}),留待下轮重试", candidate.getId());
            return false;
        }
        try {
            resumeCollectService.saveResumeFile(candidate.getId(), fileName, content, "application/pdf");
        } catch (Exception e) {
            log.warn("附件入库失败,留待下轮重试(候选人 {}): {}", candidate.getId(), e.getMessage());
            return false;
        }
        deleteQuietly(filePath);
        paceGuard.mark(account);
        log.info("候选人 {} 附件已校验入库({} 字节, sha256={})",
                candidate.getId(), content.length, node.path("sha256").asText(""));
        return true;
    }

    /** 已知候选人仅文本回复:门槛已确认 + 评分 PASS + 未 REQUESTED → 复用既有索要路径 */
    private boolean requestResumeForKnown(LiepinAccount account, Candidate candidate, Duration timeout) {
        if (!"PASS".equals(candidate.getPassStatus())) {
            log.debug("候选人 {} 非 PASS({}),不索要", candidate.getId(), candidate.getPassStatus());
            return false;
        }
        GreetingRecord record = greetingMapper.selectOne(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getCandidateId, candidate.getId())
                .last("LIMIT 1"));
        if (record == null) {
            log.warn("候选人 {} 无打招呼记录,跳过索要", candidate.getId());
            return false;
        }
        if ("REQUESTED".equals(record.getStatus()) || "AGREED".equals(record.getStatus())) {
            return false;
        }
        // 门槛门禁(fail-closed):来源岗位未确认门槛 → 绝不外发(与既有索要路径一致)
        if (!thresholdConfirmed(candidate)) {
            log.warn("岗位未确认门槛,跳过索要简历(候选人 {})", candidate.getId());
            return false;
        }
        paceGuard.await(account);
        // 复用既有索要路径:内含门槛门禁 + resume_id 校验 + 回复复核 + 状态回写
        resumeCollectService.collectOne(record, candidate);
        boolean requested = "REQUESTED".equals(record.getStatus());
        if (requested) {
            paceGuard.mark(account);
        }
        return requested;
    }

    /**
     * 陌生来话:提取在线简历标识;成功建候选人(可关联岗位则走评分门禁);
     * 提取失败但检测到对方附件卡片 → 以占位载体入库并标记待分配(评审 I-3);两者皆无 → 只记日志。
     */
    private boolean handleStranger(LiepinAccount account, JsonNode session, String imId,
                                   String direction, Duration timeout) {
        if (!"1".equals(direction)) {
            return false;
        }
        List<JsonNode> messages = commandService.chatmsg(account, imId, timeout);
        JsonNode card = findLatestResumeCard(messages);
        String resumeId = card == null ? null : findText(card, RESUME_ID_FIELD);
        if (resumeId == null || resumeId.isBlank()) {
            // 无身份标识:若对方发来附件卡片则以占位载体入库待分配,否则不建/不猜
            return storeStrangerAttachment(account, session, imId, messages, timeout);
        }
        String sessionName = session.path("name").asText("").trim();
        Long jdId = resolveJdId(card);
        Candidate candidate = new Candidate();
        candidate.setResumeId(resumeId);
        candidate.setName(sessionName.isEmpty() ? resumeId : sessionName);
        candidate.setSnapshot(buildStrangerSnapshot(imId, sessionName, card));
        candidate.setJdId(jdId);
        candidate.setPassStatus("PENDING");
        candidateMapper.insert(candidate);
        log.info("陌生来话 {} 已建候选人(id={}, resume_id={}, jd_id={})",
                imId, candidate.getId(), resumeId, jdId);
        if (jdId == null) {
            log.info("陌生来话 {} 无关联岗位,标记待分配(待人工分配)", imId);
            return true;
        }
        // 有关联岗位 → 走评分门禁(期望职能三态 + 门槛);无快照期望证据时恒 UNKNOWN→PENDING,绝不外发
        scoringEngine.scoreAndSave(candidate.getId());
        maybeRequestAfterStrangerScore(account, candidate.getId(), timeout);
        return true;
    }

    /**
     * 陌生来话无法提取身份标识时的兜底(评审 I-3):若对方发来附件卡片,则以占位载体入库并标记待分配。
     * 占位 {@code resume_id} 为 {@code im:<imId>}(明确占位前缀,不冒充真实简历 ID);仅入库附件,
     * 不评分、不索要;同一 im 已有候选人(预查 resume_id)则复用其 id,不重复建(规避 uk_resume 冲突)。
     */
    private boolean storeStrangerAttachment(LiepinAccount account, JsonNode session, String imId,
                                            List<JsonNode> messages, Duration timeout) {
        JsonNode attachment = findLatestAttachment(messages);
        if (attachment == null) {
            log.warn("陌生来话({})未能从消息提取简历标识且无对方附件卡片,不建/不猜,留待人工分配", imId);
            return false;
        }
        String placeholderResumeId = PLACEHOLDER_RESUME_ID_PREFIX + imId;
        Candidate candidate = findCandidateByResumeId(placeholderResumeId);
        if (candidate == null) {
            candidate = createPlaceholderCandidate(session, imId, placeholderResumeId);
        }
        if (hasResumeFile(candidate.getId())) {
            return false;
        }
        return downloadAndStore(account, candidate, imId, attachment, timeout);
    }

    /** 建陌生来话占位候选人:名称取 chatlist 显示名,取不到则「待分配-<imId前8位>」;jd_id=null、PENDING */
    private Candidate createPlaceholderCandidate(JsonNode session, String imId, String placeholderResumeId) {
        String sessionName = session.path("name").asText("").trim();
        String name = sessionName.isEmpty() ? "待分配-" + imIdPrefix(imId) : sessionName;
        Candidate candidate = new Candidate();
        candidate.setResumeId(placeholderResumeId);
        candidate.setName(name);
        candidate.setSnapshot(buildStrangerSnapshot(imId, sessionName, null));
        candidate.setJdId(null);
        candidate.setPassStatus("PENDING");
        candidateMapper.insert(candidate);
        log.info("陌生来话 {} 无身份标识但有附件,已建占位候选人(id={}, resume_id={}),标记待分配,不评分不索要",
                imId, candidate.getId(), placeholderResumeId);
        return candidate;
    }

    /** 陌生来话评分后:仅 PASS 且门槛已确认才索要(否则保持待处理) */
    private void maybeRequestAfterStrangerScore(LiepinAccount account, Long candidateId, Duration timeout) {
        Candidate fresh = candidateMapper.selectById(candidateId);
        if (fresh == null || !"PASS".equals(fresh.getPassStatus())) {
            return;
        }
        if (!thresholdConfirmed(fresh)) {
            log.warn("陌生来话候选人 {} 岗位未确认门槛,不索要", candidateId);
            return;
        }
        String resumeId = fresh.getResumeId();
        if (resumeId == null || resumeId.isBlank()) {
            return;
        }
        paceGuard.await(account);
        Optional<JsonNode> result = commandService.requestResume(account, resumeId, timeout);
        boolean confirmed = result.filter(node -> node.path("success").asBoolean(false)
                && node.path("confirmed").asBoolean(false)).isPresent();
        if (confirmed) {
            paceGuard.mark(account);
            log.info("陌生来话候选人 {} 评分通过,已索要简历", candidateId);
        } else {
            log.warn("陌生来话候选人 {} 索要未获确认", candidateId);
        }
    }

    /** 以 snapshot 中的 im_id 匹配已知候选人(先 LIKE 命中再精确比对,避免前缀误匹配) */
    private Candidate findCandidateByImId(String imId) {
        String needle = "\"im_id\":\"" + imId + "\"";
        List<Candidate> matches = candidateMapper.selectList(new LambdaQueryWrapper<Candidate>()
                .like(Candidate::getSnapshot, needle));
        for (Candidate candidate : matches) {
            JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
            if (snapshot != null && imId.equals(snapshot.path("im_id").asText(""))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 以 snapshot 中的 user_id(推荐输出 enusercId)匹配已知候选人(终审 I2 回退路径)。
     * im_id 缺失/不一致时兜底,降低已招呼候选人回复落入陌生来话造成断链的风险;
     * 仍匹配不到则交回陌生来话路径(fail-safe 不变)。
     */
    private Candidate findCandidateByUserId(String userId) {
        String needle = "\"user_id\":\"" + userId + "\"";
        List<Candidate> matches = candidateMapper.selectList(new LambdaQueryWrapper<Candidate>()
                .like(Candidate::getSnapshot, needle));
        for (Candidate candidate : matches) {
            JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
            if (snapshot != null && userId.equals(snapshot.path("user_id").asText(""))) {
                return candidate;
            }
        }
        return null;
    }

    /** 以 resume_id 精确匹配候选人(陌生来话占位载体预查,规避 uk_resume 冲突) */
    private Candidate findCandidateByResumeId(String resumeId) {
        return candidateMapper.selectOne(new LambdaQueryWrapper<Candidate>()
                .eq(Candidate::getResumeId, resumeId)
                .last("LIMIT 1"));
    }

    private boolean hasResumeFile(Long candidateId) {
        Long count = resumeFileMapper.selectCount(new LambdaQueryWrapper<ResumeFile>()
                .eq(ResumeFile::getCandidateId, candidateId));
        return count != null && count > 0;
    }

    private boolean thresholdConfirmed(Candidate candidate) {
        if (candidate.getJdId() == null) {
            return false;
        }
        Jd jd = jdMapper.selectById(candidate.getJdId());
        return jd != null && jd.getThresholdConfirmedAt() != null;
    }

    /** 由消息中的 job 字段映射到系统岗位;无匹配返回 null(待分配) */
    private Long resolveJdId(JsonNode card) {
        if (card == null) {
            return null;
        }
        for (String field : JOB_ID_FIELDS) {
            String jobId = findText(card, field);
            if (jobId == null || jobId.isBlank()) {
                continue;
            }
            List<Jd> matched = jdMapper.selectList(new LambdaQueryWrapper<Jd>()
                    .eq(Jd::getLiepinJobId, jobId)
                    .orderByAsc(Jd::getId));
            if (!matched.isEmpty()) {
                return matched.get(0).getId();
            }
        }
        return null;
    }

    /**
     * 最新一条「对方」发出且含附件卡片的消息 body(type=file 或含 fileId)。
     * 仅扫描 sender=对方 的消息(评审 I-1):我方发出的附件不算候选人简历;
     * sender 为空/「未知」同样不得作为附件来源(不猜)。
     */
    private static JsonNode findLatestAttachment(List<JsonNode> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode message = messages.get(i);
            if (!SENDER_OTHER.equals(message.path("sender").asText(""))) {
                continue;
            }
            JsonNode bodies = message.path("payload").path("bodies");
            if (!bodies.isArray()) {
                continue;
            }
            for (JsonNode body : bodies) {
                if (isAttachmentBody(body)) {
                    return body;
                }
            }
        }
        return null;
    }

    private static boolean isAttachmentBody(JsonNode body) {
        if (body == null || !body.isObject()) {
            return false;
        }
        if ("file".equalsIgnoreCase(body.path("type").asText(""))) {
            return true;
        }
        return body.hasNonNull("fileId") || body.hasNonNull("fileid") || body.hasNonNull("file_id");
    }

    /** 最新一条含「在线简历」标识(enresId)的消息载荷 */
    private static JsonNode findLatestResumeCard(List<JsonNode> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonNode payload = messages.get(i).path("payload");
            if (payload.isObject() && findText(payload, RESUME_ID_FIELD) != null) {
                return payload;
            }
        }
        return null;
    }

    /** 深度优先查找首个非空字段值(兼容字符串/数字),找不到返回 null */
    private static String findText(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode value = node.get(field);
            if (value != null) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText().trim();
                }
                if (value.isNumber()) {
                    return value.asText();
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                String found = findText(fields.next().getValue(), field);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String found = findText(item, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 陌生来话快照:必须带 im_id,下轮才能作为「已知候选人」被匹配 */
    private static String buildStrangerSnapshot(String imId, String name, JsonNode card) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("im_id", imId);
        if (!name.isEmpty()) {
            snapshot.put("name", name);
        }
        snapshot.put("source", "chat_poll");
        if (card != null) {
            snapshot.set("resume_card", card);
        }
        return snapshot.toString();
    }

    private static String attachWorkDir() {
        return Paths.get(ATTACH_WORK_DIR).toAbsolutePath().normalize().toString();
    }

    /** imId 前 8 位(占位候选人名称兜底用) */
    private static String imIdPrefix(String imId) {
        return imId.length() <= 8 ? imId : imId.substring(0, 8);
    }

    private static void deleteQuietly(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(filePath);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.debug("附件临时文件清理失败: {}", e.getMessage());
        }
    }
}
