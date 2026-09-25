package com.hragent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hragent.common.ApiResponse;
import com.hragent.entity.Jd;
import com.hragent.service.JdPublishService;
import com.hragent.service.JdService;
import com.hragent.service.LiepinJobSyncService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jd")
public class JdController {

    private final JdService jdService;
    private final JdPublishService jdPublishService;
    private final LiepinJobSyncService jobSyncService;

    public JdController(JdService jdService, JdPublishService jdPublishService,
                        LiepinJobSyncService jobSyncService) {
        this.jdService = jdService;
        this.jdPublishService = jdPublishService;
        this.jobSyncService = jobSyncService;
    }

    @GetMapping
    public ApiResponse<IPage<Jd>> page(@RequestParam(defaultValue = "1") int pageNo,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) String status) {
        return ApiResponse.ok(jdService.page(pageNo, pageSize, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Jd> get(@PathVariable Long id) {
        return ApiResponse.ok(jdService.get(id));
    }

    @PostMapping
    public ApiResponse<Jd> create(@Valid @RequestBody Jd jd) {
        return ApiResponse.ok(jdService.create(jd));
    }

    @PutMapping("/{id}")
    public ApiResponse<Jd> update(@PathVariable Long id, @RequestBody Jd jd) {
        return ApiResponse.ok(jdService.update(id, jd));
    }

    /** 删除岗位:有关联猎聘职位时同步删除猎聘职位(先删猎聘成功后删系统记录) */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        jdPublishService.deleteWithSync(id);
        return ApiResponse.ok(null);
    }

    /** 发布到猎聘(草稿→正式上线;已发布拒绝重复;import 是公开不可逆动作) */
    @PostMapping("/{id}/publish")
    public ApiResponse<Jd> publish(@PathVariable Long id) {
        return ApiResponse.ok(jdPublishService.publish(id));
    }

    /** 同步猎聘在招职位到系统岗位管理(按职位 ID 幂等) */
    @PostMapping("/sync-liepin")
    public ApiResponse<java.util.Map<String, Integer>> syncLiepin(
            @RequestParam(required = false) Long accountId) {
        return ApiResponse.ok(jobSyncService.sync(accountId));
    }
}
