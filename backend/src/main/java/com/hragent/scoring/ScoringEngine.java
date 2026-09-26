package com.hragent.scoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hragent.ai.AiClient;
import com.hragent.common.BizException;
import com.hragent.config.HrAgentProperties;
import com.hragent.entity.Candidate;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.ScoreRecord;
import com.hragent.executor.CliException;
import com.hragent.executor.JsonExtractor;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.ScoreRecordMapper;
import com.hragent.service.LiepinCommandService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评分引擎(可插拔):
 * 0. 期望职能三态门禁:不匹配直接 FAIL / 待确认置 PENDING(均不调模型,省 token 且 fail-closed)
 * 1. 规则预筛:硬性过滤(如薪资远超预算)直接 FAIL,不调模型省 token(评审 P2-11)
 * 2. 评分 Agent:系统提示词(agents/resume-scorer.md,细则占位)+ JD/候选人快照 → 结构化 JSON
 * 3. 解析 + 字段校验 + 失败重试(评审 P2-11)
 */
@Slf4j
@Service
public class ScoringEngine {

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    /** 在线简历详情输出的期望字段(合并进 snapshot 供三态判定) */
    private static final Set<String> EXPECTATION_FIELDS =
            Set.of("want_title", "expectation_evidence", "expectation_match");

    /** 职能待确认结论标识(score_record.reason 命中且期望数据指纹变化时允许重评一次) */
    private static final String PENDING_CONCLUSION_MARK = "职能待确认";

    /**
     * 「职能待确认」结论中携带的期望数据指纹标记(终审 I3①)。
     * 写库形如 {@code ... 职能待确认(fingerprint:<hash>)};解析容错:reason 被人工/其他格式覆盖时未命中返回 null。
     */
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("fingerprint:([0-9a-f]+)");

    /** 期望指纹长度(SHA-256 十六进制前缀:足够抗碰撞且便于阅读) */
    private static final int FINGERPRINT_LENGTH = 16;

    private final AiClient aiClient;
    private final HrAgentProperties properties;
    private final ScoreRecordMapper scoreRecordMapper;
    private final CandidateMapper candidateMapper;
    private final JdMapper jdMapper;
    private final ResourceLoader resourceLoader;
    private final LiepinAccountMapper accountMapper;
    private final LiepinCommandService commandService;
    private final CandidateScoringExecutor scoringExecutor;

    public ScoringEngine(AiClient aiClient, HrAgentProperties properties,
                         ScoreRecordMapper scoreRecordMapper, CandidateMapper candidateMapper,
                         JdMapper jdMapper, ResourceLoader resourceLoader,
                         LiepinAccountMapper accountMapper, LiepinCommandService commandService,
                         @Lazy CandidateScoringExecutor scoringExecutor) {
        this.aiClient = aiClient;
        this.properties = properties;
        this.scoreRecordMapper = scoreRecordMapper;
        this.candidateMapper = candidateMapper;
        this.jdMapper = jdMapper;
        this.resourceLoader = resourceLoader;
        this.accountMapper = accountMapper;
        this.commandService = commandService;
        this.scoringExecutor = scoringExecutor;
    }

    /** 对指定候选人评分并落库(返回评分记录) */
    @Transactional
    public ScoreRecord scoreAndSave(Long candidateId) {
        Candidate candidate = candidateMapper.selectById(candidateId);
        if (candidate == null) {
            throw BizException.notFound("候选人不存在");
        }
        Jd jd = jdMapper.selectById(candidate.getJdId());
        if (jd == null) {
            throw BizException.badRequest("候选人未关联岗位,无法评分");
        }
        ScoreResult result = score(jd, candidate);

        ScoreRecord record = new ScoreRecord();
        record.setCandidateId(candidateId);
        record.setJdId(jd.getId());
        record.setScore(result.score());
        String reason = result.summary() + " | " + String.join("; ", result.reasons());
        if (result.pending()) {
            // 终审 I3① 修复:在「职能待确认」结论里固化当时的期望数据指纹,作为后续基于内容变化的重评判据
            reason = reason + " | " + pendingMarker(candidate);
        }
        record.setReason(reason);
        record.setRuleVersion(properties.getScoring().getRuleVersion());
        record.setModel(properties.getAi().getModel());
        scoreRecordMapper.insert(record);

        candidate.setScore(result.score());
        candidate.setPassStatus(result.pending() ? "PENDING" : (result.pass() ? "PASS" : "FAIL"));
        // 「职能待确认」不触碰候选人行:pass_status 仍为 PENDING、score 保持原值;
        // 关键作用(终审 I3)是让 candidate.updated_at 只反映「快照变更」,作为重评依据而不被评分自身自触发。
        if (!result.pending()) {
            candidateMapper.updateById(candidate);
        }
        return record;
    }

