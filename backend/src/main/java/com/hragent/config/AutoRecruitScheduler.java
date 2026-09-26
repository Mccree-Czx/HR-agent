package com.hragent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hragent.common.BizException;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.SearchTask;
import com.hragent.executor.CliException;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.SearchTaskMapper;
import com.hragent.scoring.ScoringEngine;
import com.hragent.service.AutoRecruitSettingService;
import com.hragent.service.ChatPollService;
import com.hragent.service.GreetingService;
import com.hragent.service.SearchTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自动招聘闭环定时编排(设计 §2/§4.2;2026-09-26 改造为运行时开关):
 * <ul>
 *     <li>调度:北京时间每天 09:00–18:00 每整点执行一轮(含 18:00,周末与节假日同样执行),停机期间不补跑</li>
 *     <li>运行时开关({@link AutoRecruitSettingService}):<b>只停主动外发</b>——OFF 时轮次照常运行
 *         (检测回复/已读、附件下载、评分、拉推荐),仅跳过打招呼与索要(攒着,开启后自动补做);
 *         开关存库持久化,重启/部署保持;轮次开始时判定,进行中的轮次跑完才生效</li>
 *     <li>单轮顺序:先来信轮询(被动)→ 逐岗位消费存量(补评分/打招呼)→ 再拉新推荐(主动)</li>
 *     <li>防重叠:岗位已有 QUEUED/RUNNING 任务则本轮跳过;轮次级互斥(定时重叠跳过、手动触发拒绝)</li>
 *     <li>异常:单岗位失败不阻断其他岗位;风控类 {@link CliException} 立即停止本轮并上抛(既有熔断链路处理)</li>
 *     <li>每轮结束写入运行摘要({@code auto_recruit.last_run}),供状态接口展示</li>
 * </ul>
 */
@Slf4j
@Component
public class AutoRecruitScheduler {

    /** 运行时段时区(北京时间) */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 运行时段起点(含) */
    private static final LocalTime WINDOW_START = LocalTime.of(9, 0);
    /** 运行时段终点(含),整点触发由 cron 保证 */
    private static final LocalTime WINDOW_END = LocalTime.of(18, 59, 59);
    /** 运行时段首/末整点(与 cron 9-18 对齐,nextRunAt 计算用) */
    private static final int FIRST_HOUR = 9;
    private static final int LAST_HOUR = 18;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HrAgentProperties properties;
    private final LiepinAccountMapper accountMapper;
    private final JdMapper jdMapper;
    private final SearchTaskMapper searchTaskMapper;
    private final SearchTaskService searchTaskService;
    private final ScoringEngine scoringEngine;
    private final GreetingService greetingService;
    private final ChatPollService chatPollService;
    private final AutoRecruitSettingService settingService;

    /** 轮次互斥:同一时刻仅允许一轮(定时重叠跳过,手动触发拒绝) */
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile LocalDateTime runningSince;

    public AutoRecruitScheduler(HrAgentProperties properties, LiepinAccountMapper accountMapper,
                                JdMapper jdMapper, SearchTaskMapper searchTaskMapper,
                                SearchTaskService searchTaskService, ScoringEngine scoringEngine,
                                GreetingService greetingService, ChatPollService chatPollService,
                                AutoRecruitSettingService settingService) {
        this.properties = properties;
        this.accountMapper = accountMapper;
        this.jdMapper = jdMapper;
        this.searchTaskMapper = searchTaskMapper;
        this.searchTaskService = searchTaskService;
        this.scoringEngine = scoringEngine;
        this.greetingService = greetingService;
        this.chatPollService = chatPollService;
        this.settingService = settingService;
    }

    /** 每天 09:00–18:00 每整点触发一轮(周末与节假日同样执行) */
    @Scheduled(cron = "0 0 9-18 * * *", zone = "Asia/Shanghai")
    public void hourly() {
        runRound();
    }

    /** 执行一轮(对外可直调;时间取北京时间当前时刻) */
    public void runRound() {
        runRound(LocalDateTime.now(ZONE));
    }

