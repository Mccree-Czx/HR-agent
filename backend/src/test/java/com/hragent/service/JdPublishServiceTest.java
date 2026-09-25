package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.common.BizException;
import com.hragent.entity.Jd;
import com.hragent.entity.LiepinAccount;
import com.hragent.repository.JdMapper;
import com.hragent.repository.LiepinAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JdPublishServiceTest {

    @Autowired
    private JdPublishService jdPublishService;

    @Autowired
    private JdMapper jdMapper;

    @Autowired
    private LiepinAccountMapper accountMapper;

    @MockitoBean
    private LiepinCommandService commandService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdMapper.delete(new LambdaQueryWrapper<>());
        accountMapper.delete(new LambdaQueryWrapper<>());

        LiepinAccount account = new LiepinAccount();
        account.setName("测试账号");
        account.setLoginStatus("NORMAL");
        account.setCircuitBreaker(false);
        accountMapper.insert(account);
    }

    private Jd createJd() {
        Jd jd = new Jd();
        jd.setTitle("招聘主管");
        jd.setExternalJd("负责招聘全流程");
        jd.setJobCategory("N000330");
        jd.setExperienceReq("5-10年");
        jd.setDegreeReq("本科");
        jd.setSalaryMonths(13);
        jd.setSalaryMin(12000);
        jd.setSalaryMax(18000);
        jd.setPublishStatus("NOT_PUBLISHED");
        jd.setSource("LOCAL");
        jdMapper.insert(jd);
        return jd;
    }

    @Test
    void publishSuccessBackfillsJobId() throws Exception {
        when(commandService.jobPublish(any(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(objectMapper.readTree(
                        "{\"success\":true,\"job_id\":\"85869999\",\"message\":\"职位已发布\"}")));

        Jd jd = createJd();
        Jd result = jdPublishService.publish(jd.getId());

        assertEquals("PUBLISHED", result.getPublishStatus());
        assertEquals("85869999", result.getLiepinJobId());
        Jd after = jdMapper.selectById(jd.getId());
        assertEquals("PUBLISHED", after.getPublishStatus());
    }

    @Test
    void publishRejectedWhenAlreadyPublished() {
        Jd jd = createJd();
        jd.setPublishStatus("PUBLISHED");
        jd.setLiepinJobId("85027561");
        jdMapper.updateById(jd);

        BizException e = assertThrows(BizException.class, () -> jdPublishService.publish(jd.getId()));
        assertTrue(e.getMessage().contains("不可重复发布"));
    }

    @Test
    void publishFailedRecordsError() throws Exception {
        when(commandService.jobPublish(any(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(objectMapper.readTree(
                        "{\"success\":false,\"message\":\"类别无效\"}")));

        Jd jd = createJd();
        assertThrows(BizException.class, () -> jdPublishService.publish(jd.getId()));
        Jd after = jdMapper.selectById(jd.getId());
        assertEquals("FAILED", after.getPublishStatus());
        assertTrue(after.getPublishError().contains("类别无效"));
    }

    @Test
    void parseExperience() {
        assertArrayEquals(new int[]{5, 10}, jdPublishService.parseExperience("5-10年"));
        assertArrayEquals(new int[]{3, 5}, jdPublishService.parseExperience("3-5年"));
        assertArrayEquals(new int[]{0, 99}, jdPublishService.parseExperience("不限"));
        assertArrayEquals(new int[]{0, 99}, jdPublishService.parseExperience(null));
    }

    @Test
    void resolveDegreeCode() {
        assertEquals("040", jdPublishService.resolveDegreeCode("本科"));
        assertEquals("040", jdPublishService.resolveDegreeCode(null));
        assertEquals("050", jdPublishService.resolveDegreeCode("050"));
    }

    @Test
    void deleteWithoutLiepinJobIdDirectlyRemoves() {
        Jd jd = createJd();
        jdPublishService.deleteWithSync(jd.getId());
        assertEquals(0, jdMapper.selectCount(null));
    }

    @Test
    void deleteWithLiepinJobIdSyncsToLiepin() throws Exception {
        when(commandService.jobDelete(any(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(objectMapper.readTree(
                        "{\"success\":true,\"deleted\":[\"85869999\"],\"message\":\"已删除 1 个职位\"}")));

        Jd jd = createJd();
        jd.setLiepinJobId("85869999");
        jd.setPublishStatus("PUBLISHED");
        jdMapper.updateById(jd);

        jdPublishService.deleteWithSync(jd.getId());
        assertEquals(0, jdMapper.selectCount(null), "猎聘删除成功后应删系统记录");
    }

    @Test
    void deleteAbortsAndKeepsRecordWhenLiepinFails() throws Exception {
        when(commandService.jobDelete(any(), anyString(), any(Duration.class)))
                .thenReturn(Optional.of(objectMapper.readTree(
                        "{\"success\":false,\"message\":\"网络错误\"}")));

        Jd jd = createJd();
        jd.setLiepinJobId("85869999");
        jd.setPublishStatus("PUBLISHED");
        jdMapper.updateById(jd);

        assertThrows(BizException.class, () -> jdPublishService.deleteWithSync(jd.getId()));
        assertEquals(1, jdMapper.selectCount(null), "猎聘删除失败时应保留系统记录");
    }
}
