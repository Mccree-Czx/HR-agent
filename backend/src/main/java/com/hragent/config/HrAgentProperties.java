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
}