    /**
     * 批量补评分:遍历该岗位下 pass_status=PENDING 的候选人补齐评分(设计 §3.1(3)、终审 C1/I3)。
     *
     * <p>流程:
     * <ol>
     *     <li>缺期望证据(want_title 与 expectation_evidence 均缺)者,先读取在线简历详情
     *         (只读平台调用,单轮上限 {@code resume-detail-batch-limit},相邻读取间隔
     *         {@code resume-detail-interval-millis}),成功且含期望字段则合并写回 snapshot;</li>
     *     <li>合并后进入既有三态校验 + 评分;读取失败/无期望字段 → 保持 UNKNOWN(不猜),且不中断整批
     *         (风控类账号级异常仍上抛);</li>
     *     <li>已有「职能待确认」结论者,仅当候选人期望数据内容指纹发生变化(不再依赖 updated_at)时重评一次;
     *         其余已有记录者跳过(避免重复消耗 token 死循环);</li>
     *     <li>单候选人经独立 bean 的 REQUIRES_NEW 事务落库,单人异常不回滚他人。</li>
     * </ol>
     * 返回本轮成功评分的候选人数量。
     */
    public int scorePending(Long jdId) {
        List<Candidate> pending = candidateMapper.selectList(new LambdaQueryWrapper<Candidate>()
                .eq(Candidate::getJdId, jdId)
                .eq(Candidate::getPassStatus, "PENDING")
                .orderByAsc(Candidate::getId));
        if (pending.isEmpty()) {
            return 0;
        }
        LiepinAccount account = firstNormalAccount();
        int detailLimit = Math.max(0, properties.getAutoRecruit().getResumeDetailBatchLimit());
        long detailIntervalMillis = Math.max(0, properties.getAutoRecruit().getResumeDetailIntervalMillis());
        int detailFetches = 0;
        int scored = 0;
        for (Candidate candidate : pending) {
            try {
                ScoreRecord existing = latestRecord(candidate.getId(), jdId);
                if (existing != null && !shouldReevaluate(candidate, existing)) {
                    continue;
                }
                if (account != null && detailFetches < detailLimit && needsResumeDetail(candidate)) {
                    if (detailFetches > 0 && detailIntervalMillis > 0) {
                        sleep(detailIntervalMillis);
                    }
                    detailFetches++;
                    enrichFromResumeDetail(account, candidate);
                }
                scoringExecutor.scoreInNewTransaction(candidate.getId());
                scored++;
            } catch (CliException e) {
                if (e.getType() == CliException.Type.RISK_CONTROL) {
                    throw e;
                }
                log.warn("候选人 {} 补评分失败,跳过: {}", candidate.getId(), e.getMessage());
            } catch (Exception e) {
                log.warn("候选人 {} 补评分异常,跳过: {}", candidate.getId(), e.getMessage());
            }
        }
        return scored;
    }

    /** 取第一个 NORMAL 账号(与既有调度/打招呼口径一致) */
    private LiepinAccount firstNormalAccount() {
        return accountMapper.selectOne(new LambdaQueryWrapper<LiepinAccount>()
                .eq(LiepinAccount::getLoginStatus, "NORMAL")
                .orderByAsc(LiepinAccount::getId)
                .last("LIMIT 1"));
    }

    /** 最近一条 (candidate, jd) 评分记录 */
    private ScoreRecord latestRecord(Long candidateId, Long jdId) {
        return scoreRecordMapper.selectOne(new LambdaQueryWrapper<ScoreRecord>()
                .eq(ScoreRecord::getCandidateId, candidateId)
                .eq(ScoreRecord::getJdId, jdId)
                .orderByDesc(ScoreRecord::getId)
                .last("LIMIT 1"));
    }

