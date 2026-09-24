package com.hragent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hragent.common.ApiResponse;
import com.hragent.entity.Jd;
import com.hragent.service.JdService;
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

    public JdController(JdService jdService) {
        this.jdService = jdService;
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

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        jdService.delete(id);
        return ApiResponse.ok(null);
    }
}
