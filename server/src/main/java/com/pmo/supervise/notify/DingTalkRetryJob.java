package com.pmo.supervise.notify;

import com.pmo.supervise.common.DingTalkStatus;
import com.pmo.supervise.common.ShanghaiClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 钉钉失败重试（TECHNICAL §4.6）：每分钟一轮，串行发送，批内间隔 50ms（≤20 条/秒）；
 * 只更新同一行通知，1/5 分钟退避，最多 3 次。
 */
@Component
public class DingTalkRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DingTalkRetryJob.class);
    private static final int BATCH = 20;

    private final NotificationRepository repository;
    private final NotificationService notificationService;
    private final ShanghaiClock clock;

    public DingTalkRetryJob(NotificationRepository repository, NotificationService notificationService,
                            ShanghaiClock clock) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void retry() {
        List<Notification> candidates = new ArrayList<>();
        candidates.addAll(repository.findRetryable(DingTalkStatus.PENDING,
                clock.now(), PageRequest.of(0, BATCH)));
        if (candidates.size() < BATCH) {
            candidates.addAll(repository.findRetryable(DingTalkStatus.FAILED,
                    clock.now(), PageRequest.of(0, BATCH - candidates.size())));
        }
        Set<Long> seen = new LinkedHashSet<>();
        int sent = 0;
        for (Notification notification : candidates) {
            if (!seen.add(notification.getId())) {
                continue;
            }
            DingTalkStatus status = notificationService.dispatch(notification.getId());
            if (status == DingTalkStatus.SENT) {
                sent++;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!candidates.isEmpty()) {
            log.debug("钉钉重试一轮：candidates={}, sent={}", seen.size(), sent);
        }
    }
}
