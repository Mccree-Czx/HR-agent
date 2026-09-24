package com.hragent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hragent.common.BizException;
import com.hragent.entity.Jd;
import com.hragent.repository.JdMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdService {

    private final JdMapper jdMapper;
    private final OpLogService opLogService;

    public JdService(JdMapper jdMapper, OpLogService opLogService) {
        this.jdMapper = jdMapper;
        this.opLogService = opLogService;
    }

    public IPage<Jd> page(int pageNo, int pageSize, String status) {
        LambdaQueryWrapper<Jd> qw = new LambdaQueryWrapper<Jd>()
                .eq(status != null && !status.isBlank(), Jd::getStatus, status)
                .orderByDesc(Jd::getId);
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
        jdMapper.insert(jd);
        opLogService.log("CREATE", "jd", jd.getId(), "创建岗位: " + jd.getTitle());
        return get(jd.getId());
    }

    @Transactional
    public Jd update(Long id, Jd jd) {
        get(id);
        jd.setId(id);
        jdMapper.updateById(jd);
        opLogService.log("UPDATE", "jd", id, "更新岗位: " + jd.getTitle());
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        jdMapper.deleteById(id);
        opLogService.log("DELETE", "jd", id, "删除岗位");
    }
}
