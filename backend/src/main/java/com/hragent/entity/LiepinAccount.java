package com.hragent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("liepin_account")
public class LiepinAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String userDataDir;

    private String loginStatus;

    private Boolean circuitBreaker;

    private String greetMode;

    private Integer dailyGreetQuota;

    /** 熔断恢复(重置)时刻:冷却窗口判断用(2026-09-28) */
    private LocalDateTime riskResetAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
