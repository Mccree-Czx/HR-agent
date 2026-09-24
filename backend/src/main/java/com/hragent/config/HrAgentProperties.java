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

    private Ai ai = new Ai();

    private Scoring scoring = new Scoring();

    private Storage storage = new Storage();

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

    @Data
    public static class Ai {

        /** OpenAI 兼容接口 baseUrl(DeepSeek/Qwen/GLM 等) */
        private String baseUrl = "https://api.deepseek.com";

        private String apiKey = "";

        private String model = "deepseek-chat";

        /** 单次调用超时(秒) */
        private int timeoutSeconds = 120;
    }

    @Data
    public static class Scoring {

        /** 评分细则版本号(细则变更时更新) */
        private String ruleVersion = "v0.1-placeholder";

        /** 通过阈值(0-100) */
        private int passThreshold = 60;

        /** 评分 Agent 系统提示词文件 */
        private String promptFile = "classpath:agents/resume-scorer.md";

        /** JSON 解析失败最大重试次数 */
        private int maxParseRetry = 2;

        /** 打招呼节奏:同账号两次打招呼最小间隔(秒) */
        private int greetIntervalSeconds = 30;
    }

    @Data
    public static class Storage {

        /** 存储实现类型:minio(默认)/local */
        private String type = "minio";

        /** 本地存储根目录(type=local 时生效) */
        private String localBaseDir = System.getProperty("user.home") + "/hr-agent/resumes";

        private Minio minio = new Minio();

        @Data
        public static class Minio {

            private String endpoint = "http://localhost:9000";

            private String accessKey = "hr-agent-minio";

            private String secretKey = "hr-agent-minio-pass";

            private String bucket = "resumes";
        }
    }
}
