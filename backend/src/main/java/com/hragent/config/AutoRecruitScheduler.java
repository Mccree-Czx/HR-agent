package com.hragent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.SearchTask;
import com.hragent.executor.CliException;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.SearchTaskMapper;
import com.hragent.scoring.ScoringEngine;
import com.hragent.service.ChatPollService;
import com.hragent.service.GreetingService;
import com.hragent.service.SearchTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 自动招聘闭环定时编排(设计 §2/§4.2):
 * <ul>
 *     <li>调度:北京时间周一至周五 09:00–18:00 每整点执行一轮(含 18:00),停机期间不补跑</li>
 *     <li>单轮顺序:先来信轮询(被动)→ 逐岗位消费存量(补评分/打招呼)→ 再拉新推荐(主动)</li>
 *     <li>防重叠:岗位已有 QUEUED/RUNNING 任务则本轮跳过,任务经既有 search_task 队列串行执行</li>
 *     <li>异常:单岗位失败不阻断其他岗位;风控类 {@link CliException} 立即停止本轮并上抛(既有熔断链路处理)</li>
 *     <li>开关:{@code hr-agent.auto-recruit.enabled} 默认关闭,需启动参数显式开启</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hr-agent.auto-recruit.enabled", havingValue = "true", matchIfMissing = false)
public class AutoRecruitScheduler {

    /** 工作时段时区(北京时间) */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 工作时段起点(含) */
    private static final LocalTime WINDOW_START = LocalTime.of(9, 0);
    /** 工作时段终点(含),整点触发由 cron 保证 */
    private static final LocalTime WINDOW_END = LocalTime.of(18, 59, 59);

    private final HrAgentProperties properties;
    private final LiepinAccountMapper accountMapper;
    private final JdMapper jdMapper;
    private final SearchTaskMapper searchTaskMapper;
    private final SearchTaskService searchTaskService;
    private final ScoringEngine scoringEngine;
    private final GreetingService greetingService;
    private final ChatPollService chatPollService;

    public AutoRecruitScheduler(HrAgentProperties properties, LiepinAccountMapper accountMapper,
                                JdMapper jdMapper, SearchTaskMapper searchTaskMapper,
                                SearchTaskService searchTaskService, ScoringEngine scoringEngine,
                                GreetingService greetingService, ChatPollService chatPollService) {
        this.properties = properties;
        this.accountMapper = accountMapper;
        this.jdMapper = jdMapper;
        this.searchTaskMapper = searchTaskMapper;
        this.searchTaskService = searchTaskService;
        this.scoringEngine = scoringEngine;
        this.greetingService = greetingService;
        this.chatPollService = chatPollService;
    }

    /** 工作日 09:00–18:00 每整点触发一轮 */
    @Scheduled(cron = "0 0 9-18 * * MON-FRI", zone = "Asia/Shanghai")
    public void hourly() {
        runRound();
    }

    /** 执行一轮(对外可直调;时间取北京时间当前时刻) */
    public void runRound() {
        runRound(LocalDateTime.now(ZONE));
    }

    /**
     * 执行一轮(时间可注入,便于测试边界)。
     * 顺序:开关/时段门禁 → 取 NORMAL 账号 → 来信轮询 → 逐岗位(补评分 → 打招呼 → 防重叠后建推荐任务)。
     */
    void runRound(LocalDateTime now) {
        // 1. 开关门禁:未显式开启则直退
        if (!properties.getAutoRecruit().isEnabled()) {
            log.debug("自动招聘未开启(hr-agent.auto-recruit.enabled=false),本轮跳过");
            return;
        }
        // 2. 时段门禁:仅工作日 09:00–18:59
        if (!isWorkWindow(now)) {
            log.debug("非工作时段({}),本轮跳过", now);
            return;
        }
        // 3. 取第一个 NORMAL 账号(多账号轮询分配待确认,设计 §7)
        LiepinAccount account = accountMapper.selectOne(new LambdaQueryWrapper<LiepinAccount>()
                .eq(LiepinAccount::getLoginStatus, "NORMAL")
                .orderByAsc(LiepinAccount::getId)
                .last("LIMIT 1"));
        if (account == null) {
            log.warn("自动招聘:无可用猎聘账号(login_status=NORMAL),本轮跳过");
            return;
        }

        // 步骤 1:先处理来信与附件(被动通道)
        chatPollService.poll();

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
                scoringEngine.scorePending(jd.getId());
                greetingService.greetPassed(jd.getId(), properties.getAutoRecruit().getGreetBatchLimit());

                // 步骤 3:再拉新推荐(该岗位已有在途任务则跳过,防重叠)
                if (hasActiveTask(jd.getId())) {
                    log.info("自动招聘:岗位 {} 已有在途(QUEUED/RUNNING)任务,本轮跳过拉新", jd.getId());
                    continue;
                }
                searchTaskService.createRecommendTask(jd.getId(), account.getId());
            } catch (CliException e) {
                // 风控/登录失效类:记录并以异常上抛,由既有熔断链路处理,停止本轮
                if (e.getType() == CliException.Type.RISK_CONTROL) {
                    log.error("自动招聘:岗位 {} 触发风控,本轮立即停止", jd.getId(), e);
                    throw e;
                }
                log.warn("自动招聘:岗位 {} 处理失败,跳过: {}", jd.getId(), e.getMessage());
            } catch (Exception e) {
                // 单岗位失败不阻断其他岗位
                log.warn("自动招聘:岗位 {} 处理异常,跳过: {}", jd.getId(), e.getMessage(), e);
            }
        }
    }

    /** 该岗位是否已有排队/运行中的任务(防重叠) */
    private boolean hasActiveTask(Long jdId) {
        Long count = searchTaskMapper.selectCount(new LambdaQueryWrapper<SearchTask>()
                .eq(SearchTask::getJdId, jdId)
                .in(SearchTask::getStatus, "QUEUED", "RUNNING"));
        return count != null && count > 0;
    }

    /**
     * 工作时段判定:周一至周五 09:00–18:59(Asia/Shanghai 时区)返回 true;周六日或时段外 false。
     */
    static boolean isWorkWindow(LocalDateTime now) {
        if (now == null) {
            return false;
        }
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(WINDOW_START) && !time.isAfter(WINDOW_END);
    }
}
