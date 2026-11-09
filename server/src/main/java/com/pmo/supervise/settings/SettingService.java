package com.pmo.supervise.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pmo.supervise.auth.LoginAuditService;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ShanghaiClock;
import com.pmo.supervise.notify.DingTalkSender;
import com.pmo.supervise.settings.dto.ReportSettings;
import com.pmo.supervise.settings.dto.SettingsView;
import com.pmo.supervise.settings.dto.UpdateReportSettingsRequest;
import com.pmo.supervise.task.TaskAccessGuard;
import com.pmo.supervise.user.UserService;
import com.pmo.supervise.user.dto.UserRow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 系统设置读写（TECHNICAL §4.7 / §5.5，AC-20）。
 *
 * <p>所有键集中在这里：页面、报表任务、扫描任务读的是同一份值，避免“页面显示 18:00，任务却按 09:00 发”。
 * 收件人名单以 JSON 数组存库，读取时再按用户表补齐姓名/角色（名单里的人被停用仍保留，只是无法推送钉钉）。</p>
 */
@Service
public class SettingService {

    public static final String KEY_DAILY_ENABLED = "report.daily.enabled";
    public static final String KEY_DAILY_TIME = "report.daily.time";
    public static final String KEY_WEEKLY_ENABLED = "report.weekly.enabled";
    public static final String KEY_WEEKLY_TIME = "report.weekly.time";
    public static final String KEY_RECIPIENTS = "report.recipients";

    /** 审计动作（audit_logs.action）：设置变更留痕，便于回答“谁把日报关掉了”。 */
    public static final String AUDIT_SETTINGS_UPDATED = "SETTINGS_UPDATED";

    public static final int MAX_RECIPIENTS = 20;
    private static final String DEFAULT_DAILY_TIME = "18:00";
    private static final String DEFAULT_WEEKLY_TIME = "09:00";

    private static final Logger log = LoggerFactory.getLogger(SettingService.class);

    private final SettingRepository repository;
    private final UserService userService;
    private final DingTalkSender dingTalkSender;
    private final ObjectMapper objectMapper;
    private final ShanghaiClock clock;
    private final LoginAuditService audit;
    private final TaskAccessGuard guard;
    private final JdbcTemplate jdbc;

