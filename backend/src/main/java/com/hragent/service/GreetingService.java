package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hragent.ai.AiClient;
import com.hragent.config.HrAgentProperties;
import com.hragent.entity.Candidate;
import com.hragent.entity.GreetingRecord;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.executor.JsonExtractor;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.GreetingRecordMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 打招呼模块(评审 P1-5/P1-6):
 * - 全局防重复联系:同一候选人仅可被联系一次(greeting_record.candidate_id 唯一键 + 业务查重)
 * - 账号维度模式:AUTO 直接发送;MANUAL 生成待确认记录不发送(阶段 4 确认列表处理)
 * - 每日配额:账号当日已发送数达到 daily_greet_quota 则停止该账号
 * - 操作节奏:同账号两次发送间隔不小于 greetIntervalSeconds(评审 P0-3)
 */
@Slf4j
@Service
public class GreetingService {

    private final GreetingRecordMapper greetingMapper;
    private final CandidateMapper candidateMapper;
    private final LiepinAccountMapper accountMapper;
    private final JdMapper jdMapper;
    private final LiepinCommandService commandService;
    private final AiClient aiClient;
    private final HrAgentProperties properties;
    private final ResourceLoader resourceLoader;

    public GreetingService(GreetingRecordMapper greetingMapper, CandidateMapper candidateMapper,
                           LiepinAccountMapper accountMapper, JdMapper jdMapper,
                           LiepinCommandService commandService,
                           AiClient aiClient, HrAgentProperties properties, ResourceLoader resourceLoader) {
        this.greetingMapper = greetingMapper;
        this.candidateMapper = candidateMapper;
        this.accountMapper = accountMapper;
        this.jdMapper = jdMapper;
        this.commandService = commandService;
        this.aiClient = aiClient;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 对岗位下「评分通过且未联系过」的候选人打招呼,最多 limit 个。
     * 返回成功创建打招呼记录的数量。
     */
    @Transactional
    public int greetPassed(Long jdId, int limit) {
        List<Candidate> candidates = candidateMapper.selectList(new LambdaQueryWrapper<Candidate>()
                .eq(Candidate::getJdId, jdId)
                .eq(Candidate::getPassStatus, "PASS")
                .orderByDesc(Candidate::getScore)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
        if (candidates.isEmpty()) {
            log.info("岗位 {} 无通过评分且待联系的候选人", jdId);
            return 0;
        }

        int created = 0;
        // 单账号场景简化:取第一个正常账号;多账号轮询在阶段 4 台账完善
        LiepinAccount account = accountMapper.selectOne(new LambdaQueryWrapper<LiepinAccount>()
                .eq(LiepinAccount::getLoginStatus, "NORMAL")
                .orderByAsc(LiepinAccount::getId)
                .last("LIMIT 1"));
        if (account == null) {
            log.warn("无可用猎聘账号(NORMAL),无法打招呼");
            return 0;
        }
        for (Candidate candidate : candidates) {
            if (tryGreet(account, candidate)) {
                created++;
            }
        }
        return created;
    }

    /** 单个候选人打招呼(含防重复/配额/节奏/模式判断),返回是否创建记录 */
    public boolean tryGreet(LiepinAccount account, Candidate candidate) {
        // 1. 全局防重复联系(评审 P1-6);发送失败(SEND_FAILED)的允许重试
        Long existing = greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getCandidateId, candidate.getId())
                .ne(GreetingRecord::getStatus, "SEND_FAILED"));
        if (existing > 0) {
            log.info("候选人 {} 已被联系过,跳过", candidate.getId());
            return false;
        }

        // 2. 每日配额
        if (isQuotaExhausted(account)) {
            log.warn("账号 {} 当日打招呼配额已用尽,停止", account.getId());
            return false;
        }

        // 3. 解析候选人来源岗位对应的猎聘职位(打招呼必须挂到具体职位,避免错配)
        String ejobId = resolveEjobId(candidate);
        if (ejobId == null) {
            log.warn("候选人 {} 的来源岗位(JD id={})未关联猎聘职位,跳过打招呼;请先发布/关联职位",
                    candidate.getId(), candidate.getJdId());
            return false;
        }

        // 4. 生成话术
        String message = composeGreeting(candidate);

        // 5. 人工确认模式:仅落库待确认,不发送(阶段 4 确认列表)
        if ("MANUAL".equals(account.getGreetMode())) {
            insertRecord(account, candidate, ejobId, message, "PENDING_CONFIRM");
            log.info("候选人 {} 生成待确认打招呼(账号 {} 人工确认模式)", candidate.getId(), account.getId());
            return true;
        }

        // 6. 自动模式:节奏控制 + 发送;失败(如候选人设隐私保护)记录后继续下一人
        try {
            enforcePace(account);
            commandService.greet(account, candidate.getResumeId(), ejobId, message,
                    Duration.ofMinutes(properties.getLiepin().getShortTimeoutMinutes()));
            insertRecord(account, candidate, ejobId, message, "SENT");
            log.info("候选人 {} 打招呼已发送(账号 {}, 职位 {})", candidate.getId(), account.getId(), ejobId);
            return true;
        } catch (Exception e) {
            log.warn("候选人 {} 打招呼发送失败: {}", candidate.getId(), e.getMessage());
            insertRecord(account, candidate, ejobId, message, "SEND_FAILED");
            return false;
        }
    }

