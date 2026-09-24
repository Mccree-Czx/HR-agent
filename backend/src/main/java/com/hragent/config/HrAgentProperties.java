package com.hragent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "hr-agent")
public class HrAgentProperties {

    private Jwt jwt = new Jwt();

    private Auth auth = new Auth();

    private Liepin liepin = new Liepin();

    @Data
    public static class Jwt {

        private String secret;

        private long expireHours = 12;
    }

    @Data
    public static class Auth {

        /** 无 token 时放行的路径 */
        private List<String> whitelist = new ArrayList<>();
    }

    @Data
    public static class Liepin {

        /** liepin-cli 可执行文件路径(默认从 PATH 找 liepin) */
        private String cliPath = "liepin";

        /** 多账号 user-data-dir 根目录 */
        private String dataDirBase = System.getProperty("user.home") + "/.liepin-cli/profiles";

        /** 搜索命令超时(分钟) */
        private int searchTimeoutMinutes = 8;

        /** 简历/打招呼等短命令超时(分钟) */
        private int shortTimeoutMinutes = 3;
    }
}
