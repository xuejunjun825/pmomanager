package com.pmo.supervise.meeting;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.PageResponse;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.meeting.dto.ConvertResult;
import com.pmo.supervise.meeting.dto.DecisionItemRequest;
import com.pmo.supervise.meeting.dto.DecisionItemRow;
import com.pmo.supervise.meeting.dto.MeetingDetail;
import com.pmo.supervise.meeting.dto.MeetingRequest;
import com.pmo.supervise.meeting.dto.MeetingRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会议与决议接口（TECHNICAL §5.3）。
 * 类级映射为 /api：PUT/DELETE /api/decision-items/{id} 不在 /meetings 前缀下，
 * 会议与决议项在同一个控制器里，避免把同一聚合拆成两处权限判断。
 * 全部路由 admin-only；会议只归档不删除，因此没有 DELETE /meetings/{id}。
 */
@RestController
@RequestMapping("/api")
public class MeetingController {

    private final MeetingService meetingService;
    private final DecisionConversionService conversionService;
    private final ShanghaiClock clock;

    public MeetingController(MeetingService meetingService, DecisionConversionService conversionService,
                             ShanghaiClock clock) {
        this.meetingService = meetingService;
        this.conversionService = conversionService;
        this.clock = clock;
    }

    @GetMapping("/meetings")
    public PageResponse<MeetingRow> list(@RequestParam(defaultValue = "false") boolean archived,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         Actor actor) {
        return meetingService.list(archived, keyword, page, size, actor);
    }

    @PostMapping("/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingDetail create(@Valid @RequestBody MeetingRequest req, Actor actor) {
        return meetingService.create(actor, req, clock.today());
    }

    @GetMapping("/meetings/{id}")
    public MeetingDetail detail(@PathVariable long id, Actor actor) {
        return meetingService.get(id, actor, clock.today());
    }

    @PutMapping("/meetings/{id}")
    public MeetingDetail update(@PathVariable long id, @Valid @RequestBody MeetingRequest req, Actor actor) {
        return meetingService.update(id, actor, req, clock.today());
    }

    @PostMapping("/meetings/{id}/archive")
    public MeetingDetail archive(@PathVariable long id, Actor actor) {
        return meetingService.archive(id, actor, clock.today());
    }

    @PostMapping("/meetings/{id}/decision-items")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionItemRow addItem(@PathVariable long id, @Valid @RequestBody DecisionItemRequest req, Actor actor) {
        return meetingService.addItem(id, actor, req, clock.today());
    }

    @PutMapping("/decision-items/{id}")
    public DecisionItemRow updateItem(@PathVariable long id, @Valid @RequestBody DecisionItemRequest req, Actor actor) {
        return meetingService.updateItem(id, actor, req, clock.today());
    }

    @DeleteMapping("/decision-items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable long id, Actor actor) {
        meetingService.deleteItem(id, actor);
    }

    @PostMapping("/meetings/{id}/convert")
    public ConvertResult convert(@PathVariable long id, @Valid @RequestBody ConvertRequest req, Actor actor) {
        return conversionService.convert(id, actor, req.itemIds());
    }

    /** 转任务勾选列表：前端默认全选，未勾选时 422 itemIds。 */
    public record ConvertRequest(@NotNull List<Long> itemIds) {
    }
}
