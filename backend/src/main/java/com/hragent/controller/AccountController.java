package com.hragent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hragent.common.ApiResponse;
import com.hragent.entity.LiepinAccount;
import com.hragent.service.AccountService;
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
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public ApiResponse<IPage<LiepinAccount>> page(@RequestParam(defaultValue = "1") int pageNo,
                                                  @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(accountService.page(pageNo, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<LiepinAccount> get(@PathVariable Long id) {
        return ApiResponse.ok(accountService.get(id));
    }

    @PostMapping
    public ApiResponse<LiepinAccount> create(@RequestBody LiepinAccount account) {
        return ApiResponse.ok(accountService.create(account));
    }

    @PutMapping("/{id}")
    public ApiResponse<LiepinAccount> update(@PathVariable Long id, @RequestBody LiepinAccount account) {
        return ApiResponse.ok(accountService.update(id, account));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ApiResponse.ok(null);
    }
}
