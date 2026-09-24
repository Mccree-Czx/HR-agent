package com.hragent.executor;

import com.hragent.config.HrAgentProperties;
import com.hragent.entity.LiepinAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * liepin-cli 子进程封装。
 *
 * 关键设计(评审 P0-1/风控硬约束):
 * - 每账号独立 LIEPIN_USER_DATA_DIR(登录态隔离)+ 独立 CDP 调试端口(多账号浏览器不冲突)
 * - 有头 Chrome 由 CLI 自身保证(CLI 默认有头,本封装不传任何无头开关)
 * - 统一超时与输出捕获;风控/登录态检测见 {@link #checkRisk}
 */
@Slf4j
@Component
public class LiepinCliExecutor {

    private static final int BASE_DEBUG_PORT = 53471;

    /** 风控拦截特征(命中则账号熔断,评审 P0-3) */
    private static final List<String> RISK_KEYWORDS = List.of(
            "captcha", "verify", "security-check", "safe.liepin.com",
            "行为异常", "安全验证", "滑块验证", "forbidden");

    /** 登录态失效特征 */
    private static final List<String> NOT_LOGGED_KEYWORDS = List.of(
            "未登录", "请先登录", "登录已过期", "need login", "登录页");

    private final HrAgentProperties properties;

    public LiepinCliExecutor(HrAgentProperties properties) {
        this.properties = properties;
    }

    /** 执行 CLI 命令并返回原始结果(不解析) */
    public CliResult execute(LiepinAccount account, Duration timeout, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(properties.getLiepin().getCliPath());
        for (String arg : args) {
            if (arg != null && !arg.isBlank()) {
                command.add(arg);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.environment().put("LIEPIN_USER_DATA_DIR", resolveUserDataDir(account));
        pb.environment().put("LIEPIN_BROWSER_REMOTE_DEBUGGING_PORT",
                String.valueOf(BASE_DEBUG_PORT + (account.getId() == null ? 0 : account.getId() % 1000)));

        log.info("执行 liepin-cli: {} (account={}, dataDir={})",
                String.join(" ", args), account.getId(), resolveUserDataDir(account));

        Process process = pb.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            String partial;
            try {
                partial = new String(process.getInputStream().readAllBytes());
            } catch (IOException e) {
                // destroy 后流可能已关闭,拿不到部分输出不影响超时判定
                partial = "";
            }
            log.warn("liepin-cli 超时(account={}, args={})", account.getId(), String.join(" ", args));
            return new CliResult(-1, partial, "", true);
        }

        String stdout = new String(process.getInputStream().readAllBytes());
        return new CliResult(process.exitValue(), stdout, "", false);
    }

    /**
     * 检测输出中的风控/登录态特征。
     * 风控优先(风控页往往同时含登录提示)。
     */
    public void checkRisk(LiepinAccount account, CliResult result) {
        String output = result.combined().toLowerCase();
        for (String kw : RISK_KEYWORDS) {
            if (output.contains(kw.toLowerCase())) {
                throw new CliException(CliException.Type.RISK_CONTROL,
                        "检测到风控拦截特征 [" + kw + "](account=" + account.getId() + ")");
            }
        }
        for (String kw : NOT_LOGGED_KEYWORDS) {
            if (output.contains(kw.toLowerCase())) {
                throw new CliException(CliException.Type.NOT_LOGGED_IN,
                        "检测到登录态失效特征 [" + kw + "](account=" + account.getId() + ")");
            }
        }
        if (result.timedOut()) {
            throw new CliException(CliException.Type.TIMEOUT,
                    "liepin-cli 执行超时(account=" + account.getId() + ")");
        }
        if (result.exitCode() != 0) {
            throw new CliException(CliException.Type.FAILED,
                    "liepin-cli 非零退出 code=" + result.exitCode()
                            + "(account=" + account.getId() + "): " + truncate(result.combined(), 500));
        }
    }

    private String resolveUserDataDir(LiepinAccount account) {
        if (account.getUserDataDir() != null && !account.getUserDataDir().isBlank()) {
            return account.getUserDataDir();
        }
        return properties.getLiepin().getDataDirBase() + "/account-" + account.getId();
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
