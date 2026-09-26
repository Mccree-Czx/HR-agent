package com.hragent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hragent.common.BizException;
import com.hragent.entity.LiepinAccount;
import com.hragent.executor.CliException;
import com.hragent.executor.CliResult;
import com.hragent.executor.JsonExtractor;
import com.hragent.executor.LiepinCliExecutor;
import com.hragent.notify.NotifyService;
import com.hragent.repository.LiepinAccountMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * liepin-cli 命令语义封装:把 CLI 细节(参数顺序、--json、输出解析)收敛在此,
 * 上层业务(搜索/评分/打招呼)只与 JsonNode 打交道。
 *
 * 风控/登录态异常会同步标记账号状态(熔断/需扫码),评审 P0-3。
 */
@Slf4j
@Service
public class LiepinCommandService {

    private final LiepinCliExecutor executor;
    private final LiepinAccountMapper accountMapper;
    private final NotifyService notifyService;

    public LiepinCommandService(LiepinCliExecutor executor, LiepinAccountMapper accountMapper,
                                NotifyService notifyService) {
        this.executor = executor;
        this.accountMapper = accountMapper;
        this.notifyService = notifyService;
    }

    /** 搜索人才 → 候选人数组 */
    public List<JsonNode> search(LiepinAccount account, String keywords, int limit, Duration timeout) {
        CliResult result = run(account, timeout, "search", keywords, "--limit", String.valueOf(limit), "--json");
        JsonNode node = JsonExtractor.parse(result.stdout())
                .orElseThrow(() -> BizException.badRequest("搜索输出无有效 JSON"));
        if (!node.isArray()) {
            throw BizException.badRequest("搜索输出不是数组: " + truncate(result.stdout()));
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    /** 简历详情 → 对象 */
    public Optional<JsonNode> resume(LiepinAccount account, String resumeId, Duration timeout) {
        CliResult result = run(account, timeout, "resume", resumeId, "--json");
        return JsonExtractor.parse(result.stdout());
    }

    /** 平台推荐候选人 → 数组(依赖猎聘上已发布的职位) */
    public List<JsonNode> recommend(LiepinAccount account, String jobId, Duration timeout) {
        if (jobId == null || !jobId.matches("[1-9][0-9]*")) {
            throw BizException.badRequest("推荐必须指定有效的猎聘岗位 ID");
        }
        CliResult result = run(account, timeout, "recommend", "--jobId", jobId, "--json");
        JsonNode node = JsonExtractor.parse(result.stdout())
                .orElseThrow(() -> BizException.badRequest("recommend 输出无有效 JSON"));
        if (!node.isArray()) {
            throw BizException.badRequest("recommend 输出不是数组: " + truncate(result.stdout()));
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    /** 发布职位到猎聘(fork 版 CLI 的 jobpublish 命令,草稿→正式上线) */
    public Optional<JsonNode> jobPublish(LiepinAccount account, String dataJson, Duration timeout) {
        CliResult result = run(account, timeout, "jobpublish", "--data", dataJson, "--json");
        return JsonExtractor.parse(result.stdout());
    }

    /** 猎聘职位列表(招聘者端,用于同步到系统岗位管理) */
    public List<JsonNode> jobList(LiepinAccount account, Duration timeout) {
        CliResult result = run(account, timeout, "joblist", "--limit", "40", "--json");
        JsonNode node = JsonExtractor.parse(result.stdout())
                .orElseThrow(() -> BizException.badRequest("joblist 输出无有效 JSON"));
        if (!node.isArray()) {
            throw BizException.badRequest("joblist 输出不是数组");
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    /** 删除猎聘职位(fork 版 CLI 的 jobdelete 命令:自动先结束发布再删除) */
    public Optional<JsonNode> jobDelete(LiepinAccount account, String jobId, Duration timeout) {
        CliResult result = run(account, timeout, "jobdelete", "--job", jobId, "--json");
        return JsonExtractor.parse(result.stdout());
    }

    /**
     * 聊天列表 → 数组(同意/已读状态检测依据)。
     * 固定取 100 条(CLI 上限):新招呼会把旧会话挤出较小分页窗口,曾导致已回复候选人漏检
     * (2026-09-26:25 条新招呼把 14 点答复的候选人挤出了默认 30 条窗口)。
     */
    public List<JsonNode> chatlist(LiepinAccount account, Duration timeout) {
        CliResult result = run(account, timeout, "chatlist", "--limit", "100", "--json");
        JsonNode node = JsonExtractor.parse(result.stdout())
                .orElseThrow(() -> BizException.badRequest("chatlist 输出无有效 JSON"));
        if (!node.isArray()) {
            throw BizException.badRequest("chatlist 输出不是数组");
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    /**
     * 单人会话消息 → 数组(来信轮询用)。
     * 入参是<b>对方 im_id</b>(chatlist 返回的 im_id),不是 resume_id;消息字段含 type/payload.bodies。
     */
    public List<JsonNode> chatmsg(LiepinAccount account, String imId, Duration timeout) {
        if (imId == null || imId.isBlank()) {
            throw BizException.badRequest("chatmsg 必须提供对方 im_id");
        }
        CliResult result = run(account, timeout, "chatmsg", imId, "--json");
        JsonNode node = JsonExtractor.parse(result.stdout())
                .orElseThrow(() -> BizException.badRequest("chatmsg 输出无有效 JSON"));
        if (!node.isArray()) {
            throw BizException.badRequest("chatmsg 输出不是数组: " + truncate(result.stdout()));
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    /**
     * 下载指定会话的简历附件(走浏览器下载通道 + PDF 校验)。
     * 输出 {@code {success,file,bytes,sha256,sourceOrigin}};失败时 CLI 非零退出并由 {@link #run} 抛出
     * {@link CliException}(由上层留待下轮重试,不为其写半状态)。
     */
    public Optional<JsonNode> attachDownload(LiepinAccount account, String imId, String outDir, Duration timeout) {
        if (imId == null || imId.isBlank()) {
            throw BizException.badRequest("attach-download 必须提供会话 im_id");
        }
        if (outDir == null || outDir.isBlank()) {
            throw BizException.badRequest("attach-download 必须提供下载目录");
        }
        CliResult result = run(account, timeout, "attach-download",
                "--imId", imId, "--out", outDir, "--json");
        return JsonExtractor.parse(result.stdout());
    }

    /**
     * 打招呼 → 输出对象(含 success 标记)。
     * ejobId 必传:猎聘发起沟通必须挂在具体职位下,缺省时 CLI 会回退到账号第一个职位(导致错配)。
     * message 用 --message 传递(位置参数只能被 CLI 识别首个)。
     */
    public Optional<JsonNode> greet(LiepinAccount account, String resumeId, String ejobId,
                                    String message, Duration timeout) {
        CliResult result = run(account, timeout, "greet", resumeId,
                "--ejobId", ejobId, "--message", message, "--json");
        return JsonExtractor.parse(result.stdout());
    }

    /** 索要简历(需先 greet 建立会话)→ 输出对象 */
    public Optional<JsonNode> requestResume(LiepinAccount account, String resumeId, Duration timeout) {
        if (resumeId == null || resumeId.isBlank()) {
            throw BizException.badRequest("索要简历必须提供 resume_id，不能使用 im_id");
        }
        CliResult result = run(account, timeout, "request-resume", resumeId, "--json");
        return JsonExtractor.parse(result.stdout());
    }

    private CliResult run(LiepinAccount account, Duration timeout, String... args) {
        CliResult result;
        try {
            result = executor.execute(account, timeout, args);
        } catch (IOException e) {
            throw new CliException(CliException.Type.FAILED,
                    "liepin-cli 启动失败(可执行文件不存在?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(CliException.Type.FAILED, "执行被中断", e);
        }

        try {
            executor.checkRisk(account, result);
        } catch (CliException e) {
            markAccountByException(account, e);
            throw e;
        }
        return result;
    }

    /** 异常类型 → 账号状态标记 */
    private void markAccountByException(LiepinAccount account, CliException e) {
        boolean changed = false;
        switch (e.getType()) {
            case RISK_CONTROL -> {
                if (!Boolean.TRUE.equals(account.getCircuitBreaker())
                        || !"RESTRICTED".equals(account.getLoginStatus())) {
                    account.setCircuitBreaker(true);
                    account.setLoginStatus("RESTRICTED");
                    changed = true;
                }
                log.warn("账号 {} 触发熔断: {}", account.getId(), e.getMessage());
                notifyService.alert("账号触发风控熔断",
                        "账号: " + account.getName() + "(id=" + account.getId() + ")\n"
                                + "原因: " + e.getMessage() + "\n处理: 停用该账号所有任务,人工确认后重置熔断标记");
            }
            case NOT_LOGGED_IN -> {
                if (!"NEED_SCAN".equals(account.getLoginStatus())) {
                    account.setLoginStatus("NEED_SCAN");
                    changed = true;
                }
                log.warn("账号 {} 登录态失效,需扫码: {}", account.getId(), e.getMessage());
                notifyService.alert("账号登录态失效",
                        "账号: " + account.getName() + "(id=" + account.getId() + ")\n请在后台点击扫码登录");
            }
            case TIMEOUT, FAILED -> {
                // 可重试类错误,不标记账号状态
            }
        }
        if (changed) {
            accountMapper.updateById(account);
        }
    }

    private String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