    /**
     * 是否允许重评(终审 I3① 修复:改用「期望数据内容指纹」,不再依赖 candidate.updated_at)。
     *
     * <p>原实现的 {@code candidate.updated_at > record.created_at} 在生产环境恒不成立:MyBatis-Plus
     * {@code updateById} 会显式回写旧时间戳值,抑制 MySQL {@code ON UPDATE CURRENT_TIMESTAMP},
     * 快照变更后 updated_at 不前移 → 重评门恒关闭 → 「职能待确认」候选人首轮即终局、永久 PENDING。
     *
     * <p>现行规则:
     * <ul>
     *     <li>无记录 → 由调用方 {@link #scorePending(Long)} 直接评分(本方法不参与);</li>
     *     <li>有记录且非「职能待确认」→ 跳过(已有定论,不重复消耗 token);</li>
     *     <li>是「职能待确认」且记录指纹 == 当前期望指纹 → 跳过(内容无变化,防每轮空转);</li>
     *     <li>是「职能待确认」且指纹不同(含历史记录未携带指纹,或已补齐/变更期望数据)→ 放行重评一次。</li>
     * </ul>
     */
    private boolean shouldReevaluate(Candidate candidate, ScoreRecord record) {
        if (record.getReason() == null || !record.getReason().contains(PENDING_CONCLUSION_MARK)) {
            return false;
        }
        String recordedFingerprint = extractFingerprint(record.getReason());
        // 记录未携带指纹(历史遗留/被覆盖)视为「未知」,放行一次重评以自愈;解析成功后按内容比对。
        return recordedFingerprint == null || !recordedFingerprint.equals(expectationFingerprint(candidate));
    }

    /** 「职能待确认」结论标记(携带期望指纹),格式 {@code 职能待确认(fingerprint:<hash>)} */
    private String pendingMarker(Candidate candidate) {
        return PENDING_CONCLUSION_MARK + "(fingerprint:" + expectationFingerprint(candidate) + ")";
    }