    /**
     * 定时执行一轮(时间可注入,便于测试边界)。
     * 顺序:时段门禁 → 互斥检查 → 执行;运行时开关在轮次内判定(只决定是否外发)。
     */
    void runRound(LocalDateTime now) {
        if (!isRunWindow(now)) {
            log.debug("非运行时段({}),本轮跳过", now);
            return;
        }
        if (running.get()) {
            log.warn("自动招聘:上一轮仍在运行,本轮跳过(防重叠)");
            return;
        }
        executeRound();
    }

    /**
     * 手动执行一轮(ADMIN 补跑/联调):绕过时段门禁、不受外发开关限制(手动操作永远可用),
     * 但仍受轮次互斥约束——已有轮次运行中则拒绝。
     */
    public void runRoundInternal() {
        if (running.get()) {
            throw BizException.badRequest("已有轮次正在运行,请稍后再试");
        }
        executeRound();
    }

    /**
     * 单轮编排主体。
     * 运行时开关语义(2026-09-26):OFF 只停主动外发(打招呼;索要门禁在 ChatPollService 内),
     * 检测回复/已读、附件下载、评分、拉推荐照常;每轮结束写运行摘要。
     */
    private void executeRound() {
        running.set(true);
        runningSince = LocalDateTime.now(ZONE);
        boolean enabled = settingService.isEnabled();
        RoundStats stats = new RoundStats(enabled ? "full" : "collectOnly");
        try {
            // 取第一个 NORMAL 账号(多账号轮询分配待确认,设计 §7)
            LiepinAccount account = accountMapper.selectOne(new LambdaQueryWrapper<LiepinAccount>()
                    .eq(LiepinAccount::getLoginStatus, "NORMAL")
                    .orderByAsc(LiepinAccount::getId)
                    .last("LIMIT 1"));
            if (account == null) {
                log.warn("自动招聘:无可用猎聘账号(login_status=NORMAL),本轮跳过");
                stats.noAccount = true;
                return;
            }

            // 步骤 1:先处理来信与附件(被动通道;外发开关 OFF 时其内部跳过打招呼/索要类外发)
            try {
                stats.polled = chatPollService.poll();
            } catch (CliException e) {
                if (e.getType() == CliException.Type.RISK_CONTROL) {
                    stats.riskStopped = true;
                }
                throw e;
            }

            // 步骤 2/3:遍历「在招且已关联有效猎聘职位」的岗位
            List<Jd> jds = jdMapper.selectList(new LambdaQueryWrapper<Jd>()
                    .eq(Jd::getStatus, "ACTIVE")
                    .isNotNull(Jd::getLiepinJobId)
                    .ne(Jd::getLiepinJobId, "")
                    .orderByAsc(Jd::getId));
            for (Jd jd : jds) {
                if (jd.getLiepinJobId() == null || !jd.getLiepinJobId().matches("[1-9]\\d*")) {
                    log.debug("自动招聘:岗位 {} 猎聘职位 ID 无效({}),跳过", jd.getId(), jd.getLiepinJobId());
                    continue;
                }
                try {
                    // 步骤 2:先消费存量——补评分(职能门禁+AI) → PASS 未联系者打招呼(含门槛/去重/节奏门禁)
                    stats.scored += scoringEngine.scorePending(jd.getId());
                    if (enabled) {
                        stats.greeted += greetingService.greetPassed(jd.getId(),
                                properties.getAutoRecruit().getGreetBatchLimit());
                    } else {
                        log.debug("自动招聘:外发已关闭,岗位 {} 跳过打招呼(攒着,开关恢复后补做)", jd.getId());
                    }

                    // 步骤 3:再拉新推荐(该岗位已有在途任务则跳过,防重叠)
                    if (hasActiveTask(jd.getId())) {
                        log.info("自动招聘:岗位 {} 已有在途(QUEUED/RUNNING)任务,本轮跳过拉新", jd.getId());
                        continue;
                    }
                    searchTaskService.createRecommendTask(jd.getId(), account.getId());
                    stats.recommended++;
                } catch (CliException e) {
                    // 风控/登录失效类:记录并以异常上抛,由既有熔断链路处理,停止本轮
                    if (e.getType() == CliException.Type.RISK_CONTROL) {
                        log.error("自动招聘:岗位 {} 触发风控,本轮立即停止", jd.getId(), e);
                        stats.riskStopped = true;
                        throw e;
                    }
                    stats.errors++;
                    log.warn("自动招聘:岗位 {} 处理失败,跳过: {}", jd.getId(), e.getMessage());
                } catch (Exception e) {
                    // 单岗位失败不阻断其他岗位
                    stats.errors++;
                    log.warn("自动招聘:岗位 {} 处理异常,跳过: {}", jd.getId(), e.getMessage(), e);
                }
            }
        } finally {
            running.set(false);
            runningSince = null;
            try {
                settingService.saveLastRun(stats.toJson());
            } catch (Exception e) {
                log.warn("自动招聘:运行摘要保存失败: {}", e.getMessage());
            }
            log.info("自动招聘本轮结束: {}", stats.summaryText());
        }
    }

