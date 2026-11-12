package com.pmo.supervise.theme;

import com.pmo.supervise.IntegrationTestSupport;
import com.pmo.supervise.common.Actor;
import com.pmo.supervise.common.ApiError;
import com.pmo.supervise.common.BusinessException;
import com.pmo.supervise.common.ErrorCode;
import com.pmo.supervise.common.Role;
import com.pmo.supervise.source.SourcePalette;
import com.pmo.supervise.theme.dto.ThemeOption;
import com.pmo.supervise.theme.dto.ThemeRequest;
import com.pmo.supervise.theme.dto.ThemeView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 主题服务层护栏（TECHNICAL §3.1 / §6.1 / AC-8）：
 * 名称 2–20 全表唯一（含停用）、8 色默认分配、上限 100、被引用只能停用、停用后不可选。
 */
class ThemeServiceTest extends IntegrationTestSupport {

    @Autowired
    private ThemeService themes;

    private MockHttpServletRequest http() {
        return new MockHttpServletRequest();
    }

    /**
     * 共享测试底座不清理 themes（它由 T1 新增），这里自行清；先断开任务的引用，
     * 否则删除主题会撞 fk_tasks_theme。留在 @BeforeEach 也保证每个用例从空表开始。
     */
    @BeforeEach
    void cleanThemes() {
        jdbc.update("UPDATE tasks SET theme_id = NULL WHERE theme_id IS NOT NULL");
        jdbc.update("DELETE FROM themes");
    }

    @AfterEach
    void leaveNoThemeRows() {
        jdbc.update("UPDATE tasks SET theme_id = NULL WHERE theme_id IS NOT NULL");
        jdbc.update("DELETE FROM themes");
    }

    private Actor admin() {
        long id = seedUser("thmadmin", "主题管理员", Role.ADMIN, "ACTIVE");
        return actor(id);
    }

    private ThemeView create(Actor actor, String name, String color, Integer sort, Boolean enabled) {
        return themes.create(actor, new ThemeRequest(name, color, sort, enabled), http());
    }

    @Test
    void createTrimsNameAndAssignsPaletteColorsInOrder() {
        Actor admin = admin();

        ThemeView first = create(admin, "  Q4 交付冲刺  ", null, null, null);
        assertThat(first.name()).isEqualTo("Q4 交付冲刺");
        assertThat(first.color()).isEqualTo("#1B4D8F");
        assertThat(first.softColor()).isEqualTo("#EAF1FA");
        assertThat(first.borderColor()).isEqualTo("#C7D9EF");
        assertThat(first.sort()).isZero();
        assertThat(first.enabled()).isTrue();
        assertThat(first.usageCount()).isZero();

        // 第二个未指定颜色 → 色板第二个；显式颜色/排序/停用按请求落库。
        ThemeView second = create(admin, "二期主题", null, null, null);
        assertThat(second.color()).isEqualTo("#3538CD");
        assertThat(second.softColor()).isEqualTo("#EEF4FF");
        assertThat(second.sort()).isEqualTo(1);

        ThemeView explicit = create(admin, "绿色主题", "#067647", 7, false);
        assertThat(explicit.color()).isEqualTo("#067647");
        assertThat(explicit.softColor()).isEqualTo("#ECFDF3");
        assertThat(explicit.borderColor()).isEqualTo("#ABEFC6");
        assertThat(explicit.sort()).isEqualTo(7);
        assertThat(explicit.enabled()).isFalse();

        List<ThemeOption> options = themes.options();
        assertThat(options).extracting(ThemeOption::name)
                .containsExactly("Q4 交付冲刺", "二期主题", "绿色主题");
        assertThat(options.get(2).borderColor()).isEqualTo("#ABEFC6");

        assertThat(themes.palette()).hasSize(8);
        assertThat(themes.palette().get(0).color()).isEqualTo("#1B4D8F");
        assertThat(themes.palette().get(7).color()).isEqualTo("#3F6212");
    }

    @Test
    void nameIsTwoToTwentyCodePointsAndGloballyUniqueIncludingDisabled() {
        Actor admin = admin();
        ThemeView created = create(admin, "Q4 交付冲刺", null, null, null);
        assertThat(created.name()).isEqualTo("Q4 交付冲刺");

        assertThatThrownBy(() -> create(admin, "  q4 交付冲刺 ", null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.THEME_NAME_DUPLICATE);
                    assertThat(fields(ex)).contains("name");
                });

        create(admin, "已停用主题", "#6941C6", null, false);
        assertThatThrownBy(() -> create(admin, "已停用主题", null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.THEME_NAME_DUPLICATE));

        assertThatThrownBy(() -> create(admin, "主", null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(fields(ex)).containsExactly("name");
                });
        assertThatThrownBy(() -> create(admin, "一二三四五六七八九十一二三四五六七八九十一", null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(fields(ex)).containsExactly("name");
                });

