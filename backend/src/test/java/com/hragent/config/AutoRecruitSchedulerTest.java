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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AutoRecruitScheduler 单轮编排测试(全部 mock 服务,真实 mapper/H2)。
 * 开启开关:通过 @SpringBootTest properties 使 @ConditionalOnProperty 生效并创建调度器 bean,
 * 再在用例内运行时切换 properties.autoRecruit.enabled 验证关闭分支。
 */
@SpringBootTest(properties = "hr-agent.auto-recruit.enabled=true")
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
        properties.getAutoRecruit().setEnabled(true);
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

    // ---------- isRunWindow 边界 ----------

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

    // ---------- 开关 / 时段 / 账务门禁 ----------

    @Test
    void disabledDoesNothing() {
        properties.getAutoRecruit().setEnabled(false);
        scheduler.runRound(WORK_TIME);

        verify(chatPollService, never()).poll();
        verify(scoringEngine, never()).scorePending(anyLong());
        verify(greetingService, never()).greetPassed(anyLong(), anyInt());
        verify(searchTaskService, never()).createRecommendTask(anyLong(), anyLong());
    }

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
    }

    // ---------- 防重叠 ----------

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
    }

    @Test
    void riskControlExceptionPropagates() {
        createAccount();
        Jd jd = createActiveJd("123");
        doThrow(new CliException(CliException.Type.RISK_CONTROL, "安全验证"))
                .when(scoringEngine).scorePending(jd.getId());

        assertThrows(CliException.class, () -> scheduler.runRound(WORK_TIME), "风控异常应上抛(不吞掉)");
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
}
