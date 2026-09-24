package com.hragent.executor;

import com.hragent.config.HrAgentProperties;
import com.hragent.entity.LiepinAccount;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinCliExecutorTest {

    private static String fakeScript;

    private static HrAgentProperties properties;

    @BeforeAll
    static void setUp() throws Exception {
        File script = new File("src/test/resources/scripts/fake-liepin.sh");
        // 确保可执行权限(maven 资源复制不保留执行位)
        script.setExecutable(true);
        fakeScript = script.getAbsolutePath();

        properties = new HrAgentProperties();
        properties.getLiepin().setCliPath(fakeScript);
    }

    private LiepinAccount account(long id) {
        LiepinAccount account = new LiepinAccount();
        account.setId(id);
        account.setName("测试账号" + id);
        return account;
    }

    private LiepinCliExecutor newExecutor() {
        return new LiepinCliExecutor(properties);
    }

    @Test
    void executeSuccess() throws Exception {
        CliResult result = newExecutor().execute(account(1), Duration.ofSeconds(10), "search", "java", "--json");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("默认候选人"));
        assertTrue(result.stdout().contains("r0"));
    }

    @Test
    void executeTimeout() throws Exception {
        CliResult result = newExecutor().execute(account(1), Duration.ofSeconds(2), "sleep-long");
        assertTrue(result.timedOut());
    }

    @Test
    void checkRiskDetectsCaptcha() {
        LiepinCliExecutor executor = newExecutor();
        CliResult riskResult = new CliResult(0, "302 to safe.liepin.com/captchaPage_PC 行为异常", "", false);
        CliException e = assertThrows(CliException.class, () -> executor.checkRisk(account(1), riskResult));
        assertEquals(CliException.Type.RISK_CONTROL, e.getType());
    }

    @Test
    void checkRiskDetectsNotLoggedIn() {
        LiepinCliExecutor executor = newExecutor();
        CliResult result = new CliResult(0, "请先登录后再操作", "", false);
        CliException e = assertThrows(CliException.class, () -> executor.checkRisk(account(1), result));
        assertEquals(CliException.Type.NOT_LOGGED_IN, e.getType());
    }

    @Test
    void checkRiskDetectsNonZeroExit() {
        LiepinCliExecutor executor = newExecutor();
        CliResult result = new CliResult(1, "some error", "", false);
        CliException e = assertThrows(CliException.class, () -> executor.checkRisk(account(1), result));
        assertEquals(CliException.Type.FAILED, e.getType());
    }

    @Test
    void checkRiskDetectsTimeout() {
        LiepinCliExecutor executor = newExecutor();
        CliResult result = new CliResult(-1, "", "", true);
        CliException e = assertThrows(CliException.class, () -> executor.checkRisk(account(1), result));
        assertEquals(CliException.Type.TIMEOUT, e.getType());
    }

    @Test
    void checkRiskPassesNormalOutput() {
        LiepinCliExecutor executor = newExecutor();
        CliResult result = new CliResult(0, "[{\"name\":\"张三\",\"resume_id\":\"r1\"}]", "", false);
        executor.checkRisk(account(1), result); // 不应抛异常
    }
}
