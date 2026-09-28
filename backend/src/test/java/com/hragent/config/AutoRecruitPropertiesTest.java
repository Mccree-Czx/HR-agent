package com.hragent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自动招聘配置默认值契约:打招呼单轮上限 2026-09-28 熔断治理降档为 15(原 50,更早为 5)。
 * 该断言锁定定档值——防止无意回退(50=过激会触发平台风控;5=过小拖慢闭环)。
 */
@SpringBootTest
@ActiveProfiles("test")
class AutoRecruitPropertiesTest {

    @Autowired
    private HrAgentProperties properties;

    @Test
    void greetBatchLimitDefaultsToNegotiatedValue() {
        assertEquals(15, properties.getAutoRecruit().getGreetBatchLimit(),
                "单轮打招呼上限默认应为 15(2026-09-28 熔断治理均衡档)");
    }
}
