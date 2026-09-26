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

    private Notify notify = new Notify();

    private AutoRecruit autoRecruit = new AutoRecruit();

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

        /** 搜索命令超时(分钟,200 条需翻页耗时较长) */
        private int searchTimeoutMinutes = 15;

        /** 简历/打招呼等短命令超时(分钟) */
        private int shortTimeoutMinutes = 3;

        /** 单次搜索返回候选人上限(实测翻页 200 会触发风控安全验证,50 为安全区间) */
        private int searchLimit = 50;
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

    @Data
    public static class AutoRecruit {

        /** 自动招聘闭环总开关(默认关闭,需启动参数显式开启) */
        private boolean enabled = false;

        /** 单轮单岗位打招呼上限(取消小批量保护,近似放开:符合即打招呼;真实处理量由详情读取与推荐入口决定,可配置) */
        private int greetBatchLimit = 50;

        /** 单轮「在线简历详情」读取上限(只读平台调用,与推荐批次同量级,默认 20) */
        private int resumeDetailBatchLimit = 20;

        /** 相邻两次简历详情读取的最小间隔(毫秒,读操作轻节流,默认 1000) */
        private int resumeDetailIntervalMillis = 1000;
    }

    @Data
    public static class Notify {

        /** 飞书自定义机器人 webhook(留空则仅日志告警) */
        private String feishuWebhook = "";

        /** 飞书机器人签名密钥(机器人开启加签时必填,未开启留空) */
        private String feishuSecret = "";
    }
}
