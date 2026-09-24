package com.hragent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hragent.common.BizException;
import com.hragent.config.HrAgentProperties;
import com.hragent.entity.Candidate;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.SearchTask;
import com.hragent.executor.CliException;
import com.hragent.notify.NotifyService;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.SearchTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 搜索任务流程:JD 关键词 → liepin search → 候选人快照落库(按 resume_id+name 去重)。
 */
@Slf4j
@Service
public class SearchTaskService {

    private final SearchTaskMapper taskMapper;
    private final JdMapper jdMapper;
    private final LiepinAccountMapper accountMapper;
    private final CandidateMapper candidateMapper;
    private final LiepinCommandService commandService;
    private final TaskQueueService queueService;
    private final HrAgentProperties properties;
    private final NotifyService notifyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearchTaskService(SearchTaskMapper taskMapper, JdMapper jdMapper,
                             LiepinAccountMapper accountMapper, CandidateMapper candidateMapper,
                             LiepinCommandService commandService, TaskQueueService queueService,
                             HrAgentProperties properties, NotifyService notifyService) {
        this.taskMapper = taskMapper;
        this.jdMapper = jdMapper;
        this.accountMapper = accountMapper;
        this.candidateMapper = candidateMapper;
        this.commandService = commandService;
        this.queueService = queueService;
        this.properties = properties;
        this.notifyService = notifyService;
    }

    /** 创建搜索任务(关键词取 JD 对内寻源备注,为空则用岗位名) */
    @Transactional
    public SearchTask createTask(Long jdId, Long accountId) {
        Jd jd = jdMapper.selectById(jdId);
        if (jd == null) {
            throw BizException.notFound("岗位不存在");
        }
        LiepinAccount account = accountMapper.selectById(accountId);
        if (account == null) {
            throw BizException.notFound("猎聘账号不存在");
        }
        String keywords = (jd.getInternalNotes() == null || jd.getInternalNotes().isBlank())
                ? jd.getTitle() : jd.getInternalNotes();

        SearchTask task = new SearchTask();
        task.setJdId(jdId);
        task.setAccountId(accountId);
        task.setKeywords(keywords);
        task.setStatus("QUEUED");
        task.setRetryCount(0);
        taskMapper.insert(task);
        return task;
    }

    /**
     * 执行任务主流程(由调度器调用):
     * 账号熔断直接终态失败;CLI 异常按类型重试或终态;成功则候选人落库并 DONE。
     */
    public void execute(SearchTask task) {
        LiepinAccount account = accountMapper.selectById(task.getAccountId());
        if (account == null) {
            queueService.fail(task.getId(), "账号不存在", task.getRetryCount());
            return;
        }
        if (Boolean.TRUE.equals(account.getCircuitBreaker())) {
            queueService.fail(task.getId(), "账号已熔断,停止调度", TaskQueueService.MAX_RETRY);
            return;
        }
        if ("NEED_SCAN".equals(account.getLoginStatus())) {
            queueService.fail(task.getId(), "账号需扫码登录,任务挂起", task.getRetryCount());
            return;
        }

        try {
            Duration timeout = Duration.ofMinutes(properties.getLiepin().getSearchTimeoutMinutes());
            List<JsonNode> candidates = commandService.search(
                    account, task.getKeywords(), properties.getLiepin().getSearchLimit(), timeout);
            int saved = saveCandidates(task.getJdId(), candidates);
            queueService.complete(task.getId());
            log.info("任务 {} 完成:搜索 {} 条,落库/更新 {} 条", task.getId(), candidates.size(), saved);
        } catch (CliException e) {
            if (e.getType() == CliException.Type.RISK_CONTROL) {
                // 账号已熔断,任务无重试意义 → 终态失败
                queueService.fail(task.getId(), "账号触发风控熔断: " + e.getMessage(), TaskQueueService.MAX_RETRY);
            } else {
                queueService.fail(task.getId(), e.getMessage(), task.getRetryCount());
            }
        } catch (Exception e) {
            log.error("任务 {} 执行异常", task.getId(), e);
            queueService.fail(task.getId(), "执行异常: " + e.getMessage(), task.getRetryCount());
        }

        // 终态失败告警(评审 P2-14)
        SearchTask after = taskMapper.selectById(task.getId());
        if (after != null && "FAILED".equals(after.getStatus())) {
            notifyService.alert("搜索任务终态失败",
                    "任务: #" + task.getId() + " 岗位: " + task.getJdId() + " 关键词: " + task.getKeywords()
                            + "\n原因: " + after.getErrorMsg());
        }
    }

    /** 候选人落库:按 (resume_id, name) 去重,存在则更新快照 */
    @Transactional
    public int saveCandidates(Long jdId, List<JsonNode> nodes) {
        int saved = 0;
        for (JsonNode node : nodes) {
            String resumeId = node.path("resume_id").asText("");
            String name = node.path("name").asText("");
            if (resumeId.isBlank() || name.isBlank()) {
                continue;
            }
            Candidate existing = candidateMapper.selectOne(new LambdaQueryWrapper<Candidate>()
                    .eq(Candidate::getResumeId, resumeId)
                    .eq(Candidate::getName, name));
            if (existing != null) {
                existing.setSnapshot(toJson(node));
                candidateMapper.updateById(existing);
            } else {
                Candidate candidate = new Candidate();
                candidate.setResumeId(resumeId);
                candidate.setName(name);
                candidate.setSnapshot(toJson(node));
                candidate.setPassStatus("PENDING");
                candidate.setJdId(jdId);
                candidateMapper.insert(candidate);
            }
            saved++;
        }
        return saved;
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
