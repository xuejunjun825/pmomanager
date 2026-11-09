package com.pmo.supervise.notify;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.notify.dto.NotificationPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 通知接口（TECHNICAL §5.5）：全部为本人视角；越权按 id 探取他人通知返回 404。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public NotificationPage list(Actor actor,
                                 @RequestParam(defaultValue = "false") boolean unreadOnly,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        return notificationService.page(actor.id(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(Actor actor) {
        return Map.of("count", notificationService.unreadCount(actor.id()));
    }

    @PostMapping("/{id}/read")
    public void read(@PathVariable long id, Actor actor) {
        notificationService.markRead(id, actor.id());
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll(Actor actor) {
        return Map.of("updated", notificationService.markAllRead(actor.id()));
    }
}