    /** 该岗位是否已有排队/运行中的任务(防重叠) */
    private boolean hasActiveTask(Long jdId) {
        Long count = searchTaskMapper.selectCount(new LambdaQueryWrapper<SearchTask>()
                .eq(SearchTask::getJdId, jdId)
                .in(SearchTask::getStatus, "QUEUED", "RUNNING"));
        return count != null && count > 0;
    }

    /** 当前是否有轮次在运行(状态接口/互斥用) */
    public boolean isRunning() {
        return running.get();
    }

    /** 当前轮次的开始时间(无运行中轮次时为 null) */
    public LocalDateTime getRunningSince() {
        return runningSince;
    }

    /**
     * 运行时段判定:每天 09:00–18:59(Asia/Shanghai 时区)返回 true;时段外或 null 返回 false。
     * 周末与法定节假日不排除(用户拍板:每天执行)。
     */
    static boolean isRunWindow(LocalDateTime now) {
        if (now == null) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(WINDOW_START) && !time.isAfter(WINDOW_END);
    }

    /**
     * 下一个定时运行时刻:严格晚于 now 的最近整点(09:00..18:00 范围内);当日已无则次日 09:00;null → null。
     */
    public static LocalDateTime nextRunAt(LocalDateTime now) {
        if (now == null) {
            return null;
        }
        LocalDate day = now.toLocalDate();
        for (int hour = FIRST_HOUR; hour <= LAST_HOUR; hour++) {
            LocalDateTime tick = day.atTime(hour, 0);
            if (tick.isAfter(now)) {
                return tick;
            }
        }
        return day.plusDays(1).atTime(FIRST_HOUR, 0);
    }

    /** 单轮运行统计(写入 auto_recruit.last_run 摘要) */
    private static final class RoundStats {

        private final String mode;
        private boolean noAccount;
        private boolean riskStopped;
        private int polled;
        private int scored;
        private int greeted;
        private int recommended;
        private int errors;

        private RoundStats(String mode) {
            this.mode = mode;
        }

        private String toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("at", LocalDateTime.now(ZONE).toString());
            node.put("mode", mode);
            node.put("noAccount", noAccount);
            node.put("polled", polled);
            node.put("scored", scored);
            node.put("greeted", greeted);
            node.put("recommended", recommended);
            node.put("errors", errors);
            node.put("riskStopped", riskStopped);
            return node.toString();
        }

        private String summaryText() {
            return "mode=" + mode + ", polled=" + polled + ", scored=" + scored
                    + ", greeted=" + greeted + ", recommended=" + recommended
                    + ", errors=" + errors + (noAccount ? ", noAccount" : "")
                    + (riskStopped ? ", riskStopped" : "");
        }
    }
}