    /** 解析候选人来源岗位对应的猎聘职位 ID;缺失返回 null(调用方跳过并告警) */
    private String resolveEjobId(Candidate candidate) {
        if (candidate.getJdId() == null) {
            return null;
        }
        Jd jd = jdMapper.selectById(candidate.getJdId());
        if (jd == null || jd.getLiepinJobId() == null || jd.getLiepinJobId().isBlank()) {
            return null;
        }
        return jd.getLiepinJobId();
    }

    private void insertRecord(LiepinAccount account, Candidate candidate, String ejobId,
                              String message, String status) {
        GreetingRecord existing = greetingMapper.selectOne(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getCandidateId, candidate.getId())
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setAccountId(account.getId());
            existing.setMessage(message);
            existing.setStatus(status);
            existing.setMode(account.getGreetMode());
            existing.setLiepinJobId(ejobId);
            greetingMapper.updateById(existing);
            return;
        }
        GreetingRecord record = new GreetingRecord();
        record.setCandidateId(candidate.getId());
        record.setAccountId(account.getId());
        record.setMessage(message);
        record.setStatus(status);
        record.setMode(account.getGreetMode());
        record.setLiepinJobId(ejobId);
        greetingMapper.insert(record);
    }

    /** 话术 Agent 生成打招呼话术(失败回退固定模板) */
    private String composeGreeting(Candidate candidate) {
        try {
            String systemPrompt = loadPrompt();
            JsonNode snapshot = JsonExtractor.parse(candidate.getSnapshot()).orElse(null);
            String userPrompt = "候选人背景: " + (snapshot == null ? candidate.getSnapshot() : snapshot.toString())
                    + "\n请为这位候选人写一句打招呼话术。";
            String message = aiClient.chat(systemPrompt, userPrompt);
            String cleaned = message == null ? "" : message.trim().replaceAll("^[\"'“]+|[\"'”]+$", "");
            if (!cleaned.isBlank() && cleaned.length() <= 80) {
                return cleaned;
            }
        } catch (Exception e) {
            log.warn("话术生成失败,使用固定模板: {}", e.getMessage());
        }
        return "您好,看到您的背景与我们岗位较为匹配,方便进一步沟通吗?";
    }

    private boolean isQuotaExhausted(LiepinAccount account) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Long sentToday = greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getAccountId, account.getId())
                .eq(GreetingRecord::getStatus, "SENT")
                .ge(GreetingRecord::getCreatedAt, todayStart));
        Integer quota = account.getDailyGreetQuota();
        return quota != null && sentToday >= quota;
    }

    /** 节奏控制:距该账号上次发送不足间隔时等待(评审 P0-3) */
    private void enforcePace(LiepinAccount account) {
        GreetingRecord last = greetingMapper.selectOne(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getAccountId, account.getId())
                .eq(GreetingRecord::getStatus, "SENT")
                .orderByDesc(GreetingRecord::getCreatedAt)
                .last("LIMIT 1"));
        if (last == null || last.getCreatedAt() == null) {
            return;
        }
        long interval = properties.getScoring().getGreetIntervalSeconds();
        long elapsed = Duration.between(last.getCreatedAt(), LocalDateTime.now()).toSeconds();
        if (elapsed < interval) {
            try {
                Thread.sleep((interval - elapsed) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String loadPrompt() {
        try {
            Resource resource = resourceLoader.getResource("classpath:agents/greeting-writer.md");
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("话术提示词加载失败", e);
        }
    }
}
