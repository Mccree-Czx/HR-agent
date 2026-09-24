package com.hragent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hragent.common.ApiResponse;
import com.hragent.dto.CreateUserRequest;
import com.hragent.entity.SysUser;
import com.hragent.security.RequireRole;
import com.hragent.service.UserService;
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
@RequestMapping("/api/user")
@RequireRole("ADMIN")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ApiResponse<IPage<SysUser>> page(@RequestParam(defaultValue = "1") int pageNo,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(userService.page(pageNo, pageSize));
    }

    @PostMapping
    public ApiResponse<SysUser> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userService.create(request.getUsername(), request.getPassword(), request.getRole()));
    }

    @PutMapping("/{id}")
    public ApiResponse<SysUser> update(@PathVariable Long id, @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userService.update(id, request.getRole(), request.getPassword()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.ok(null);
    }
}
