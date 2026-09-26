package com.hragent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自动招聘配置默认值契约:打招呼单轮上限已放宽为 50(原 5,取消小批量保护)。
 * 该断言防止默认值回退到小批量——回退会导致"评分符合即打招呼"无法生效。
 */
@SpringBootTest
@ActiveProfiles("test")
class AutoRecruitPropertiesTest {

    @Autowired
    private HrAgentProperties properties;

    @Test
    void greetBatchLimitDefaultsToRelaxedValue() {
        assertEquals(50, properties.getAutoRecruit().getGreetBatchLimit(),
                "单轮打招呼上限默认应为 50(取消小批量保护,符合即打招呼)");
    }
}
