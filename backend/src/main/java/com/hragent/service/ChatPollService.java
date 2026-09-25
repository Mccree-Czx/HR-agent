package com.hragent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 来信轮询与附件收集入口(被动通道)。
 *
 * <p>本类为 Task 3 提供的占位实现:仅暴露 {@link #poll()} 供 {@code AutoRecruitScheduler}
 * 每轮调用。真正的会话列表轮询、附件检测下载、陌生来话处理等逻辑由 Task 4 填充。
 */
@Slf4j
@Service
public class ChatPollService {

    /**
     * 轮询来信与附件(每轮总入口,内部单账号串行)。
     *
     * <p>占位实现:当前为空操作,待 Task 4 填充。
     */
    public void poll() {
        // TODO(Task 4): 轮询会话列表(保留 message_id 增量识别) → 附件卡片下载校验入库 → 文本回复评分索要
        log.debug("ChatPollService.poll() 占位实现,尚未启用来信轮询(Task 4 填充)");
    }
}
