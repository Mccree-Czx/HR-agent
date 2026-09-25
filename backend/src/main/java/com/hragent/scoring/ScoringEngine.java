package com.hragent.scoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hragent.ai.AiClient;
import com.hragent.common.BizException;
import com.hragent.config.HrAgentProperties;
import com.hragent.entity.Candidate;
import com.hragent.entity.Jd;
import com.hragent.entity.ScoreRecord;
import com.hragent.executor.CliException;
import com.hragent.executor.JsonExtractor;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.ScoreRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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

    private final AiClient aiClient;
    private final HrAgentProperties properties;
    private final ScoreRecordMapper scoreRecordMapper;
    private final CandidateMapper candidateMapper;
    private final JdMapper jdMapper;
    private final ResourceLoader resourceLoader;

    public ScoringEngine(AiClient aiClient, HrAgentProperties properties,
                         ScoreRecordMapper scoreRecordMapper, CandidateMapper candidateMapper,
                         JdMapper jdMapper, ResourceLoader resourceLoader) {
        this.aiClient = aiClient;
        this.properties = properties;
        this.scoreRecordMapper = scoreRecordMapper;
        this.candidateMapper = candidateMapper;
        this.jdMapper = jdMapper;
        this.resourceLoader = resourceLoader;
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
        record.setReason(result.summary() + " | " + String.join("; ", result.reasons()));
        record.setRuleVersion(properties.getScoring().getRuleVersion());
        record.setModel(properties.getAi().getModel());
        scoreRecordMapper.insert(record);

        candidate.setScore(result.score());
        candidate.setPassStatus(result.pending() ? "PENDING" : (result.pass() ? "PASS" : "FAIL"));
        candidateMapper.updateById(candidate);
        return record;
    }

    /**
     * 批量补评分:遍历该岗位下 pass_status=PENDING 且尚无 (candidate, jd) 评分记录的候选人补齐评分。
     * <p>- 已有评分记录者跳过(含「职能待确认」置 PENDING 的记录,避免重复消耗 token 死循环)
     * <p>- 单个候选人失败不阻断其余候选人;风控异常上抛由熔断链路处理
     * 返回本轮成功评分的候选人数量。
     */
    @Transactional
    public int scorePending(Long jdId) {
        List<Candidate> pending = candidateMapper.selectList(new LambdaQueryWrapper<Candidate>()
                .eq(Candidate::getJdId, jdId)
                .eq(Candidate::getPassStatus, "PENDING")
                .orderByAsc(Candidate::getId));
        if (pending.isEmpty()) {
            return 0;
        }
        int scored = 0;
        for (Candidate candidate : pending) {
            Long existing = scoreRecordMapper.selectCount(new LambdaQueryWrapper<ScoreRecord>()
                    .eq(ScoreRecord::getCandidateId, candidate.getId())
                    .eq(ScoreRecord::getJdId, jdId));
            if (existing != null && existing > 0) {
                continue;
            }
            try {
                scoreAndSave(candidate.getId());
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