    public SettingService(SettingRepository repository, UserService userService, DingTalkSender dingTalkSender,
                          ObjectMapper objectMapper, ShanghaiClock clock, LoginAuditService audit,
                          TaskAccessGuard guard, JdbcTemplate jdbc) {
        this.repository = repository;
        this.userService = userService;
        this.dingTalkSender = dingTalkSender;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.audit = audit;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    /** 报表设置（含收件人明细）；解析失败退回默认值并向页面暴露空名单，而不是让设置页 500。 */
    public ReportSettings report() {
        boolean dailyEnabled = Boolean.parseBoolean(get(KEY_DAILY_ENABLED, "true"));
        boolean weeklyEnabled = Boolean.parseBoolean(get(KEY_WEEKLY_ENABLED, "true"));
        String dailyTime = normalizeTime(get(KEY_DAILY_TIME, DEFAULT_DAILY_TIME), DEFAULT_DAILY_TIME);
        String weeklyTime = normalizeTime(get(KEY_WEEKLY_TIME, DEFAULT_WEEKLY_TIME), DEFAULT_WEEKLY_TIME);
        List<UserRow> recipients = resolveRecipients(readRecipientIds());
        return new ReportSettings(dailyEnabled, dailyTime, weeklyEnabled, weeklyTime,
                recipients, recipients.isEmpty());
    }

    /** GET /api/settings：报表设置 + 钉钉配置状态 + 最后更新时间；仅 admin（AC-2）。 */
    public SettingsView view(Actor actor) {
        guard.requireAdmin(actor);
        return new SettingsView(report(),
                new SettingsView.DingtalkInfo(dingTalkSender.isConfigured(), dingTalkSender.missingKeys()),
                lastUpdatedAt());
    }

    @Transactional
    public ReportSettings updateReport(Actor actor, UpdateReportSettingsRequest request) {
        return updateReport(actor, request, null);
    }

    @Transactional
    public ReportSettings updateReport(Actor actor, UpdateReportSettingsRequest request,
                                       HttpServletRequest httpRequest) {
        guard.requireAdmin(actor);
        String dailyTime = resolveTime(request.dailyTime(), KEY_DAILY_TIME, DEFAULT_DAILY_TIME, "dailyTime");
        String weeklyTime = resolveTime(request.weeklyTime(), KEY_WEEKLY_TIME, DEFAULT_WEEKLY_TIME, "weeklyTime");
        List<Long> recipientIds = distinct(request.recipientIds());
        if (recipientIds.size() > MAX_RECIPIENTS) {
            throw BusinessException.validation("recipientIds",
                    "收件人最多 " + MAX_RECIPIENTS + " 位，当前 " + recipientIds.size() + " 位");
        }
        requireExistingUsers(recipientIds);

        LocalDateTime now = clock.now();
        save(KEY_DAILY_ENABLED, Boolean.toString(request.dailyEnabled()), actor.id(), now);
        save(KEY_DAILY_TIME, dailyTime, actor.id(), now);
        save(KEY_WEEKLY_ENABLED, Boolean.toString(request.weeklyEnabled()), actor.id(), now);
        save(KEY_WEEKLY_TIME, weeklyTime, actor.id(), now);
        save(KEY_RECIPIENTS, writeRecipientIds(recipientIds), actor.id(), now);

        audit.record(actor.id(), AUDIT_SETTINGS_UPDATED, "settings", "report",
                "daily=" + request.dailyEnabled() + "@" + dailyTime
                        + ",weekly=" + request.weeklyEnabled() + "@" + weeklyTime
                        + ",recipients=" + recipientIds.size(), httpRequest);
        log.info("报表设置已更新：actor={}, daily={}@{}, weekly={}@{}, recipients={}",
                actor.id(), request.dailyEnabled(), dailyTime, request.weeklyEnabled(), weeklyTime,
                recipientIds.size());
        return report();
    }

    /** 读单个键；库中缺失（例如手工删过种子行）时用调用方给的默认值。 */
    public String get(String key, String defaultValue) {
        return repository.findByKey(key).map(Setting::getValue).orElse(defaultValue);
    }

    private void save(String key, String value, long actorId, LocalDateTime now) {
        Optional<Setting> existing = repository.findByKey(key);
        Setting setting = existing.orElseGet(() -> new Setting(key, value, actorId, now));
        setting.setValue(value);
        setting.setUpdatedBy(actorId);
        setting.setUpdatedAt(now);
        repository.save(setting);
    }

    private LocalDateTime lastUpdatedAt() {
        return jdbc.queryForObject("SELECT MAX(updated_at) FROM settings", LocalDateTime.class);
    }

    private List<Long> readRecipientIds() {
        String json = get(KEY_RECIPIENTS, "[]");
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Long[] ids = objectMapper.readValue(json, Long[].class);
            return ids == null ? List.of() : List.of(ids);
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("报表收件人配置无法解析，按空名单处理：{}", json, ex);
            return List.of();
        }
    }

    private String writeRecipientIds(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("收件人名单序列化失败", ex);
        }
    }

    /** 名单里被删除/停用的用户仍然显示（保存过就是历史事实），只是推送时按用户当前状态处理。 */
    private List<UserRow> resolveRecipients(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, UserRow> byId = userService.options(true).stream()
                .collect(Collectors.toMap(UserRow::id, Function.identity(), (a, b) -> a));
        List<UserRow> rows = new ArrayList<>();
        for (Long id : ids) {
            UserRow row = byId.get(id);
            if (row != null) {
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    private void requireExistingUsers(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, UserRow> byId = userService.options(true).stream()
                .collect(Collectors.toMap(UserRow::id, Function.identity(), (a, b) -> a));
        List<Long> missing = ids.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw BusinessException.validation("recipientIds", "收件人不存在：" + missing);
        }
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(ids.stream().filter(java.util.Objects::nonNull).toList()));
    }

    /** 非法时间退回默认值：脏数据不应让整页设置或报表任务失效。 */
    static String normalizeTime(String raw, String fallback) {
        return isValidTime(raw) ? raw : fallback;
    }

    /** 写入口的时间校验：超管绕过表单直接调接口时也必须 422，而不是把 "25:00" 落库。 */
    static String requireValidTime(String raw, String field) {
        if (!isValidTime(raw)) {
            throw BusinessException.validation(field, "时间格式须为 HH:mm（00:00–23:59）");
        }
        return raw;
    }

    /**
     * 关闭某通道时前端会提交空时间（表单清空了 time 输入）：沿用库里已有的时间，不清成空值；
     * 非空则仍按 HH:mm 严格校验，非法值 422。
     */
    private String resolveTime(String requested, String key, String fallback, String field) {
        String raw = requested == null ? "" : requested.trim();
        if (raw.isEmpty()) {
            return normalizeTime(get(key, fallback), fallback);
        }
        return requireValidTime(raw, field);
    }

    static boolean isValidTime(String raw) {
        return raw != null && raw.matches("^([01]\\d|2[0-3]):[0-5]\\d$");
    }
}
