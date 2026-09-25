package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hragent.common.BizException;
import com.hragent.entity.Jd;
import com.hragent.repository.JdMapper;
import com.hragent.security.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class JdService {

    private final JdMapper jdMapper;
    private final OpLogService opLogService;
    private final UserJdService userJdService;

    public JdService(JdMapper jdMapper, OpLogService opLogService, UserJdService userJdService) {
        this.jdMapper = jdMapper;
        this.opLogService = opLogService;
        this.userJdService = userJdService;
    }

    public IPage<Jd> page(int pageNo, int pageSize, String status) {
        LambdaQueryWrapper<Jd> qw = new LambdaQueryWrapper<Jd>()
                .eq(status != null && !status.isBlank(), Jd::getStatus, status)
                .orderByDesc(Jd::getId);
        // 权限:非 ADMIN 只可见被分配岗位(评审 P1-8)
        Set<Long> allowed = userJdService.allowedJdIds(
                UserContext.get().getUserId(), UserContext.get().getRole());
        if (allowed != null) {
            qw.in(allowed.isEmpty(), Jd::getId, -1L);
            if (!allowed.isEmpty()) {
                qw.in(Jd::getId, allowed);
            }
        }
        return jdMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    public Jd get(Long id) {
        Jd jd = jdMapper.selectById(id);
        if (jd == null) {
            throw BizException.notFound("岗位不存在");
        }
        return jd;
    }

    @Transactional
    public Jd create(Jd jd) {
        jd.setId(null);
        jd.setStatus("ACTIVE");
        stripThresholdConfirmation(jd);
        jdMapper.insert(jd);
        opLogService.log("CREATE", "jd", jd.getId(), "创建岗位: " + jd.getTitle());
        return get(jd.getId());
    }

    @Transactional
    public Jd update(Long id, Jd jd) {
        get(id);
        jd.setId(id);
        stripThresholdConfirmation(jd);
        jdMapper.updateById(jd);
        opLogService.log("UPDATE", "jd", id, "更新岗位: " + jd.getTitle());
        return get(id);
    }

    /**
     * 屏蔽通用 JD 接口对门槛确认字段的写入(评审 Critical-1):
     * 门槛只能经 {@link JdThresholdService#confirm} 写入,防止任一登录用户经 POST/PUT /api/jd
     * 直接注入 scoreThreshold/thresholdConfirmedAt 绕过确认校验放行自动外发;
     * updateById 忽略 null 字段,故不会误清既有确认值。
     */
    private void stripThresholdConfirmation(Jd jd) {
        jd.setScoreThreshold(null);
        jd.setThresholdSuggestion(null);
        jd.setThresholdConfirmedBy(null);
        jd.setThresholdConfirmedAt(null);
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        jdMapper.deleteById(id);
        opLogService.log("DELETE", "jd", id, "删除岗位");
    }
}
