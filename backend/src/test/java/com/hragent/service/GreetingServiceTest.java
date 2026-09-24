package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hragent.ai.AiClient;
import com.hragent.entity.Candidate;
import com.hragent.entity.GreetingRecord;
import com.hragent.entity.LiepinAccount;
import com.hragent.repository.CandidateMapper;
import com.hragent.repository.GreetingRecordMapper;
import com.hragent.repository.LiepinAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GreetingServiceTest {

    @Autowired
    private GreetingService greetingService;

    @Autowired
    private CandidateMapper candidateMapper;

    @Autowired
    private LiepinAccountMapper accountMapper;

    @Autowired
    private GreetingRecordMapper greetingMapper;

    @MockitoBean
    private LiepinCommandService commandService;

    @MockitoBean
    private AiClient aiClient;

    private LiepinAccount account;

    @BeforeEach
    void setUp() {
        greetingMapper.delete(new LambdaQueryWrapper<>());
        candidateMapper.delete(new LambdaQueryWrapper<>());
        accountMapper.delete(new LambdaQueryWrapper<>());

        account = new LiepinAccount();
        account.setName("测试账号");
        account.setLoginStatus("NORMAL");
        account.setCircuitBreaker(false);
        account.setGreetMode("AUTO");
        account.setDailyGreetQuota(20);
        accountMapper.insert(account);

        when(aiClient.chat(anyString(), anyString())).thenReturn("您好,看到您的背景很匹配,方便聊聊吗");
    }

    private Candidate candidate(long id) {
        Candidate c = new Candidate();
        c.setResumeId("r" + id);
        c.setName("候选人" + id);
        c.setSnapshot("{\"name\":\"候选人" + id + "\",\"salary\":\"20-30K\"}");
        c.setPassStatus("PASS");
        c.setJdId(1L);
        candidateMapper.insert(c);
        return c;
    }

    @Test
    void autoModeSendsGreeting() {
        Candidate c = candidate(1);
        boolean done = greetingService.tryGreet(account, c);

        assertTrue(done);
        verify(commandService).greet(any(LiepinAccount.class), anyString(), anyString(), any());
        assertEquals(1, greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getStatus, "SENT")));
    }

    @Test
    void duplicateContactPrevented() {
        Candidate c = candidate(1);
        assertTrue(greetingService.tryGreet(account, c), "第一次应发送");
        assertFalse(greetingService.tryGreet(account, c), "第二次应被防重复拦截");

        verify(commandService).greet(any(LiepinAccount.class), anyString(), anyString(), any());
        assertEquals(1, greetingMapper.selectCount(null));
    }

    @Test
    void manualModeOnlyCreatesPendingRecord() {
        account.setGreetMode("MANUAL");
        accountMapper.updateById(account);

        Candidate c = candidate(1);
        boolean done = greetingService.tryGreet(account, c);

        assertTrue(done);
        verify(commandService, never()).greet(any(), anyString(), anyString(), any());
        assertEquals(1, greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getStatus, "PENDING_CONFIRM")));
    }

    @Test
    void quotaExhaustedStopsSending() {
        account.setDailyGreetQuota(1);
        accountMapper.updateById(account);

        Candidate c1 = candidate(1);
        Candidate c2 = candidate(2);
        assertTrue(greetingService.tryGreet(account, c1), "配额内应发送");
        assertFalse(greetingService.tryGreet(account, c2), "超配额应拒绝");

        assertEquals(1, greetingMapper.selectCount(null));
    }

    @Test
    void greetPassedFullFlow() {
        Candidate c1 = candidate(1);
        Candidate c2 = candidate(2);
        int created = greetingService.greetPassed(1L, 10);

        assertEquals(2, created);
        assertEquals(2, greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getStatus, "SENT")));
    }

    @Test
    void sendFailureRecordsAndAllowsRetry() {
        // 首次发送失败(如候选人隐私保护)
        when(commandService.greet(any(), anyString(), anyString(), any()))
                .thenThrow(new com.hragent.executor.CliException(
                        com.hragent.executor.CliException.Type.FAILED, "该人选设置了隐私保护,无法开聊"));
        Candidate c = candidate(1);
        assertFalse(greetingService.tryGreet(account, c), "发送失败应返回 false");
        assertEquals(1, greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getStatus, "SEND_FAILED")));

        // 第二次恢复可发送:应允许重试并更新为 SENT(不新增记录)
        // 用 doReturn 风格覆盖 thenThrow stub(when() 风格会触发上一条 thenThrow)
        org.mockito.Mockito.doReturn(java.util.Optional.empty())
                .when(commandService).greet(any(), anyString(), anyString(), any());
        assertTrue(greetingService.tryGreet(account, c), "失败后可重试");
        assertEquals(1, greetingMapper.selectCount(null), "重试应更新而非新增");
        assertEquals(1, greetingMapper.selectCount(new LambdaQueryWrapper<GreetingRecord>()
                .eq(GreetingRecord::getStatus, "SENT")));
    }
}
