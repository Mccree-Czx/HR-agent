package com.hragent.controller;

import com.hragent.common.ApiResponse;
import com.hragent.config.AutoRecruitScheduler;
import com.hragent.security.RequireRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动招聘闭环手动触发端点(ADMIN),用于联调/停机或非工作时段手动补跑一轮。
 *
 * <p>与 {@link AutoRecruitScheduler} 同受 {@code hr-agent.auto-recruit.enabled} 开关约束:
 * 仅当功能开启(调度器 bean 存在)时端点才注册;触发时同步执行
 * {@link AutoRecruitScheduler#runRoundInternal()},绕过工作时段门禁但不绕过总开关。
 * 同步执行可能耗时较长(多岗位推荐+评分+打招呼),联调期可接受。</p>
 */
@RestController
@RequestMapping("/api/auto-recruit")
@RequireRole("ADMIN")
@ConditionalOnProperty(name = "hr-agent.auto-recruit.enabled", havingValue = "true", matchIfMissing = false)
public class AutoRecruitController {

    private final AutoRecruitScheduler autoRecruitScheduler;

    public AutoRecruitController(AutoRecruitScheduler autoRecruitScheduler) {
        this.autoRecruitScheduler = autoRecruitScheduler;
    }

    /** 手动执行一轮自动招聘编排(绕过时段门禁) */
    @PostMapping("/run-once")
    public ApiResponse<Void> runOnce() {
        autoRecruitScheduler.runRoundInternal();
        return ApiResponse.ok(null);
    }
}