        // 恰好 20 个码点合法（列宽 40 只是余量）。
        ThemeView twenty = create(admin, "一二三四五六七八九十一二三四五六七八九十", null, null, null);
        assertThat(twenty.name()).hasSize(20);
    }

    @Test
    void limitIsOneHundredIncludingDisabled() {
        Actor admin = admin();
        for (int i = 0; i < ThemeService.LIMIT; i++) {
            jdbc.update("INSERT INTO themes (name, color, sort_no, enabled) VALUES (?, ?, ?, ?)",
                    "主题" + i, SourcePalette.entries().get(i % 8).color(), i, i % 2 == 0);
        }

        assertThatThrownBy(() -> create(admin, "第 101 个", null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.THEME_LIMIT_REACHED);
                    assertThat(ex.getMessage()).contains("100");
                });
        assertThat(themes.list()).hasSize(ThemeService.LIMIT);
    }

    @Test
    void referencedThemeCanOnlyBeDisabledAndDeleteIsReadable409() {
        Actor admin = admin();
        ThemeView theme = create(admin, "被引用主题", null, null, null);

        long ownerId = seedUser("thmowner", "任务负责人", Role.OWNER, "ACTIVE");
        long taskId = seedTask("挂主题的任务", ownerId, LocalDate.of(2026, 11, 20), "PENDING", admin.id());
        jdbc.update("UPDATE tasks SET theme_id = ? WHERE id = ?", theme.id(), taskId);

        assertThat(themes.list()).singleElement().satisfies(row -> assertThat(row.usageCount()).isEqualTo(1));

        assertThatThrownBy(() -> themes.delete(theme.id(), admin, http()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.THEME_IN_USE);
                    assertThat(ex.getMessage()).contains("1 条任务").contains("只能停用");
                    assertThat(ex.details()).containsEntry("taskCount", 1L);
                });

        ThemeView disabled = themes.setEnabled(theme.id(), admin, false, http());
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.usageCount()).isEqualTo(1);

        assertThatThrownBy(() -> themes.requireSelectable(theme.id()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(fields(ex)).containsExactly("themeId");
                    assertThat(ex.getMessage()).contains("已停用");
                });
        // 停用只是不可新选，历史任务仍能按 id 解析与展示。
        assertThat(themes.requireKnown(theme.id()).enabled()).isFalse();

        // 仍被引用时即使停用也不能物理删除。
        assertThatThrownBy(() -> themes.delete(theme.id(), admin, http()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.THEME_IN_USE));

        jdbc.update("UPDATE tasks SET theme_id = NULL WHERE id = ?", taskId);
        themes.delete(theme.id(), admin, http());
        assertThat(themes.list()).isEmpty();
    }

    @Test
    void selectableNullIsLegalAndUnknownOrNonAdminAreRejected() {
        Actor admin = admin();
        ThemeView theme = create(admin, "可选主题", null, null, null);

        assertThat(themes.requireSelectable(null)).isNull();
        assertThat(themes.requireSelectable(theme.id()).name()).isEqualTo("可选主题");
        assertThatThrownBy(() -> themes.requireSelectable(999_999L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(fields(ex)).containsExactly("themeId");
                });
        assertThatThrownBy(() -> themes.requireKnown(999_999L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.getMessage()).isEqualTo("主题不存在");
                });

        long ownerId = seedUser("thmreader", "只读用户", Role.OWNER, "ACTIVE");
        Actor owner = actor(ownerId);
        assertThat(themes.list()).extracting(ThemeView::name).containsExactly("可选主题");
        assertThat(themes.options()).extracting(ThemeOption::name).containsExactly("可选主题");
        assertThatThrownBy(() -> create(owner, "越权主题", null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> themes.delete(theme.id(), owner, http()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void invalidColorIsRejectedWithFieldError() {
        Actor admin = admin();
        assertThatThrownBy(() -> create(admin, "非法颜色", "#000000", null, null))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(fields(ex)).containsExactly("color");
                    assertThat(ex.getMessage()).contains("色板");
                });
        // 小写与省略 # 同样归一化后命中色板。
        assertThat(create(admin, "归一颜色", "067647", null, null).color()).isEqualTo("#067647");
    }

    @Test
    void everyWriteLeavesAnAuditRowWithReadableTarget() {
        Actor admin = admin();
        ThemeView created = create(admin, "审计主题", null, 2, null);
        themes.update(created.id(), admin,
                new ThemeRequest("审计主题改名", "#3538CD", 4, null), http());
        themes.setEnabled(created.id(), admin, false, http());
        themes.setEnabled(created.id(), admin, true, http());
        themes.delete(created.id(), admin, http());

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, target_id, detail FROM audit_logs
                 WHERE target_type = 'THEME' ORDER BY id
                """);
        assertThat(rows).extracting(row -> row.get("action")).containsExactly(
                ThemeService.AUDIT_CREATED, ThemeService.AUDIT_UPDATED,
                ThemeService.AUDIT_DISABLED, ThemeService.AUDIT_ENABLED,
                ThemeService.AUDIT_DELETED);
        assertThat(rows).extracting(row -> row.get("target_id"))
                .containsOnly(String.valueOf(created.id()));
        assertThat((String) rows.get(0).get("detail"))
                .contains("name=审计主题").contains("color=#1B4D8F").contains("sort=2");
        assertThat((String) rows.get(1).get("detail"))
                .contains("name=审计主题→审计主题改名")
                .contains("color=#1B4D8F→#3538CD")
                .contains("sort=2→4");
        assertThat((String) rows.get(2).get("detail")).isEqualTo("name=审计主题改名; enabled=1→0");
        assertThat((String) rows.get(3).get("detail")).isEqualTo("name=审计主题改名; enabled=0→1");
    }

    private static List<String> fields(BusinessException ex) {
        return ex.fieldErrors().stream().map(ApiError.FieldError::field).toList();
    }
}
