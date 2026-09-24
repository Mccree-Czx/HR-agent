package com.hragent.config;

import com.hragent.entity.LiepinAccount;
import com.hragent.entity.SearchTask;
import com.hragent.repository.LiepinAccountMapper;
import com.hragent.service.SearchTaskService;
import com.hragent.service.TaskQueueService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 搜索任务调度器(单机):
 * - 周期 tick:释放过期租约(进程崩溃兜底)→ 每账号认领一个任务提交线程池
 * - 单账号串行由 {@link TaskQueueService#claim} 保证
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "hr-agent.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SearchTaskScheduler {

    private final TaskQueueService queueService;
    private final SearchTaskService searchTaskService;
    private final LiepinAccountMapper accountMapper;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(4);

    public SearchTaskScheduler(TaskQueueService queueService, SearchTaskService searchTaskService,
                               LiepinAccountMapper accountMapper) {
        this.queueService = queueService;
        this.searchTaskService = searchTaskService;
        this.accountMapper = accountMapper;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void tick() {
        int released = queueService.releaseExpiredLeases();
        if (released > 0) {
            log.warn("释放租约过期的 RUNNING 任务 {} 个(进程崩溃恢复)", released);
        }

        Set<Long> processedAccounts = new HashSet<>();
        List<SearchTask> claimed;
        do {
            claimed = claimOnePerAccount(processedAccounts);
            for (SearchTask task : claimed) {
                workerPool.submit(() -> {
                    try {
                        searchTaskService.execute(task);
                    } catch (Exception e) {
                        log.error("任务 {} 调度执行异常", task.getId(), e);
                    }
                });
            }
        } while (!claimed.isEmpty());
    }

    /** 每账号认领一个任务(跳过本轮已认领的账号) */
    private List<SearchTask> claimOnePerAccount(Set<Long> processedAccounts) {
        List<SearchTask> claimed = new ArrayList<>();
        List<LiepinAccount> accounts = accountMapper.selectList(null);
        for (LiepinAccount account : accounts) {
            if (processedAccounts.contains(account.getId())) {
                continue;
            }
            SearchTask task = queueService.claim(account.getId());
            if (task != null) {
                claimed.add(task);
                processedAccounts.add(account.getId());
            }
        }
        return claimed;
    }

    @PreDestroy
    public void shutdown() {
        workerPool.shutdown();
        try {
            workerPool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
