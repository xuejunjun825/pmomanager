-- 主题主数据（TECHNICAL §2.1 / AC-8、AC-9）
-- 名称全表唯一（含停用，库层大小写不敏感）；颜色存 SourcePalette 的 8 个 hex 之一；
-- 任务两列全部可空、零回填——历史任务不需要迁移即可出现在甘特的「未归主题」行。
CREATE TABLE themes (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name       VARCHAR(40)     NOT NULL COMMENT '业务上限 2–20 字（码点）由服务端判定；列宽留余量避免截断 500',
  color      VARCHAR(16)     NOT NULL COMMENT 'SourcePalette hex；soft/border 由服务端派生，不落库',
  sort_no    INT             NOT NULL DEFAULT 0,
  enabled    TINYINT(1)      NOT NULL DEFAULT 1,
  created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_theme_name (name),
  KEY idx_themes_sort (sort_no, id),
  CONSTRAINT ck_theme_sort CHECK (sort_no BETWEEN 0 AND 99)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 主题绑定与计划开始：只加两个可空列，不触碰任何既有行（AC-3 零迁移）。
ALTER TABLE tasks
  ADD COLUMN theme_id BIGINT UNSIGNED NULL AFTER source_project_id,
  ADD COLUMN planned_start_date DATE NULL AFTER due_date,
  ADD KEY idx_tasks_theme (theme_id),
  ADD KEY idx_tasks_planned_due (planned_start_date, due_date),
  ADD CONSTRAINT fk_tasks_theme FOREIGN KEY (theme_id)
    REFERENCES themes (id) ON UPDATE RESTRICT ON DELETE RESTRICT;