    /** 从 score_record.reason 解析期望指纹;未命中/格式被覆盖返回 null(容错,不抛异常) */
    private static String extractFingerprint(String reason) {
        if (reason == null) {
            return null;
        }
        Matcher matcher = FINGERPRINT_PATTERN.matcher(reason);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 候选人当前期望数据指纹:对 snapshot 的 {@code want_title} 与 {@code expectation_evidence}
     * 规范化后取 SHA-256 十六进制前缀。规范化按键排序 JSON 对象,保证字段顺序不同但内容相同 → 指纹一致;
     * 期望由缺失变为补齐(或内容变更)→ 指纹不同。仅在期望字段上计算,避免无关字段变更触发无谓重评。
     */
    String expectationFingerprint(Candidate candidate) {
        return sha256Hex(canonicalExpectation(candidate.getSnapshot())).substring(0, FINGERPRINT_LENGTH);
    }

    /** 期望字段规范化字符串(缺失/非对象均映射为稳定的空值表示) */
    private String canonicalExpectation(String snapshotJson) {
        JsonNode snapshot = JsonExtractor.parse(snapshotJson).orElse(null);
        if (snapshot == null || !snapshot.isObject()) {
            return "want_title=;evidence=null";
        }
        return "want_title=" + snapshot.path("want_title").asText("").trim()
                + ";evidence=" + canonicalJson(snapshot.get("expectation_evidence"));
    }

    /** JSON 规范化:对象按键升序排列(递归),数组保持顺序,保证同内容不同字段顺序产生相同串 */
    private String canonicalJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(entry.getKey()).append("\":").append(canonicalJson(entry.getValue()));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalJson(node.get(i)));
            }
            return sb.append(']').toString();
        }
        return node.toString();
    }

    /** SHA-256 十六进制小写摘要 */
    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /** snapshot 是否缺期望证据(既无 expectation_evidence 对象也无非空 want_title) */
    private boolean needsResumeDetail(Candidate candidate) {
        JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
        if (snapshot == null || !snapshot.isObject()) {
            return true;
        }
        boolean hasEvidence = snapshot.has("expectation_evidence") && snapshot.get("expectation_evidence").isObject();
        boolean hasWantTitle = snapshot.path("want_title").isTextual() && !snapshot.path("want_title").asText().isBlank();
        return !hasEvidence && !hasWantTitle;
    }

    /**
     * 读取在线简历详情并合并期望字段写回 snapshot(设计 §3.1(3))。
     * 失败/无期望字段 → 只记日志并保持原快照(UNKNOWN 路径,不猜);风控类异常上抛。
     */
    private void enrichFromResumeDetail(LiepinAccount account, Candidate candidate) {
        String resumeId = resumeDetailId(candidate);
        if (resumeId.isEmpty()) {
            log.info("候选人 {} 无可用的简历标识,跳过详情读取(保持职能待确认)", candidate.getId());
            return;
        }
        JsonNode detail;
        try {
            Optional<JsonNode> result = commandService.resume(account, resumeId,
                    Duration.ofMinutes(properties.getLiepin().getShortTimeoutMinutes()));
            detail = result == null ? null : result.orElse(null);
        } catch (CliException e) {
            if (e.getType() == CliException.Type.RISK_CONTROL) {
                throw e;
            }
            log.warn("候选人 {} 在线简历详情读取失败,保持职能待确认: {}", candidate.getId(), e.getMessage());
            return;
        } catch (Exception e) {
            log.warn("候选人 {} 在线简历详情读取异常,保持职能待确认: {}", candidate.getId(), e.getMessage());
            return;
        }
        if (detail == null || !detail.isObject() || !hasExpectationFields(detail)) {
            log.info("候选人 {} 在线简历详情无期望字段,保持职能待确认", candidate.getId());
            return;
        }
        candidate.setSnapshot(mergeResumeDetail(candidate.getSnapshot(), detail));
        candidateMapper.updateById(candidate);
        log.info("候选人 {} 已合并在线简历期望字段(简历标识={})", candidate.getId(), resumeId);
    }

    /**
     * 简历详情读取入参:优先快照内的搜索节点 resume_id,其次落库 resume_id 主列。
     *
     * <p>注意:推荐节点的 {@code snapshot.talentId} 为 enresId(56 位 hex),并非 CLI
     * {@code resume} 命令的有效标识(resIdEncode,25 字符),用作详情入参会返回「简历信息不存在」,
     * 故不再采用(真机联调确认)。
     */
    private String resumeDetailId(Candidate candidate) {
        JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
        if (snapshot != null) {
            String resumeId = snapshot.path("resume_id").asText("").trim();
            if (!resumeId.isEmpty()) {
                return resumeId;
            }
        }
        String stored = candidate.getResumeId();
        return stored == null ? "" : stored.trim();
    }

    /** 详情是否含可用期望字段(expectation_evidence 对象 或 非空 want_title) */
    private boolean hasExpectationFields(JsonNode detail) {
        JsonNode evidence = detail.get("expectation_evidence");
        boolean evidencePresent = evidence != null && evidence.isObject();
        boolean wantTitlePresent = detail.path("want_title").isTextual()
                && !detail.path("want_title").asText().isBlank();
        return evidencePresent || wantTitlePresent;
    }

    /**
     * 合并详情进快照:期望字段强制覆盖,其余字段仅在快照缺失时补充
     * (保留既有 im_id/user_id/salary 等,避免覆盖后影响来信路由与预筛)。
     */
    private String mergeResumeDetail(String snapshotJson, JsonNode detail) {
        JsonNode existing = JsonExtractor.parse(snapshotJson).orElse(null);
        ObjectNode merged = existing != null && existing.isObject()
                ? ((ObjectNode) existing).deepCopy()
                : SNAPSHOT_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = detail.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (EXPECTATION_FIELDS.contains(entry.getKey()) || !merged.has(entry.getKey())) {
                merged.set(entry.getKey(), entry.getValue());
            }
        }
        return merged.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 评分核心流程(不落库,便于测试与重试) */
    public ScoreResult score(Jd jd, Candidate candidate) {
        // 0. 期望职能三态门禁(设计 3.1/4.2):不匹配直接 FAIL(不调 AI 省 token);待确认置 PENDING(绝不 PASS)
        JobMatchEvaluator.Result jobMatch = JobMatchEvaluator.evaluateFromSnapshot(
                JsonExtractor.parse(candidate.getSnapshot()).orElse(null), jd.getTitle());
        if (jobMatch.status() == JobMatchEvaluator.Status.MISMATCH) {
            log.info("候选人 {} 期望职能不匹配,直接排除: {}", candidate.getId(), jobMatch.reason());
            return ScoreResult.preFilteredFail("职能不匹配: " + jobMatch.reason());
        }
        if (jobMatch.status() == JobMatchEvaluator.Status.UNKNOWN) {
            log.info("候选人 {} 期望职能待确认,置 PENDING: {}", candidate.getId(), jobMatch.reason());
            return ScoreResult.pending("职能待确认: " + jobMatch.reason());
        }

        // 1. 规则预筛(硬性门槛,省 token)
        Optional<ScoreResult> preFiltered = preFilter(jd, candidate);
        if (preFiltered.isPresent()) {
            log.info("候选人 {} 被预筛规则直接排除: {}", candidate.getId(), preFiltered.get().summary());
            return preFiltered.get();
        }

        // 2. 评分 Agent 调用 + 解析重试(门槛取岗位值,缺省回退全局)
        int passThreshold = jd.getScoreThreshold() != null
                ? jd.getScoreThreshold() : properties.getScoring().getPassThreshold();
        String systemPrompt = loadPrompt();
        String userPrompt = buildUserPrompt(jd, candidate, passThreshold);
        int maxRetry = properties.getScoring().getMaxParseRetry();
        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                String output = aiClient.chat(systemPrompt, userPrompt);
                JsonNode node = JsonExtractor.parse(output).orElseThrow(
                        () -> new IllegalArgumentException("模型输出无 JSON: " + abbreviate(output)));
                return ScoreResult.fromJson(node, passThreshold);
            } catch (Exception e) {
                lastError = e;
                log.warn("候选人 {} 评分输出解析失败(第 {} 次): {}", candidate.getId(), attempt + 1, e.getMessage());
            }
        }
        throw BizException.badRequest("评分输出解析失败(重试 " + maxRetry + " 次后放弃): " + lastError.getMessage());
    }

    /** 规则预筛:返回非空则直接采纳该结果(跳过模型调用) */
    Optional<ScoreResult> preFilter(Jd jd, Candidate candidate) {
        if (jd.getSalaryMax() == null || candidate.getSnapshot() == null) {
            return Optional.empty();
        }
        JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
        if (snapshot == null) {
            return Optional.empty();
        }
        // 薪资硬过滤:期望薪资下限 > 岗位上限 1.5 倍 → 直接排除
        String salary = snapshot.path("salary").asText("");
        Integer minSalary = parseSalaryMin(salary);
        if (minSalary != null && minSalary > jd.getSalaryMax() * 1.5) {
            return Optional.of(ScoreResult.preFilteredFail(
                    "期望薪资下限 " + minSalary + " 远超岗位上限 " + jd.getSalaryMax()));
        }
        return Optional.empty();
    }

    /** 解析薪资区间下限,如 "45-60K·16薪" → 45000;无法解析返回 null */
    Integer parseSalaryMin(String salaryText) {
        if (salaryText == null || salaryText.isBlank()) {
            return null;
        }
        Matcher range = Pattern.compile("(\\d+)\\s*-").matcher(salaryText);
        if (range.find()) {
            return Integer.parseInt(range.group(1)) * 1000;
        }
        Matcher single = Pattern.compile("^(\\d+)\\s*[Kk]").matcher(salaryText.trim());
        if (single.find()) {
            return Integer.parseInt(single.group(1)) * 1000;
        }
        return null;
    }

    private String buildUserPrompt(Jd jd, Candidate candidate, int passThreshold) {
        StringBuilder sb = new StringBuilder();
        sb.append("请按系统提示词中的评分细则,对候选人进行评分。\n\n");
        sb.append("## 岗位信息\n");
        sb.append("- 岗位名称: ").append(jd.getTitle()).append("\n");
        sb.append("- 对外 JD: ").append(abbreviate(jd.getExternalJd(), 800)).append("\n");
        if (jd.getSalaryMin() != null || jd.getSalaryMax() != null) {
            sb.append("- 薪资预算(元/月): ").append(jd.getSalaryMin()).append(" ~ ").append(jd.getSalaryMax()).append("\n");
        }
        // 门槛显式告知模型(评审 Important-2):最终通过还需 score >= 该值,由服务端硬规则强制
        sb.append("- 通过门槛：").append(passThreshold).append(" 分\n");
        sb.append("\n## 候选人在线简历快照\n");
        sb.append(candidate.getSnapshot()).append("\n");
        return sb.toString();
    }

    private String loadPrompt() {
        try {
            Resource resource = resourceLoader.getResource(properties.getScoring().getPromptFile());
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("评分提示词加载失败: " + properties.getScoring().getPromptFile(), e);
        }
    }

    private String abbreviate(String s) {
        return abbreviate(s, 500);
    }

    private String abbreviate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }
}
