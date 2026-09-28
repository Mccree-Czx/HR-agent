package com.hragent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hragent.common.BizException;
import com.hragent.entity.AutoRecruitRound;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.entity.SearchTask;
import com.hragent.executor.CliException;
import com.hragent.repository.AppSettingMapper;
import com.hragent.repository.AutoRecruitRoundMapper;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.repository.SearchTaskMapper;
import com.hragent.scoring.ScoringEngine;
import com.hragent.service.AutoRecruitSettingService;
import com.hragent.service.ChatPollService;
import com.hragent.service.GreetingService;
import com.hragent.service.SearchTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AutoRecruitScheduler 单轮编排测试(全部 mock 服务,真实 mapper/H2)。
 * 运行时开关语义(2026-09-26):OFF 只停主动外发——轮次照常(检测/评分/拉推荐),
 * 仅跳过打招呼;互斥:定时重叠跳过、手动拒绝;每轮写摘要。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AutoRecruitSchedulerTest {

    @Autowired
    private AutoRecruitScheduler scheduler;

    @Autowired
    private HrAgentProperties properties;

    @Autowired
    private LiepinAccountMapper accountMapper;

    @Autowired
    private JdMapper jdMapper;

    @Autowired
    private SearchTaskMapper searchTaskMapper;

    @Autowired
    private AppSettingMapper settingMapper;

    @Autowired
    private AutoRecruitRoundMapper roundMapper;

    @Autowired
    private AutoRecruitSettingService settingService;

    @MockitoBean
    private SearchTaskService searchTaskService;

    @MockitoBean
    private ScoringEngine scoringEngine;

    @MockitoBean
    private GreetingService greetingService;

    @MockitoBean
    private ChatPollService chatPollService;

    private Long accountId;

    /** 周一 10:00(运行时段内;每天执行,时段判定与星期无关) */
    private static final LocalDateTime WORK_TIME = LocalDateTime.of(2026, 9, 28, 10, 0);

    @BeforeEach
    void setUp() {
        searchTaskMapper.delete(new LambdaQueryWrapper<>());
        jdMapper.delete(new LambdaQueryWrapper<>());
        accountMapper.delete(new LambdaQueryWrapper<>());
        settingMapper.delete(new LambdaQueryWrapper<>());
        properties.getAutoRecruit().setEnabled(true); // 种子默认开启(库清空后由 isEnabled() 落库)
        properties.getAutoRecruit().setGreetBatchLimit(5);
        accountId = null;
    }

    private Long createAccount() {
        LiepinAccount account = new LiepinAccount();
        account.setName("测试账号");
        account.setLoginStatus("NORMAL");
        account.setCircuitBreaker(false);
        accountMapper.insert(account);
        accountId = account.getId();
        return accountId;
    }

    private Jd createActiveJd(String liepinJobId) {
        Jd jd = new Jd();
        jd.setTitle("Java 后端工程师");
        jd.setStatus("ACTIVE");
        jd.setLiepinJobId(liepinJobId);
        jdMapper.insert(jd);
        return jd;
    }

    private void insertTask(Long jdId, String status) {
        SearchTask task = new SearchTask();
        task.setJdId(jdId);
        task.setAccountId(accountId);
        task.setTaskType("RECOMMEND");
        task.setKeywords("t");
        task.setStatus(status);
        task.setRetryCount(0);
        searchTaskMapper.insert(task);
    }

    // ---------- isRunWindow / nextRunAt 边界 ----------

    @Test
    void isRunWindowBoundaries() {
        // 2026-09-25 周五 / 09-26 周六 / 09-27 周日 / 09-28 周一
        assertTrue(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 25, 18, 59)), "周五18:59应为true");
        assertTrue(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 25, 9, 0)), "周五9:00应为true");
        assertFalse(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 25, 19, 0)), "周五19:00应为false");
        assertTrue(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 26, 10, 0)), "周六应为true(每天执行)");
        assertTrue(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 27, 9, 0)), "周日应为true(每天执行)");
        assertTrue(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 28, 9, 0)), "周一9:00应为true");
        assertFalse(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 28, 8, 59)), "周一8:59应为false");
        assertFalse(AutoRecruitScheduler.isRunWindow(LocalDateTime.of(2026, 9, 26, 19, 0)), "周六19:00应为false(时段外)");
        assertFalse(AutoRecruitScheduler.isRunWindow(null), "null应为false");
    }

    @Test
    void nextRunAtBoundaries() {
        assertEquals(LocalDateTime.of(2026, 9, 26, 9, 0),
                AutoRecruitScheduler.nextRunAt(LocalDateTime.of(2026, 9, 26, 8, 59)), "8:59→当日9:00");
        assertEquals(LocalDateTime.of(2026, 9, 26, 10, 0),
                AutoRecruitScheduler.nextRunAt(LocalDateTime.of(2026, 9, 26, 9, 0)), "9:00→10:00(严格晚于)");
        assertEquals(LocalDateTime.of(2026, 9, 26, 18, 0),
                AutoRecruitScheduler.nextRunAt(LocalDateTime.of(2026, 9, 26, 17, 30)), "17:30→18:00");
        assertEquals(LocalDateTime.of(2026, 9, 27, 9, 0),
                AutoRecruitScheduler.nextRunAt(LocalDateTime.of(2026, 9, 26, 18, 0)), "18:00→次日9:00");
        assertEquals(LocalDateTime.of(2026, 9, 27, 9, 0),
                AutoRecruitScheduler.nextRunAt(LocalDateTime.of(2026, 9, 26, 23, 30)), "23:30→次日9:00");
        assertNull(AutoRecruitScheduler.nextRunAt(null), "null→null");
    }

    // ---------- 时段 / 账号门禁 ----------

    @Test
    void outsideRunWindowDoesNothing() {
        createAccount();
        createActiveJd("123");

        scheduler.runRound(LocalDateTime.of(2026, 9, 26, 19, 0)); // 周六 19:00(时段外)

        verify(chatPollService, never()).poll();
        verify(scoringEngine, never()).scorePending(anyLong());
        verify(searchTaskService, never()).createRecommendTask(anyLong(), anyLong());
    }

    @Test
    void noAccountDoesNotCreateTask() {
        createActiveJd("123");

        scheduler.runRound(WORK_TIME);

        verify(chatPollService, never()).poll();
        verify(searchTaskService, never()).createRecommendTask(anyLong(), anyLong());
        JsonNode lastRun = settingService.lastRun().orElseThrow();
        assertTrue(lastRun.path("noAccount").asBoolean(), "无账号轮次摘要应标记 noAccount");
        verify(chatPollService, never()).poll();
    }

    // ---------- 运行时开关:OFF = 只收不联 ----------

    @Test
    void switchOffRunsCollectOnlyMode() {
        settingService.setEnabled(false);
        createAccount();
        Jd jd = createActiveJd("123");

        scheduler.runRound(WORK_TIME);

        // 只收:检测/评分/拉推荐照常
        verify(chatPollService).poll();
        verify(scoringEngine).scorePending(jd.getId());
        verify(searchTaskService).createRecommendTask(jd.getId(), accountId);
        // 不联:打招呼绝不发生
        verify(greetingService, never()).greetPassed(anyLong(), anyInt());
        // 摘要体现只收模式
        JsonNode lastRun = settingService.lastRun().orElseThrow();
        assertEquals("collectOnly", lastRun.path("mode").asText());
        assertEquals(0, lastRun.path("greeted").asInt());
    }

    @Test
    void switchOnRunsFullMode() {
        // 默认种子开启(见 setUp)
        createAccount();
        Jd jd = createActiveJd("123");

        scheduler.runRound(WORK_TIME);

        verify(greetingService).greetPassed(jd.getId(), 5);
        assertEquals("full", settingService.lastRun().orElseThrow().path("mode").asText());
    }

    // ---------- 轮次互斥 ----------

    @Test
    void hourlySkipsWhenRoundAlreadyRunning() {
        createAccount();
        Jd jd1 = createActiveJd("111");
        Jd jd2 = createActiveJd("222");
        // 第一岗评分时嵌套触发一轮(模拟整点重叠)→ 应被互斥挡下
        doAnswer(invocation -> {
            scheduler.runRound(WORK_TIME);
            return 0;
        }).when(scoringEngine).scorePending(jd1.getId());
        // 第二岗评分时嵌套手动触发 → 应抛出"运行中"拒绝
        doAnswer(invocation -> {
            assertThrows(BizException.class, () -> scheduler.runRoundInternal());
            return 0;
        }).when(scoringEngine).scorePending(jd2.getId());

        scheduler.runRound(WORK_TIME);

        verify(chatPollService, times(1)).poll(); // 嵌套轮次未执行
        assertFalse(scheduler.isRunning(), "轮次结束后互斥标志应释放");
        assertNull(scheduler.getRunningSince(), "轮次结束后 runningSince 应为 null");
    }

    // ---------- 防重叠(岗位任务) ----------

    @Test
    void skipCreateWhenTaskInFlight() {
        createAccount();
        Jd jd = createActiveJd("123");
        insertTask(jd.getId(), "QUEUED");

        scheduler.runRound(WORK_TIME);

        // 存量处理仍执行,但不重复创建在途任务
        verify(scoringEngine).scorePending(jd.getId());
        verify(greetingService).greetPassed(jd.getId(), 5);
        verify(searchTaskService, never()).createRecommendTask(anyLong(), anyLong());
    }

    @Test
    void skipCreateWhenTaskRunning() {
        createAccount();
        Jd jd = createActiveJd("123");
        insertTask(jd.getId(), "RUNNING");

        scheduler.runRound(WORK_TIME);

        verify(searchTaskService, never()).createRecommendTask(anyLong(), anyLong());
    }

    // ---------- 顺序:来信 → 补评分 → 打招呼 → 拉新 ----------

    @Test
    void processesStockBeforePullingNew() {
        createAccount();
        Jd jd = createActiveJd("123");

        scheduler.runRound(WORK_TIME);

        InOrder order = inOrder(chatPollService, scoringEngine, greetingService, searchTaskService);
        order.verify(chatPollService).poll();
        order.verify(scoringEngine).scorePending(jd.getId());
        order.verify(greetingService).greetPassed(jd.getId(), 5);
        order.verify(searchTaskService).createRecommendTask(jd.getId(), accountId);
    }

    // ---------- 单岗位失败隔离 ----------

    @Test
    void singleJdFailureDoesNotBlockNextJd() {
        createAccount();
        Jd jd1 = createActiveJd("111");
        Jd jd2 = createActiveJd("222");
        doThrow(new RuntimeException("模拟单岗位异常")).when(scoringEngine).scorePending(jd1.getId());

        scheduler.runRound(WORK_TIME);

        verify(scoringEngine).scorePending(jd1.getId());
        verify(scoringEngine).scorePending(jd2.getId());
        verify(searchTaskService, never()).createRecommendTask(eq(jd1.getId()), anyLong());
        verify(searchTaskService).createRecommendTask(jd2.getId(), accountId);
        assertEquals(1, settingService.lastRun().orElseThrow().path("errors").asInt(),
                "单岗位失败应计入摘要 errors");
    }

    @Test
    void riskControlExceptionPropagates() {
        createAccount();
        Jd jd = createActiveJd("123");
        doThrow(new CliException(CliException.Type.RISK_CONTROL, "安全验证"))
                .when(scoringEngine).scorePending(jd.getId());

        assertThrows(CliException.class, () -> scheduler.runRound(WORK_TIME), "风控异常应上抛(不吞掉)");
        JsonNode lastRun = settingService.lastRun().orElseThrow();
        assertTrue(lastRun.path("riskStopped").asBoolean(), "风控停止应写入摘要");
        assertFalse(scheduler.isRunning(), "异常后互斥标志应释放");
    }

    // ---------- 配置生效 ----------

    @Test
    void greetBatchLimitPassedThrough() {
        createAccount();
        Jd jd = createActiveJd("123");
        properties.getAutoRecruit().setGreetBatchLimit(7);

        scheduler.runRound(WORK_TIME);

        verify(greetingService).greetPassed(jd.getId(), 7);
    }

    @Test
    void invalidLiepinJobIdSkipped() {
        createAccount();
        createActiveJd("abc");

        scheduler.runRound(WORK_TIME);

        verify(scoringEngine, never()).scorePending(anyLong());
        verify(searchTaskService, never()).createRecommendTask(anyLong(), anyLong());
    }

    // ---------- 摘要计数 ----------

    @Test
    void summarySavesCountsAfterRound() {
        createAccount();
        Jd jd = createActiveJd("123");
        when(chatPollService.poll()).thenReturn(7);
        when(scoringEngine.scorePending(jd.getId())).thenReturn(2);
        when(greetingService.greetPassed(jd.getId(), 5)).thenReturn(3);

        scheduler.runRound(WORK_TIME);

        JsonNode lastRun = settingService.lastRun().orElseThrow();
        assertEquals("full", lastRun.path("mode").asText());
        assertEquals(7, lastRun.path("polled").asInt());
        assertEquals(2, lastRun.path("scored").asInt());
        assertEquals(3, lastRun.path("greeted").asInt());
        assertEquals(1, lastRun.path("recommended").asInt());
        assertEquals(0, lastRun.path("errors").asInt());
        assertFalse(lastRun.path("riskStopped").asBoolean());
        assertFalse(lastRun.path("noAccount").asBoolean());
    }

    // ---------- 轮次历史落库(运行日志页,2026-09-28) ----------

    @Test
    void roundHistoryRecordedAfterRun() {
        createAccount();
        Jd jd = createActiveJd("123");
        when(chatPollService.poll()).thenReturn(4);
        when(scoringEngine.scorePending(jd.getId())).thenReturn(1);
        when(greetingService.greetPassed(jd.getId(), 5)).thenReturn(1);

        scheduler.runRound(WORK_TIME);

        List<AutoRecruitRound> rounds = roundMapper.selectList(new LambdaQueryWrapper<>());
        assertEquals(1, rounds.size(), "每轮结束应写入一条轮次历史");
        AutoRecruitRound round = rounds.get(0);
        assertEquals("full", round.getMode());
        assertEquals(4, round.getPolled());
        assertEquals(1, round.getScored());
        assertEquals(1, round.getGreeted());
        assertFalse(round.getRiskStopped());
        assertFalse(round.getNoAccount());
        assertNotNull(round.getFinishedAt());
        assertNotNull(round.getStatsJson());
    }
}
