package com.pmo.supervise.meeting;

import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.meeting.dto.ConvertResult;
import com.pmo.supervise.source.SourceCodes;
import com.pmo.supervise.task.TaskAccessGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 决议项勾选转任务（AC-4 / TECHNICAL §4.4）。
 * 单事务逐条 INSERT；重复点击不报错也不产生重复任务——命中 uk_tasks_decision 的项进 skipped，
 * 因此部分成功是正常结果，用 created/skipped 表达而不是整批回滚。
 * 任务来源由库层 CHECK 约束固定为「会议决议」形态，派发人=当前管理员。
 */
@Service
public class DecisionConversionService {

    private static final String INSERT_TASK = """
            INSERT INTO tasks
                (title, owner_id, due_date, source_type, source_meeting_id, source_decision_item_id,
                 source_note, status, dispatcher_id, priority)
            VALUES (?, ?, ?, ?, ?, ?, NULL, 'PENDING', ?, 'MEDIUM')
            """;

    private final MeetingService meetingService;
    private final DecisionItemRepository itemRepository;
    private final TaskAccessGuard guard;
    private final JdbcTemplate jdbc;
    private final ShanghaiClock clock;

    public DecisionConversionService(MeetingService meetingService, DecisionItemRepository itemRepository,
                                     TaskAccessGuard guard, JdbcTemplate jdbc, ShanghaiClock clock) {
        this.meetingService = meetingService;
        this.itemRepository = itemRepository;
        this.guard = guard;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ConvertResult convert(long meetingId, Actor actor, List<Long> itemIds) {
        guard.requireAdmin(actor);
        meetingService.require(meetingId);
        // 归档会议新增/编辑/删除决议项都是 409；转任务属于同一类写操作，不能从这里绕过归档锁
        if (meetingService.isArchived(meetingId)) {
            throw new BusinessException(ErrorCode.MEETING_ARCHIVED);
        }

        List<Long> requested = itemIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(itemIds.stream().filter(Objects::nonNull).toList()));
        if (requested.isEmpty()) {
            throw BusinessException.validation("itemIds", "请选择要转任务的决议事项");
        }

        List<DecisionItem> items = itemRepository.findByIdInForUpdate(requested).stream()
                .sorted(Comparator.comparingLong(DecisionItem::getId))
                .toList();
        if (items.size() != requested.size()) {
            throw BusinessException.notFound("决议事项不存在");
        }
        for (DecisionItem item : items) {
            if (item.getMeetingId() != meetingId) {
                // 与「owner 越权任务一律 404」同一语义：不暴露其他会议的决议项是否存在
                throw BusinessException.notFound("决议事项不存在");
            }
        }

        List<ConvertResult.Created> created = new ArrayList<>();
        List<ConvertResult.Skipped> skipped = new ArrayList<>();
        for (DecisionItem item : items) {
            Long existing = findTaskId(item.getId());
            if (existing != null) {
                skipped.add(new ConvertResult.Skipped(item.getId(), ConvertResult.Skipped.ALREADY_CONVERTED, existing));
                continue;
            }
            try {
                long taskId = insertTask(item, actor.id());
                created.add(new ConvertResult.Created(item.getId(), taskId));
            } catch (DuplicateKeyException race) {
                // 并发转换同一决议项：唯一键是最终裁决，后到者归入 skipped
                Long winner = findTaskId(item.getId());
                skipped.add(new ConvertResult.Skipped(item.getId(), ConvertResult.Skipped.ALREADY_CONVERTED, winner));
            }
        }
        return new ConvertResult(created, skipped);
    }

    private long insertTask(DecisionItem item, long dispatcherId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_TASK, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, item.getDescription());
            ps.setLong(2, item.getOwnerId());
            ps.setObject(3, item.getDueDate() == null ? clock.today() : item.getDueDate());
            ps.setString(4, SourceCodes.MEETING);
            ps.setLong(5, item.getMeetingId());
            ps.setLong(6, item.getId());
            ps.setLong(7, dispatcherId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("转任务未返回主键");
        }
        return key.longValue();
    }

    private Long findTaskId(long itemId) {
        List<Long> ids = jdbc.query("SELECT id FROM tasks WHERE source_decision_item_id = ?",
                (rs, rowNum) -> rs.getLong(1), itemId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
